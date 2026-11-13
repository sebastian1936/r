// 在线网络质量探测 Agent 模块（设计文档 §5/§6）。
//
// 心跳拉到 probe_tasks 后入队（容量 32），独立工作线程内 current-thread 运行时
// 并发执行，信号量限流 PROBE_CONCURRENCY=10：满员后 supervisor 停 drain 形成背压，
// 队列满则入队端丢弃并告警，由服务端 TTL 兜底。三种探测全部单次化、失败不重试：
//   - ping：仅 Windows，进程内 IcmpSendEcho（iphlpapi，普通用户权限，无子进程无窗口），
//     阻塞调用放 spawn_blocking；非 Windows 平台返回 unsupported
//   - tcp：tokio TcpStream，单次连接 2s 超时，失败即返回
//   - http：专用 reqwest client，硬超时 5s，不重试、不下载 body、最多 3 跳转
// 各阶段超时：DNS 2s / Ping 兜底 5s / TCP 连接 2s / HTTP 整体 5s。
// 结果经 post_request 单次上报 /api/probe/report，带 X-Probe-Token，失败丢弃由服务端 TTL 收口。

use std::{
    collections::{HashMap, HashSet},
    io::ErrorKind,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use hbb_common::{
    config::Config,
    log,
    tokio::{
        self,
        net::TcpStream,
        sync::{mpsc, Semaphore},
    },
};
use serde::Deserialize;
use serde_json::{json, Map, Value};

const PROBE_TOKEN: &str = "sc-probe-2026-10";
// 队列容量必须大于并发度，给服务端心跳批量下发留 burst 缓冲；
// 队列满后 spawn() 非阻塞丢弃并告警，由服务端 TTL 兜底。
const QUEUE_CAP: usize = 32;
// 单 Agent 探测并发上限：服务端保留派发策略大脑（发几个、何时发可随版本调整），
// 客户端把执行能力一次做足——客户端无法强制用户升级，并发模型不留给以后改。
const PROBE_CONCURRENCY: usize = 10;
const DONE_CACHE_MAX: usize = 64; // 已执行任务结果缓存上限，超过整体清空

// 5s 封顶折中方案：单槽目标"领任务 ≤3s + 执行 ≤5s（DNS 另计 2s）"，
// 慢目标/弱网以 timeout 如实上报，不再靠拉长等待换成功率。
const HARD_TIMEOUT: Duration = Duration::from_secs(5);
const DNS_TIMEOUT: Duration = Duration::from_secs(2);
const PING_TIMEOUT: Duration = Duration::from_secs(5); // 单包探测的命令挂死兜底
const TCP_CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const TCP_GAP: Duration = Duration::from_millis(500);
const MAX_REDIRECTS_DEFAULT: u64 = 3;

const STATUS_DONE: &str = "done";
const STATUS_TIMEOUT: &str = "timeout";
const STATUS_FAILED: &str = "failed";

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
    // 必须是 tokio 异步通道：worker 跑在独立 current-thread 运行时上，队列空时
    // worker_loop 挂在 rx.recv().await，运行时仍能驱动已 spawn 的探测任务。
    // 若用 std 阻塞通道，recv 会让线程睡在运行时调度器之外，spawn 出去的任务
    // 只在新消息到达时才有机会被 poll；单台设备派完任务后通道静默，会导致
    // 探测永不执行、上报永不发出（服务端只能等 TTL 收口为 expired）。
    static ref QUEUE: Mutex<mpsc::Sender<ProbeTask>> = Mutex::new(start_worker());
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

fn start_worker() -> mpsc::Sender<ProbeTask> {
    let (tx, rx) = mpsc::channel::<ProbeTask>(QUEUE_CAP);
    if let Err(e) = std::thread::Builder::new()
        .name("probe-worker".to_owned())
        .spawn(move || {
            // 独立 current-thread 运行时：探测全部异步化，与心跳线程互不影响。
            // spawn 出的探测任务在 await 点交错并发（IO/子进程型任务无线程阻塞）。
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

async fn worker_loop(mut rx: mpsc::Receiver<ProbeTask>) {
    // 并发闸：最多 PROBE_CONCURRENCY 个探测在途（执行+上报合计）。许可在派发前
    // 获取，满员后 supervisor 停在 acquire 不再 drain channel，channel 填满后
    // 入队端 try_send 丢弃，形成"队列 + 信号量"两级背压，内存里不会无限堆任务。
    let sem = Arc::new(Semaphore::new(PROBE_CONCURRENCY));
    // task_id -> 上次完整上报 body：重复任务直接重发结果，不重复探测。
    // 并发任务共享，用 std Mutex 保护，持锁只做 map 短操作、不跨 await。
    let done = Arc::new(Mutex::new(HashMap::<String, String>::new()));

    // recv().await 在队列空时把线程交还给 tokio park，已 spawn 的探测任务
    // 照常被驱动；所有 Sender 关闭时 recv 返回 None，循环退出、线程自然结束。
    while let Some(task) = rx.recv().await {
        // 重复任务：仅重发缓存结果，不占探测并发额度
        if let Some(cached) = done.lock().unwrap().get(&task.task_id).cloned() {
            log::info!("probe duplicate task {}, re-report cached result", task.task_id);
            tokio::spawn(async move {
                report_once(&cached).await;
            });
            continue;
        }
        // 满员时在此等待形成背压；信号量全程不 close，acquire_owned 不会出错
        let permit = sem
            .clone()
            .acquire_owned()
            .await
            .expect("probe semaphore closed");
        let done = done.clone();
        tokio::spawn(async move {
            let body = run_task(&task).await;
            {
                let mut cache = done.lock().unwrap();
                if cache.len() >= DONE_CACHE_MAX {
                    cache.clear();
                }
                cache.insert(task.task_id.clone(), body.clone());
            }
            report_once(&body).await;
            // 上报完成才释放许可：与服务端 inflight（已派发未上报）口径一致
            drop(permit);
        });
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
    json!({
        "probe_type": task.probe_type,
        "target": task.target,
        "agent": {
            "platform": std::env::consts::OS,
            "version": crate::VERSION,
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

#[cfg(windows)]
async fn probe_ping(task: &ProbeTask) -> (String, String, Value) {
    let host = task.target.trim();
    if !is_safe_host(host) {
        return (
            STATUS_FAILED.to_owned(),
            "invalid ping target".to_owned(),
            base_result(task, skipped_dns("invalid_target")),
        );
    }
    // 单次化：服务端 defaultExtra 下发 count=1；默认值同步为 1 防御老服务端。
    let count = clamp_usize(extra_u64(task, "count", 1), 1, 10);
    let (ips, dns) = resolve_host_ips(host).await;
    let v4 = match ips.iter().find_map(|ip| match ip {
        std::net::IpAddr::V4(v4) => Some(*v4),
        _ => None,
    }) {
        Some(v4) => v4,
        None => {
            // 仅 IPv6（Icmp6 链路暂未实现）或解析失败：明确失败原因，不做静默兜底
            let reason = if ips.is_empty() {
                "dns resolve failed or timed out"
            } else {
                "ipv6 icmp not supported"
            };
            return (STATUS_FAILED.to_owned(), reason.to_owned(), base_result(task, dns));
        }
    };

    // IcmpSendEcho 是同步阻塞 API，放 spawn_blocking 防止冻结 current-thread 运行时
    let started = Instant::now();
    let job = tokio::task::spawn_blocking(move || icmp_win::echo_v4(v4, count));
    let outcome = match tokio::time::timeout(PING_TIMEOUT, job).await {
        Ok(Ok(o)) => o,
        Ok(Err(e)) => {
            return (
                STATUS_FAILED.to_owned(),
                format!("icmp task join failed: {}", e),
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

    let sent = outcome.sent;
    let received = outcome.received;
    let loss_pct = if sent > 0 {
        ((sent - received) as f64 / sent as f64) * 100.0
    } else {
        100.0
    };
    let (rtt_min, rtt_avg, rtt_max) = ping_rtt_stats(&outcome.rtts);
    // 字段口径与旧 ping.exe 版本完全一致，前端/地图零改动
    let ping = json!({
        "sent": sent,
        "received": received,
        "loss_pct": loss_pct,
        "rtt_min_ms": rtt_min,
        "rtt_avg_ms": rtt_avg,
        "rtt_max_ms": rtt_max,
        // IcmpSendEcho 无 mdev 口径，固定 0（旧 Windows 文本解析同样为 0）
        "rtt_mdev_ms": 0.0,
        // 无子进程概念：探测执行成功固定 0，失败原因走 error_msg/raw_tail
        "exit_code": 0,
        "elapsed_ms": ms(started.elapsed()),
        "raw_tail": outcome.summary,
    });
    let mut result = base_result(task, dns);
    result["ping"] = ping;

    if received == 0 {
        // 全丢/超时按 timeout 上报，地图染红；部分丢包仍为 done，由丢包率叠红闪电
        (
            STATUS_TIMEOUT.to_owned(),
            format!("icmp echo unreachable: {}", outcome.summary),
            result,
        )
    } else {
        (STATUS_DONE.to_owned(), String::new(), result)
    }
}

#[cfg(not(windows))]
async fn probe_ping(task: &ProbeTask) -> (String, String, Value) {
    // 探测白名单已收窄为仅 Windows，非 Windows 客户端理论上收不到 ping 任务；
    // 保留防御性返回，避免老服务端误派时 panic 或静默丢弃。
    (
        STATUS_FAILED.to_owned(),
        "ping probe only supported on windows".to_owned(),
        base_result(task, skipped_dns("ping unsupported on this platform")),
    )
}

/// 解析主机名并返回去重 IP 列表，同时产出与 resolve_dns 同口径的 dns JSON。
#[cfg(windows)]
async fn resolve_host_ips(host: &str) -> (Vec<std::net::IpAddr>, Value) {
    let started = Instant::now();
    match tokio::time::timeout(DNS_TIMEOUT, tokio::net::lookup_host((host, 0u16))).await {
        Ok(Ok(addrs)) => {
            let mut seen = HashSet::new();
            let ips: Vec<std::net::IpAddr> =
                addrs.map(|sa| sa.ip()).filter(|ip| seen.insert(*ip)).collect();
            let dns = json!({
                "resolved_ips": ips.iter().map(|ip| ip.to_string()).collect::<Vec<_>>(),
                "elapsed_ms": ms(started.elapsed()),
                "error": "",
            });
            (ips, dns)
        }
        Ok(Err(e)) => (
            Vec::new(),
            json!({
                "resolved_ips": [],
                "elapsed_ms": ms(started.elapsed()),
                "error": format!("dns_lookup_failed: {}", e),
            }),
        ),
        Err(_) => (
            Vec::new(),
            json!({
                "resolved_ips": [],
                "elapsed_ms": ms(DNS_TIMEOUT),
                "error": "dns_timeout",
            }),
        ),
    }
}

/// 由成功回包 RTT 序列（毫秒）计算 min/avg/max，空序列返回全 0；avg 保留 1 位小数。
#[cfg(windows)]
fn ping_rtt_stats(rtts: &[u32]) -> (f64, f64, f64) {
    if rtts.is_empty() {
        return (0.0, 0.0, 0.0);
    }
    let mut min = u32::MAX;
    let mut max = 0u32;
    let mut sum = 0u64;
    for &rtt in rtts {
        min = min.min(rtt);
        max = max.max(rtt);
        sum += rtt as u64;
    }
    let avg = sum as f64 / rtts.len() as f64;
    (min as f64, (avg * 10.0).round() / 10.0, max as f64)
}

/// Windows 进程内 ICMP 实现（iphlpapi）。
/// 普通用户权限即可调用 IcmpSendEcho（无需 raw socket/管理员），
/// 不创建任何子进程，彻底消除 ping.exe 的窗口与进程创建开销。
#[cfg(windows)]
mod icmp_win {
    use windows::Win32::Foundation::{GetLastError, HANDLE};
    use windows::Win32::NetworkManagement::IpHelper::{
        IcmpCloseHandle, IcmpCreateFile, IcmpSendEcho, ICMP_ECHO_REPLY,
    };

    /// 单包 echo 超时，与旧 ping.exe `-w 2000` 口径一致（毫秒）
    const ECHO_TIMEOUT_MS: u32 = 2000;
    const REQUEST_DATA: &[u8] = b"rustdesk-probe-ping";
    /// 回包缓冲余量：ICMP_ECHO_REPLY 结构 + 请求数据 + IP 头余量
    const REPLY_MARGIN: usize = 16;

    /// IP_STATUS 常见值（ipifcons/ipexport 口径），用于 raw_tail 人类可读文案
    const IP_SUCCESS: u32 = 0;
    const IP_BUF_TOO_SMALL: u32 = 11001;
    const IP_DEST_NET_UNREACHABLE: u32 = 11002;
    const IP_DEST_HOST_UNREACHABLE: u32 = 11003;
    const IP_REQ_TIMED_OUT: u32 = 11010;
    const IP_GENERAL_FAILURE: u32 = 11050;

    pub(super) struct EchoOutcome {
        pub(super) sent: u32,
        pub(super) received: u32,
        pub(super) rtts: Vec<u32>,
        pub(super) summary: String,
    }

    fn status_text(code: u32) -> &'static str {
        match code {
            IP_BUF_TOO_SMALL => "buffer too small",
            IP_DEST_NET_UNREACHABLE => "destination network unreachable",
            IP_DEST_HOST_UNREACHABLE => "destination host unreachable",
            IP_REQ_TIMED_OUT => "request timed out",
            IP_GENERAL_FAILURE => "general failure",
            _ => "icmp error",
        }
    }

    /// 对 IPv4 目标背靠背连发 count 个 ICMP Echo（无系统 ping 的 1s 间隔）。
    /// 全程同步阻塞，调用方必须放在 spawn_blocking 线程执行。
    pub(super) fn echo_v4(ip: std::net::Ipv4Addr, count: usize) -> EchoOutcome {
        let mut outcome = EchoOutcome {
            sent: 0,
            received: 0,
            rtts: Vec::with_capacity(count),
            summary: String::new(),
        };
        // SAFETY: FFI 调用遵循 Icmp* 契约——句柄由 IcmpCreateFile 取得并在本函数
        // 内关闭；回包缓冲生命周期覆盖 IcmpSendEcho 全程，裸指针仅在调用期间有效。
        unsafe {
            let handle = match IcmpCreateFile() {
                Ok(h) => h,
                Err(e) => {
                    outcome.summary = format!("IcmpCreateFile failed: {}", e);
                    return outcome;
                }
            };
            echo_loop(handle, ip, count, &mut outcome);
            let _ = IcmpCloseHandle(handle);
        }
        outcome
    }

    /// # Safety
    /// `handle` 必须是 IcmpCreateFile 返回且尚未关闭的有效句柄。
    unsafe fn echo_loop(handle: HANDLE, ip: std::net::Ipv4Addr, count: usize, outcome: &mut EchoOutcome) {
        let buf_len = std::mem::size_of::<ICMP_ECHO_REPLY>() + REQUEST_DATA.len() + REPLY_MARGIN;
        let mut reply_buf = vec![0u8; buf_len];
        // IcmpSendEcho 目标地址要求网络字节序 u32（同 inet_addr 返回值）
        let destination = u32::from_le_bytes(ip.octets());
        let mut last_code: u32 = IP_SUCCESS;
        let mut last_ttl: u32 = 0;

        for _ in 0..count {
            outcome.sent += 1;
            // windows 0.61 的 IcmpSendEcho 为混合签名（CI 实测）：
            // requestdata/replybuffer 是裸指针需显式 cast，
            // requestoptions 是 Option<*const IP_OPTION_INFORMATION>，无自定义 IP 选项传 None
            let replies = IcmpSendEcho(
                handle,
                destination,
                REQUEST_DATA.as_ptr() as *const core::ffi::c_void,
                REQUEST_DATA.len() as u16,
                None,
                reply_buf.as_mut_ptr() as *mut core::ffi::c_void,
                reply_buf.len() as u32,
                ECHO_TIMEOUT_MS,
            );
            if replies == 0 {
                // 本机 API 调用失败（超时也走这里，GetLastError=IP_REQ_TIMED_OUT）
                last_code = GetLastError().0;
                outcome.summary = format!("{} (win32 code={})", status_text(last_code), last_code);
                continue;
            }
            let reply = &*(reply_buf.as_ptr() as *const ICMP_ECHO_REPLY);
            last_code = reply.Status;
            last_ttl = reply.Options.Ttl as u32;
            if reply.Status == IP_SUCCESS {
                outcome.received += 1;
                outcome.rtts.push(reply.RoundTripTime);
            } else {
                // 回包到达但目的端返回差错（不可达等）
                outcome.summary = format!("{} (status={})", status_text(reply.Status), reply.Status);
            }
        }

        if outcome.received > 0 {
            outcome.summary = format!(
                "icmp echo sent={} recv={} last_code={} ttl={}",
                outcome.sent, outcome.received, last_code, last_ttl
            );
        }
    }
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
    // 单次化：服务端 defaultExtra 下发 attempts=1；默认值同步为 1 防御老服务端。
    let attempts_n = clamp_usize(extra_u64(task, "attempts", 1), 1, 5) as u32;

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
        // 注：ErrorKind::NetworkUnreachable 等 io_error_more 变体在 Rust 1.83 才稳定，
        // CI 固定 1.75，不可用；网络不可达归入 other
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
                "error": "http hard timeout 5s",
            });
            let mut result = base_result(task, dns);
            result["http"] = http;
            (
                STATUS_TIMEOUT.to_owned(),
                "http hard timeout 5s".to_owned(),
                result,
            )
        }
    }
}

// ---------- 上报 ----------

/// 单次上报：失败立即丢弃，不做退避重试——探测已全面短超时化，重试只会放大
/// 任务尾延迟，未上报的槽位由服务端 TTL（90s）收口为 expired。
async fn report_once(body: &str) {
    let base = crate::hbbs_http::sync::api_server_base();
    if base.is_empty() {
        log::warn!("probe report skipped: api server base empty");
        return;
    }
    let url = format!("{}/api/probe/report", base);
    let header = format!("X-Probe-Token: {}", PROBE_TOKEN);
    match crate::post_request(url, body.to_owned(), &header).await {
        Ok(text) => {
            if serde_json::from_str::<Value>(&text)
                .ok()
                .and_then(|v| v.get("code").and_then(|c| c.as_i64()))
                != Some(0)
            {
                log::warn!("probe report rejected by server: {}", text);
            }
        }
        Err(e) => log::warn!("probe report failed (no retry, server TTL will close slot): {}", e),
    }
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

// ---------- 单测（全部离线，无网络/子进程依赖） ----------

#[cfg(test)]
mod tests {
    use super::*;

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
            classify_io(&std::io::Error::new(ErrorKind::TimedOut, "x")),
            "timeout"
        );
        assert_eq!(
            classify_io(&std::io::Error::new(ErrorKind::PermissionDenied, "x")),
            "other"
        );
    }

    #[cfg(windows)]
    #[test]
    fn test_ping_rtt_stats() {
        // 空序列全 0
        assert_eq!(ping_rtt_stats(&[]), (0.0, 0.0, 0.0));
        // 单包：min/avg/max 相同
        assert_eq!(ping_rtt_stats(&[12]), (12.0, 12.0, 12.0));
        // 多包：min/max 取极值，avg 保留 1 位小数（(11+12+13)/3=12.0）
        assert_eq!(ping_rtt_stats(&[12, 11, 13]), (11.0, 12.0, 13.0));
        // avg 四舍五入：10+11+12 = 33/3 = 11.0；10+12 = 11.0
        assert_eq!(ping_rtt_stats(&[10, 12]), (10.0, 11.0, 12.0));
        // 非整除场景：(10+11)/2 = 10.5
        assert_eq!(ping_rtt_stats(&[10, 11]), (10.0, 10.5, 11.0));
    }
}
