// 在线网络质量探测 Agent 模块（设计文档 §5/§6）。
//
// 心跳拉到 probe_tasks 后入队，由独立工作线程串行执行（单设备并发 1、其余排队），
// 不阻塞心跳 current-thread 运行时。三种探测：
//   - ping：系统自带 ping 子进程（免 root/免新权限），参数数组调用，不经 shell
//   - tcp：tokio TcpStream，3 次×2s，成功即停
//   - http：专用 reqwest client，硬超时 15s，不重试、不下载 body、最多 3 跳转
// 结果经 post_request 上报 /api/probe/report，带 X-Probe-Token，失败重试 2 次。

use std::{
    collections::{HashMap, HashSet},
    io::ErrorKind,
    sync::{
        mpsc::{sync_channel, Receiver, SyncSender},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};

use hbb_common::{
    config::Config,
    log,
    tokio::{self, net::TcpStream},
};
use serde::Deserialize;
use serde_json::{json, Map, Value};

const PROBE_TOKEN: &str = "sc-probe-2026-10";
const QUEUE_CAP: usize = 8;
const DONE_CACHE_MAX: usize = 64; // 已执行任务结果缓存上限，超过整体清空

const HARD_TIMEOUT: Duration = Duration::from_secs(15);
const DNS_TIMEOUT: Duration = Duration::from_secs(3);
const PING_TIMEOUT: Duration = Duration::from_secs(9); // 3 包×2s + 间隔兜底
const TCP_CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const TCP_GAP: Duration = Duration::from_millis(500);
const MAX_REDIRECTS_DEFAULT: u64 = 3;

const STATUS_DONE: &str = "done";
const STATUS_TIMEOUT: &str = "timeout";
const STATUS_FAILED: &str = "failed";

// 上报失败重试间隔（首次立即发，之后 2s/5s 各一次）
const REPORT_RETRY_GAPS: [Duration; 2] = [Duration::from_secs(2), Duration::from_secs(5)];

/// 心跳下发的探测任务（协议见设计 §5.1）。未知字段默认忽略，向前兼容。
#[derive(Debug, Clone, Deserialize)]
pub struct ProbeTask {
    pub task_id: String,
    pub probe_type: String,
    pub target: String,
    #[serde(default)]
    pub port: u16,
    #[serde(default)]
    pub timeout_ms: u32,
    #[serde(default)]
    pub extra: Map<String, Value>,
}

// ---------- 入队与工作线程 ----------

lazy_static::lazy_static! {
    static ref QUEUE: Mutex<SyncSender<ProbeTask>> = Mutex::new(start_worker());
}

/// 心跳入口：非阻塞入队，队列满（设备长期卡顿）直接丢弃并告警，服务端 TTL 兜底。
pub fn spawn(task: ProbeTask) {
    if task.task_id.is_empty() || task.target.is_empty() {
        log::warn!("probe task missing task_id/target, dropped: {:?}", task);
        return;
    }
    if let Err(e) = QUEUE.lock().unwrap().try_send(task) {
        log::warn!("probe enqueue failed (queue full or worker gone): {}", e);
    }
}

fn start_worker() -> SyncSender<ProbeTask> {
    let (tx, rx) = sync_channel::<ProbeTask>(QUEUE_CAP);
    if let Err(e) = std::thread::Builder::new()
        .name("probe-worker".to_owned())
        .spawn(move || {
            // 独立 current-thread 运行时：探测全部异步化，与心跳线程互不影响
            let rt = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(rt) => rt,
                Err(e) => {
                    log::error!("probe worker runtime build failed: {}", e);
                    return;
                }
            };
            rt.block_on(worker_loop(rx));
        })
    {
        log::error!("probe worker thread spawn failed: {}", e);
    }
    tx
}

async fn worker_loop(rx: Receiver<ProbeTask>) {
    // task_id -> 上次完整上报 body：重复任务直接重发结果，不重复探测
    let mut done: HashMap<String, String> = HashMap::new();
    while let Ok(task) = rx.recv() {
        let body = match done.get(&task.task_id) {
            Some(cached) => {
                log::info!("probe duplicate task {}, re-report cached result", task.task_id);
                cached.clone()
            }
            None => {
                let body = run_task(&task).await;
                if done.len() >= DONE_CACHE_MAX {
                    done.clear();
                }
                done.insert(task.task_id.clone(), body.clone());
                body
            }
        };
        report_with_retry(&body).await;
    }
}

// ---------- 执行框架 ----------

async fn run_task(task: &ProbeTask) -> String {
    let client_ts = chrono::Utc::now().timestamp();
    let (status, error_msg, result) = match task.probe_type.as_str() {
        "ping" => probe_ping(task).await,
        "tcp" => probe_tcp(task).await,
        "http" => probe_http(task).await,
        other => (
            STATUS_FAILED.to_owned(),
            format!("unknown probe_type: {}", other),
            base_result(task, skipped_dns("unknown_probe_type")),
        ),
    };
    json!({
        "task_id": task.task_id,
        "peer_id": Config::get_id(),
        "client_ts": client_ts,
        "status": status,
        "result": result,
        "error_msg": error_msg,
    })
    .to_string()
}

/// 通用外层结构（设计 §5.4）；dns 段由各探测函数预解析后传入。
fn base_result(task: &ProbeTask, dns: Value) -> Value {
    let transport = Config::get_option("net-transport");
    let transport = if transport.is_empty() { "unknown" } else { transport.as_str() };
    json!({
        "probe_type": task.probe_type,
        "target": task.target,
        "agent": {
            "platform": std::env::consts::OS,
            "version": crate::VERSION,
            "transport": transport,
        },
        "dns": dns,
    })
}

// ---------- DNS 预解析 ----------

async fn resolve_dns(host: &str) -> Value {
    // 字面量 IP 不触发解析
    if host.parse::<std::net::IpAddr>().is_ok() {
        return json!({ "resolved_ips": [host], "elapsed_ms": 0, "error": "" });
    }
    let started = Instant::now();
    match tokio::time::timeout(DNS_TIMEOUT, tokio::net::lookup_host((host, 0u16))).await {
        Ok(Ok(addrs)) => {
            let mut seen = HashSet::new();
            let ips: Vec<String> = addrs
                .filter_map(|sa| {
                    let ip = sa.ip().to_string();
                    seen.insert(ip.clone()).then_some(ip)
                })
                .collect();
            json!({
                "resolved_ips": ips,
                "elapsed_ms": ms(started.elapsed()),
                "error": "",
            })
        }
        Ok(Err(e)) => json!({
            "resolved_ips": [],
            "elapsed_ms": ms(started.elapsed()),
            "error": format!("dns_lookup_failed: {}", e),
        }),
        Err(_) => json!({
            "resolved_ips": [],
            "elapsed_ms": ms(DNS_TIMEOUT),
            "error": "dns_timeout",
        }),
    }
}

// ---------- ping ----------

/// 目标字符白名单：只允许主机名字符，禁 shell 元字符；配合参数数组杜绝命令注入。
fn is_safe_host(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= 255
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b':')
}

async fn probe_ping(task: &ProbeTask) -> (String, String, Value) {
    let host = task.target.trim();
    if !is_safe_host(host) {
        return (
            STATUS_FAILED.to_owned(),
            "invalid ping target".to_owned(),
            base_result(task, json!({ "resolved_ips": [], "elapsed_ms": 0, "error": "invalid_target" })),
        );
    }
    let dns = resolve_dns(host).await;
    // DNS 预解析失败不阻断：系统 ping 仍可能自行解析成功，dns.error 如实上报
    let count = clamp_usize(extra_u64(task, "count", 3), 1, 10);

    let mut cmd = build_ping_command(host, count);
    let started = Instant::now();
    let output = match tokio::time::timeout(PING_TIMEOUT, cmd.output()).await {
        Ok(Ok(o)) => o,
        Ok(Err(e)) => {
            return (
                STATUS_FAILED.to_owned(),
                format!("ping spawn failed: {}", e),
                base_result(task, dns),
            )
        }
        Err(_) => {
            return (
                STATUS_TIMEOUT.to_owned(),
                "ping hard timeout".to_owned(),
                base_result(task, dns),
            )
        }
    };
    let elapsed = started.elapsed();
    let exit_code = output.status.code().unwrap_or(-1);
    let mut text = String::from_utf8_lossy(&output.stdout).to_string();
    if !output.stderr.is_empty() {
        text.push('\n');
        text.push_str(&String::from_utf8_lossy(&output.stderr));
    }
    let stats = parse_ping_output(current_flavor(), &text);
    // 退出码非零通常是 100% 丢包这类业务失败，探测本身执行成功，仍按 done 上报
    let ping = json!({
        "sent": stats.sent,
        "received": stats.received,
        "loss_pct": stats.loss_pct,
        "rtt_min_ms": stats.rtt_min,
        "rtt_avg_ms": stats.rtt_avg,
        "rtt_max_ms": stats.rtt_max,
        "rtt_mdev_ms": stats.rtt_mdev,
        "exit_code": exit_code,
        "elapsed_ms": ms(elapsed),
        "raw_tail": tail(&text, 200),
    });
    let mut result = base_result(task, dns);
    result["ping"] = ping;
    (STATUS_DONE.to_owned(), String::new(), result)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PingFlavor {
    Unix, // Linux / Android / macOS 输出同构
    Windows,
}

fn current_flavor() -> PingFlavor {
    #[cfg(windows)]
    {
        PingFlavor::Windows
    }
    #[cfg(not(windows))]
    {
        PingFlavor::Unix
    }
}

fn build_ping_command(host: &str, count: usize) -> hbb_common::tokio::process::Command {
    let count = count.to_string();
    #[cfg(windows)]
    let bin = "ping.exe";
    #[cfg(not(windows))]
    let bin = "ping";
    let mut cmd = hbb_common::tokio::process::Command::new(bin);
    // 超时后 drop 即 kill，避免子进程悬挂占住工作线程
    cmd.kill_on_drop(true);
    #[cfg(windows)]
    {
        // -w 单位毫秒
        cmd.args(["-n", count.as_str(), "-w", "2000"]);
    }
    #[cfg(target_os = "macos")]
    {
        // macOS 的 -W 单位是毫秒
        cmd.args(["-c", count.as_str(), "-W", "2000"]);
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        // Linux/Android 的 -W 单位秒
        cmd.args(["-c", count.as_str(), "-W", "2"]);
    }
    cmd.arg(host);
    cmd
}

#[derive(Debug, Default, PartialEq)]
struct PingStats {
    sent: u32,
    received: u32,
    loss_pct: f64,
    rtt_min: f64,
    rtt_avg: f64,
    rtt_max: f64,
    rtt_mdev: f64,
}

/// 解析入口按平台分发；解析器为纯函数，全部平台都参与编译与单测。
fn parse_ping_output(flavor: PingFlavor, text: &str) -> PingStats {
    match flavor {
        PingFlavor::Unix => parse_ping_unix(text),
        PingFlavor::Windows => parse_ping_windows(text),
    }
}

/// Linux/Android/macOS：
/// `3 packets transmitted, 3 received, 0% packet loss`
/// `rtt min/avg/max/mdev = 0.047/0.062/0.081/0.014 ms`（macOS 为 stddev）
fn parse_ping_unix(text: &str) -> PingStats {
    let mut s = PingStats::default();
    for line in text.lines() {
        let lower = line.to_lowercase();
        if lower.contains("packets transmitted") {
            let tokens: Vec<&str> = lower.split_whitespace().collect();
            if let Some(v) = tokens.first().and_then(|t| t.parse::<u32>().ok()) {
                s.sent = v;
            }
            if let Some(v) = tokens.get(3).and_then(|t| t.parse::<u32>().ok()) {
                s.received = v;
            }
            if let Some(p) = tokens.iter().find(|t| t.ends_with('%')) {
                s.loss_pct = p.trim_end_matches('%').parse().unwrap_or(0.0);
            }
        } else if lower.contains("min/avg/max") {
            if let Some((_, rhs)) = line.split_once('=') {
                let nums: Vec<f64> = rhs
                    .split_whitespace()
                    .next()
                    .unwrap_or("")
                    .split('/')
                    .filter_map(|x| x.parse().ok())
                    .collect();
                if nums.len() >= 3 {
                    s.rtt_min = nums[0];
                    s.rtt_avg = nums[1];
                    s.rtt_max = nums[2];
                    s.rtt_mdev = nums.get(3).copied().unwrap_or(0.0);
                }
            }
        }
    }
    s
}

/// Windows 中英文输出按"位置"解析，不依赖本地化文案，因此对 CP936(GBK) 控制台
/// 经 lossy 转换后的乱码同样有效：
///   统计行 `Packets: Sent = 3, Received = 3, Lost = 0 (0% loss)`
///         / `数据包: 已发送 = 3，已接收 = 3，已丢失 = 0 (0% 丢失)`
///   RTT 行固定顺序 min/max/avg（最短/最长/平均），均为 `标识 = 数值ms`
fn parse_ping_windows(text: &str) -> PingStats {
    let mut s = PingStats::default();
    let lines: Vec<&str> = text.lines().collect();
    let stats_idx = lines.iter().position(|l| l.contains('(') && l.contains('%'));
    if let Some(idx) = stats_idx {
        let stats_line = lines[idx];
        let vals = eq_values(stats_line);
        if let Some(v) = vals.first() {
            s.sent = *v as u32;
        }
        if let Some(v) = vals.get(1) {
            s.received = *v as u32;
        }
        s.loss_pct = pct_in_parens(stats_line).unwrap_or(0.0);
        // 统计行之后的 RTT 块：中英文都可能把 min/max/avg 压在同一行
        // （`Minimum = 11ms, Maximum = 13ms, Average = 12ms`），故拍平所有等号值，
        // 按固定顺序 min/max/avg（最短/最长/平均）取值
        let rtt: Vec<f64> = lines[idx + 1..]
            .iter()
            .filter(|l| l.contains('=') && l.to_lowercase().contains("ms"))
            .flat_map(|l| eq_values(l))
            .collect();
        if let Some(v) = rtt.first() {
            s.rtt_min = *v;
        }
        if let Some(v) = rtt.get(1) {
            s.rtt_max = *v;
        }
        if let Some(v) = rtt.get(2) {
            s.rtt_avg = *v;
        }
    }
    s
}

/// 取一行中所有 `= 数值` 前缀数字，忽略本地化标签。
fn eq_values(line: &str) -> Vec<f64> {
    line.split('=')
        .skip(1)
        .filter_map(|part| {
            let num: String = part
                .trim()
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '.')
                .collect();
            num.parse().ok()
        })
        .collect()
}

/// 取括号内百分比数字：`(0% loss)` / `(100% 丢失)` → 0 / 100。
fn pct_in_parens(line: &str) -> Option<f64> {
    let l = line.find('(')?;
    let r = line[l..].find('%')? + l;
    line[l + 1..r].trim().parse().ok()
}

// ---------- tcp ----------

async fn probe_tcp(task: &ProbeTask) -> (String, String, Value) {
    let host = task.target.trim();
    if !is_safe_host(host) || task.port == 0 {
        return (
            STATUS_FAILED.to_owned(),
            "invalid tcp target or port".to_owned(),
            base_result(task, json!({ "resolved_ips": [], "elapsed_ms": 0, "error": "invalid_target" })),
        );
    }
    let dns = resolve_dns(host).await;
    let attempts_n = clamp_usize(extra_u64(task, "attempts", 3), 1, 5) as u32;

    let mut attempts = Vec::new();
    let mut connected = false;
    let mut first_connect_ms = 0.0;
    for seq in 1..=attempts_n {
        let started = Instant::now();
        let attempt = match tokio::time::timeout(
            TCP_CONNECT_TIMEOUT,
            TcpStream::connect((host, task.port)),
        )
        .await
        {
            Ok(Ok(_stream)) => {
                let elapsed = ms(started.elapsed());
                if !connected {
                    connected = true;
                    first_connect_ms = elapsed;
                }
                json!({
                    "seq": seq,
                    "ok": true,
                    "elapsed_ms": elapsed,
                    "error": "",
                })
            }
            Ok(Err(e)) => json!({
                "seq": seq,
                "ok": false,
                "elapsed_ms": ms(started.elapsed()),
                "error": classify_io(&e),
            }),
            Err(_) => json!({
                "seq": seq,
                "ok": false,
                "elapsed_ms": ms(TCP_CONNECT_TIMEOUT),
                "error": "timeout",
            }),
        };
        let ok = attempt["ok"].as_bool().unwrap_or(false);
        attempts.push(attempt);
        if ok {
            break; // 成功即停
        }
        if seq < attempts_n {
            tokio::time::sleep(TCP_GAP).await;
        }
    }
    let tcp = json!({
        "attempts": attempts,
        "connected": connected,
        "first_connect_ms": first_connect_ms,
    });
    let mut result = base_result(task, dns);
    result["tcp"] = tcp;
    (STATUS_DONE.to_owned(), String::new(), result)
}

fn classify_io(e: &std::io::Error) -> &'static str {
    match e.kind() {
        ErrorKind::TimedOut => "timeout",
        ErrorKind::ConnectionRefused => "connection_refused",
        ErrorKind::NetworkUnreachable => "network_unreachable",
        _ => "other",
    }
}

// ---------- http ----------

async fn probe_http(task: &ProbeTask) -> (String, String, Value) {
    let parsed = match url::Url::parse(task.target.trim()) {
        Ok(u) if u.scheme() == "http" || u.scheme() == "https" => u,
        Ok(_) => {
            return (
                STATUS_FAILED.to_owned(),
                "http target must be http/https url".to_owned(),
                base_result(task, skipped_dns("invalid_scheme")),
            )
        }
        Err(e) => {
            return (
                STATUS_FAILED.to_owned(),
                format!("invalid url: {}", e),
                base_result(task, skipped_dns("invalid_url")),
            )
        }
    };
    let host = parsed.host_str().unwrap_or("").to_owned();
    let dns = resolve_dns(&host).await;
    let max_redirects = clamp_usize(extra_u64(task, "max_redirects", MAX_REDIRECTS_DEFAULT), 0, 5);

    // 记录每一跳 from→to，custom 策略内部同时完成 limited(n) 语义
    let chain = Arc::new(Mutex::new(Vec::<String>::new()));
    let policy = reqwest::redirect::Policy::custom({
        let chain = chain.clone();
        move |attempt| {
            if attempt.previous().len() >= max_redirects {
                attempt.stop()
            } else {
                if let Some(prev) = attempt.previous().last() {
                    chain
                        .lock()
                        .unwrap()
                        .push(format!("{}→{}", prev, attempt.url()));
                }
                attempt.follow()
            }
        }
    });
    // 专用 client：超时策略与业务 client 不同，按设计不复用
    let client = match reqwest::Client::builder()
        .redirect(policy)
        .timeout(HARD_TIMEOUT)
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            return (
                STATUS_FAILED.to_owned(),
                format!("http client build failed: {}", e),
                base_result(task, dns),
            )
        }
    };

    let started = Instant::now();
    // reqwest 0.12 不暴露 TCP/TLS 分段回调，tcp/tls 两段如实置 0；
    // dns 为手动预解析实测，ttfb/total 为 send 到响应头返回（不读 body）。
    let dns_ms = dns["elapsed_ms"].as_f64().unwrap_or(0.0);
    match tokio::time::timeout(HARD_TIMEOUT, client.get(task.target.trim()).send()).await {
        Ok(Ok(resp)) => {
            let elapsed = ms(started.elapsed());
            let status_code = resp.status().as_u16();
            let final_url = resp.url().to_string();
            drop(resp); // 拿到响应头立即断开，不下载 body
            let redirect_chain = chain.lock().unwrap().clone();
            let http = json!({
                "final_url": final_url,
                "status_code": status_code,
                "redirect_chain": redirect_chain,
                "timing_ms": {
                    "dns": dns_ms,
                    "tcp": 0,
                    "tls": 0,
                    "ttfb": elapsed,
                    "total": elapsed,
                },
                "error": "",
            });
            let mut result = base_result(task, dns);
            result["http"] = http;
            // 4xx/5xx 也是有效探测结果，status 仍为 done
            (STATUS_DONE.to_owned(), String::new(), result)
        }
        Ok(Err(e)) => {
            let status = if e.is_timeout() {
                STATUS_TIMEOUT
            } else {
                STATUS_FAILED
            };
            let error_msg = if e.is_timeout() {
                "http timeout (dns/tcp/tls/ttfb stage unknown)".to_owned()
            } else {
                format!("http request failed: {}", e)
            };
            let http = json!({
                "final_url": "",
                "status_code": 0,
                "redirect_chain": chain.lock().unwrap().clone(),
                "timing_ms": {
                    "dns": dns_ms,
                    "tcp": 0,
                    "tls": 0,
                    "ttfb": ms(started.elapsed()),
                    "total": ms(started.elapsed()),
                },
                "error": error_msg,
            });
            let mut result = base_result(task, dns);
            result["http"] = http;
            (status.to_owned(), error_msg, result)
        }
        Err(_) => {
            // 双层超时兜底：client 自身 timeout 通常先返回 error，走到这里说明整体卡死
            let http = json!({
                "final_url": "",
                "status_code": 0,
                "redirect_chain": chain.lock().unwrap().clone(),
                "timing_ms": {
                    "dns": dns_ms,
                    "tcp": 0,
                    "tls": 0,
                    "ttfb": ms(HARD_TIMEOUT),
                    "total": ms(HARD_TIMEOUT),
                },
                "error": "http hard timeout 15s",
            });
            let mut result = base_result(task, dns);
            result["http"] = http;
            (
                STATUS_TIMEOUT.to_owned(),
                "http hard timeout 15s".to_owned(),
                result,
            )
        }
    }
}

// ---------- 上报 ----------

async fn report_with_retry(body: &str) {
    let base = crate::hbbs_http::sync::api_server_base();
    if base.is_empty() {
        log::warn!("probe report skipped: api server base empty");
        return;
    }
    let url = format!("{}/api/probe/report", base);
    let header = format!("X-Probe-Token: {}", PROBE_TOKEN);
    // 首次立即发；失败按 2s/5s 重试，仍失败丢弃，服务端 TTL 兜底
    for attempt in 0..=REPORT_RETRY_GAPS.len() {
        if attempt > 0 {
            tokio::time::sleep(REPORT_RETRY_GAPS[attempt - 1]).await;
        }
        match crate::post_request(url.clone(), body.to_owned(), &header).await {
            Ok(text) => {
                if serde_json::from_str::<Value>(&text)
                    .ok()
                    .and_then(|v| v.get("code").and_then(|c| c.as_i64()))
                    == Some(0)
                {
                    return;
                }
                log::warn!("probe report rejected by server: {}", text);
            }
            Err(e) => log::warn!("probe report attempt {} failed: {}", attempt + 1, e),
        }
    }
    log::error!("probe report dropped after retries: {}", &body[..body.len().min(120)]);
}

// ---------- 小工具 ----------

fn ms(d: Duration) -> f64 {
    (d.as_secs_f64() * 10000.0).round() / 10.0
}

/// 未执行 DNS 阶段（目标非法/类型未知）时的占位结构，保持 dns 段始终为对象。
fn skipped_dns(reason: &str) -> Value {
    json!({ "resolved_ips": [], "elapsed_ms": 0, "error": reason })
}

fn extra_u64(task: &ProbeTask, key: &str, default: u64) -> u64 {
    task.extra
        .get(key)
        .and_then(|v| v.as_u64())
        .unwrap_or(default)
}

fn clamp_usize(v: u64, lo: usize, hi: usize) -> usize {
    (v as usize).clamp(lo, hi)
}

fn tail(s: &str, n: usize) -> String {
    if s.len() <= n {
        return s.to_owned();
    }
    let mut start = s.len() - n;
    while !s.is_char_boundary(start) {
        start -= 1;
    }
    s[start..].to_owned()
}

// ---------- 单测（全部离线，无网络/子进程依赖） ----------

#[cfg(test)]
mod tests {
    use super::*;

    const LINUX_OK: &str = "\
PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
64 bytes from 8.8.8.8: icmp_seq=1 ttl=57 time=12.1 ms
64 bytes from 8.8.8.8: icmp_seq=2 ttl=57 time=11.8 ms
64 bytes from 8.8.8.8: icmp_seq=3 ttl=57 time=13.4 ms

--- 8.8.8.8 ping statistics ---
3 packets transmitted, 3 received, 0% packet loss, time 2003ms
rtt min/avg/max/mdev = 11.800/12.433/13.400/0.702 ms";

    const LINUX_FULL_LOSS: &str = "\
PING 10.255.255.1 (10.255.255.1) 56(84) bytes of data.

--- 10.255.255.1 ping statistics ---
3 packets transmitted, 0 received, 100% packet loss, time 2030ms";

    const MACOS_OK: &str = "\
PING 8.8.8.8 (8.8.8.8): 56 data bytes
64 bytes from 8.8.8.8: icmp_seq=0 ttl=57 time=12.044 ms
64 bytes from 8.8.8.8: icmp_seq=1 ttl=57 time=13.102 ms
64 bytes from 8.8.8.8: icmp_seq=2 ttl=57 time=11.766 ms

--- 8.8.8.8 ping statistics ---
3 packets transmitted, 3 packets received, 0.0% packet loss
round-trip min/avg/max/stddev = 11.766/12.304/13.102/0.581 ms";

    const WIN_EN_OK: &str = "\
Pinging 8.8.8.8 with 32 bytes of data:
Reply from 8.8.8.8: bytes=32 time=12ms TTL=57
Reply from 8.8.8.8: bytes=32 time=11ms TTL=57
Reply from 8.8.8.8: bytes=32 time=13ms TTL=57

Ping statistics for 8.8.8.8:
    Packets: Sent = 3, Received = 3, Lost = 0 (0% loss),
Approximate round trip times in milli-seconds:
    Minimum = 11ms, Maximum = 13ms, Average = 12ms";

    const WIN_ZH_OK: &str = "\
正在 Ping 8.8.8.8 具有 32 字节的数据:
来自 8.8.8.8 的回复: 字节=32 时间=12ms TTL=57
来自 8.8.8.8 的回复: 字节=32 时间=11ms TTL=57
来自 8.8.8.8 的回复: 字节=32 时间=13ms TTL=57

Ping 统计信息:
    数据包: 已发送 = 3，已接收 = 3，已丢失 = 0 (0% 丢失)，
往返行程的估计时间(以毫秒为单位):
    最短 = 11ms，最长 = 13ms，平均 = 12ms";

    const WIN_EN_FULL_LOSS: &str = "\
Pinging 10.255.255.1 with 32 bytes of data:
Request timed out.
Request timed out.
Request timed out.

Ping statistics for 10.255.255.1:
    Packets: Sent = 3, Received = 0, Lost = 3 (100% loss),";

    #[test]
    fn test_parse_ping_unix_ok() {
        let s = parse_ping_unix(LINUX_OK);
        assert_eq!(
            s,
            PingStats {
                sent: 3,
                received: 3,
                loss_pct: 0.0,
                rtt_min: 11.8,
                rtt_avg: 12.433,
                rtt_max: 13.4,
                rtt_mdev: 0.702,
            }
        );
    }

    #[test]
    fn test_parse_ping_unix_full_loss() {
        let s = parse_ping_unix(LINUX_FULL_LOSS);
        assert_eq!(s.sent, 3);
        assert_eq!(s.received, 0);
        assert_eq!(s.loss_pct, 100.0);
        // 无 RTT 行时保持零值
        assert_eq!(s.rtt_min, 0.0);
        assert_eq!(s.rtt_mdev, 0.0);
    }

    #[test]
    fn test_parse_ping_macos() {
        let s = parse_ping_unix(MACOS_OK);
        assert_eq!(s.sent, 3);
        assert_eq!(s.received, 3);
        assert_eq!(s.loss_pct, 0.0);
        assert_eq!(s.rtt_min, 11.766);
        assert_eq!(s.rtt_avg, 12.304);
        assert_eq!(s.rtt_max, 13.102);
        assert_eq!(s.rtt_mdev, 0.581);
    }

    #[test]
    fn test_parse_ping_windows_en() {
        let s = parse_ping_windows(WIN_EN_OK);
        assert_eq!(s.sent, 3);
        assert_eq!(s.received, 3);
        assert_eq!(s.loss_pct, 0.0);
        assert_eq!(s.rtt_min, 11.0);
        assert_eq!(s.rtt_max, 13.0);
        assert_eq!(s.rtt_avg, 12.0);
        assert_eq!(s.rtt_mdev, 0.0);
    }

    #[test]
    fn test_parse_ping_windows_zh() {
        // 位置解析不依赖中文标签，GBK 乱码场景同样成立
        let s = parse_ping_windows(WIN_ZH_OK);
        assert_eq!(s.sent, 3);
        assert_eq!(s.received, 3);
        assert_eq!(s.loss_pct, 0.0);
        assert_eq!(s.rtt_min, 11.0);
        assert_eq!(s.rtt_max, 13.0);
        assert_eq!(s.rtt_avg, 12.0);
    }

    #[test]
    fn test_parse_ping_windows_full_loss() {
        let s = parse_ping_windows(WIN_EN_FULL_LOSS);
        assert_eq!(s.sent, 3);
        assert_eq!(s.received, 0);
        assert_eq!(s.loss_pct, 100.0);
        assert_eq!(s.rtt_avg, 0.0);
    }

    #[test]
    fn test_parse_ping_garbage() {
        let s = parse_ping_output(PingFlavor::Unix, "something went wrong\nno stats here");
        assert_eq!(s, PingStats::default());
        let s = parse_ping_windows("完全无法识别的输出");
        assert_eq!(s, PingStats::default());
    }

    #[test]
    fn test_is_safe_host() {
        assert!(is_safe_host("api.example.com"));
        assert!(is_safe_host("8.8.8.8"));
        assert!(is_safe_host("2001:db8::1"));
        assert!(is_safe_host("my-host-01"));
        assert!(!is_safe_host(""));
        assert!(!is_safe_host("example.com; rm -rf /"));
        assert!(!is_safe_host("example.com|cat"));
        assert!(!is_safe_host("example.com`whoami`"));
        assert!(!is_safe_host(&"a".repeat(256)));
    }

    #[test]
    fn test_classify_io() {
        assert_eq!(
            classify_io(&std::io::Error::new(ErrorKind::ConnectionRefused, "x")),
            "connection_refused"
        );
        assert_eq!(
            classify_io(&std::io::Error::new(ErrorKind::NetworkUnreachable, "x")),
            "network_unreachable"
        );
        assert_eq!(
            classify_io(&std::io::Error::new(ErrorKind::TimedOut, "x")),
            "timeout"
        );
        assert_eq!(
            classify_io(&std::io::Error::new(ErrorKind::PermissionDenied, "x")),
            "other"
        );
    }

    #[test]
    fn test_eq_and_pct_helpers() {
        assert_eq!(
            eq_values("Sent = 3, Received = 2, Lost = 1"),
            vec![3.0, 2.0, 1.0]
        );
        assert_eq!(pct_in_parens("Lost = 3 (100% loss)"), Some(100.0));
        assert_eq!(pct_in_parens("no parens here"), None);
        assert_eq!(tail("abcdef", 3), "def");
        assert_eq!(tail("ab", 10), "ab");
    }
}
