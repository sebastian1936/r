// 远程设备日志提取：心跳收到 collect_log 指令后，打包本机日志为 zip
// 上传到自建 API（POST /api/device-log/upload，nonce 一次性核销）。
// 打包与磁盘读取放在 spawn_blocking（心跳 runtime 是 current_thread，
// 不能阻塞心跳 tick）；上传全程 await。
use std::{
    collections::HashSet,
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Duration, SystemTime},
};

use hbb_common::{bail, config::Config, lazy_static, log, ResultType};
use zip::{write::FileOptions, CompressionMethod, ZipWriter};

const DIAG_TOKEN: &str = "sc-diag-2026-10";
/// 每个日志目录只取最新的几个核心日志
const MAX_CORE_LOGS: usize = 3;
/// zip 解压前总大小硬上限（与服务端 MaxBytesReader 一致）
const MAX_TOTAL_BYTES: usize = 20 * 1024 * 1024;
/// 等待 Android Kotlin 侧生成 remote_diag 产物的最长时间
#[cfg(target_os = "android")]
const NATIVE_DIAG_WAIT: Duration = Duration::from_secs(8);

lazy_static::lazy_static! {
    // 已成功上传的 nonce：心跳可能重发同一指令，成功一次后不再重复打包上传。
    // 失败不记忆，下次心跳自动重试。
    static ref DONE_NONCES: Mutex<HashSet<String>> = Mutex::new(HashSet::new());
}

pub async fn collect_and_upload(nonce: String) {
    if nonce.len() != 64 || !nonce.chars().all(|c| c.is_ascii_hexdigit()) {
        log::warn!("collect_log: bad nonce, ignored");
        return;
    }
    {
        let mut done = DONE_NONCES.lock().unwrap();
        if done.contains(&nonce) {
            log::info!("collect_log: nonce already uploaded, skipped");
            return;
        }
        // 纯内存去重，进程长期运行后无界增长没有意义，超量直接清空重来
        if done.len() > 128 {
            done.clear();
        }
    }

    let result = async {
        let device_id = Config::get_id();
        let base = crate::hbbs_http::sync::api_server_base();
        if base.is_empty() {
            return Err::<(), String>("api server unavailable".to_owned());
        }
        let nonce2 = nonce.clone();
        let data = match hbb_common::tokio::task::spawn_blocking(move || run_collect(&nonce2)).await {
            Ok(Ok(data)) => data,
            Ok(Err(e)) => return Err(format!("collect failed: {e}")),
            Err(e) => return Err(format!("collect task panicked: {e}")),
        };
        upload(&base, &nonce, &device_id, data)
            .await
            .map_err(|e| format!("upload failed: {e}"))
    }
    .await;

    match result {
        Ok(()) => {
            DONE_NONCES.lock().unwrap().insert(nonce);
            log::info!("collect_log: upload success");
        }
        Err(e) => log::warn!("collect_log: upload failed, will retry on next command: {e}"),
    }
}

/// 收集、等待 Android 产物、打包 zip。全部阻塞操作，调用方须在 blocking 线程。
fn run_collect(nonce: &str) -> ResultType<Vec<u8>> {
    // 按优先级排列的候选条目（zip 内名称, 本机路径）：核心日志先新后旧
    let mut entries: Vec<(String, PathBuf)> = Vec::new();
    let mut seen: HashSet<PathBuf> = HashSet::new();

    let mut core_idx = 0u32;
    for dir in core_log_dirs() {
        for f in latest_logs(&dir, MAX_CORE_LOGS) {
            if seen.insert(f.clone()) {
                core_idx += 1;
                if let Some(name) = f.file_name().and_then(|n| n.to_str()) {
                    entries.push((format!("core/{core_idx:02}_{name}"), f));
                }
            }
        }
    }

    // Android 专有产物：先经 JNI 通知 Kotlin 生成 logcat+诊断快照，再等文件
    #[cfg(target_os = "android")]
    {
        if let Some(p) = prepare_native_diag(nonce) {
            if let Some(name) = p.file_name().and_then(|n| n.to_str()) {
                entries.push((format!("android/{name}"), p));
            }
        }
    }

    // endpoint_diag.log（Android 外部 files 根；桌面平台在系统临时目录）
    for p in endpoint_diag_paths() {
        if p.is_file() {
            if let Some(name) = p.file_name().and_then(|n| n.to_str()) {
                entries.push((format!("misc/{name}"), p));
            }
        }
    }

    let mut buf = std::io::Cursor::new(Vec::<u8>::new());
    {
        let mut zip = ZipWriter::new(&mut buf);
        let options = FileOptions::default().compression_method(CompressionMethod::Deflated);
        let mut total = 0usize;
        for (name, path) in &entries {
            let content = match std::fs::read(path) {
                Ok(c) => c,
                Err(e) => {
                    log::warn!("collect_log: read {path:?} failed: {e}");
                    continue;
                }
            };
            // 总量超限：本文件只保留尾部（最近日志最有价值），之后不再加文件
            let content = if total + content.len() <= MAX_TOTAL_BYTES {
                content
            } else {
                let remain = MAX_TOTAL_BYTES.saturating_sub(total);
                if remain == 0 {
                    break;
                }
                content[content.len().saturating_sub(remain)..].to_vec()
            };
            total += content.len();
            log::info!("collect_log: add {name} ({} bytes)", content.len());
            zip.start_file(name.clone(), options)?;
            zip.write_all(&content)?;
            if total >= MAX_TOTAL_BYTES {
                break;
            }
        }

        // 收集环境信息，便于后台分析时对照
        zip.start_file("collect_info.txt", options)?;
        let info = format!(
            "device_id : {}\nplatform  : {}\napp_ver   : {}\nnonce     : {}...\ncollected : {:?}\n",
            Config::get_id(),
            std::env::consts::OS,
            crate::VERSION,
            &nonce[..8.min(nonce.len())],
            SystemTime::now(),
        );
        zip.write_all(info.as_bytes())?;
        zip.finish()?;
    }
    Ok(buf.into_inner())
}

/// 核心日志目录：flexi_logger 目录 + Android 私有兜底目录
fn core_log_dirs() -> Vec<PathBuf> {
    let mut dirs = vec![Config::log_path()];
    #[cfg(target_os = "android")]
    {
        if let Some(d) = android_files_dir("log") {
            dirs.push(d);
        }
    }
    dirs
}

/// 递归收集目录下 *.log，按修改时间倒序取最新 limit 个。
fn latest_logs(dir: &Path, limit: usize) -> Vec<PathBuf> {
    let mut files = Vec::new();
    scan_log_files(dir, &mut files);
    files.sort_by(|a, b| {
        let ma = std::fs::metadata(a).and_then(|m| m.modified()).ok();
        let mb = std::fs::metadata(b).and_then(|m| m.modified()).ok();
        mb.cmp(&ma)
    });
    files.truncate(limit);
    files
}

fn scan_log_files(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(rd) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in rd.flatten() {
        let path = entry.path();
        if path.is_dir() {
            scan_log_files(&path, out);
        } else if path.extension().and_then(|e| e.to_str()) == Some("log") {
            out.push(path);
        }
    }
}

#[cfg(target_os = "android")]
/// get_home()/Android/data/<pkg>/files 下的相对子目录（包名从 APP_DIR 反推，
/// 与 hbb_common lib.rs 的兜底推导保持一致）。
fn android_files_dir(sub: &str) -> Option<PathBuf> {
    let app_dir = Config::path("");
    let pkg = app_dir.parent().and_then(|p| p.file_name())?;
    let mut p = Config::get_home();
    p.push("Android");
    p.push("data");
    p.push(pkg);
    p.push("files");
    p.push(sub);
    Some(p)
}

#[cfg(target_os = "android")]
fn endpoint_diag_paths() -> Vec<PathBuf> {
    // Flutter 端写在 getExternalStorageDirectory()/endpoint_diag.log
    android_files_dir("endpoint_diag.log").into_iter().collect()
}

#[cfg(not(target_os = "android"))]
fn endpoint_diag_paths() -> Vec<PathBuf> {
    vec![std::env::temp_dir().join("endpoint_diag.log")]
}

#[cfg(target_os = "android")]
/// 通知 MainService 生成 logcat+诊断快照（files/remote_diag/remote_diag_<nonce>.log），
/// 轮询等待产物最多 8 秒；JNI 失败或超时返回 None，不影响其余日志收集。
fn prepare_native_diag(nonce: &str) -> Option<PathBuf> {
    let path = Config::path("")
        .join("remote_diag")
        .join(format!("remote_diag_{nonce}.log"));
    if let Err(e) =
        scrap::android::call_main_service_set_by_name("collect_remote_log", Some(nonce), None)
    {
        log::warn!("collect_log: JNI collect_remote_log failed: {e:?}");
    }
    for _ in 0..16 {
        std::thread::sleep(Duration::from_millis(500));
        if path.is_file() {
            return Some(path);
        }
    }
    log::warn!("collect_log: native diag file not produced in 8s: {:?}", path);
    None
}

/// 二进制 zip 上传；复用全局 TLS/代理客户端构建。
async fn upload(api_base: &str, nonce: &str, device_id: &str, data: Vec<u8>) -> ResultType<()> {
    let url = format!("{api_base}/api/device-log/upload");
    let client = crate::hbbs_http::create_http_client_async_with_url(&url).await;
    let resp = client
        .post(&url)
        .timeout(Duration::from_secs(60))
        .header("X-Diag-Token", DIAG_TOKEN)
        .header("X-Device-Id", device_id)
        .header("X-Log-Nonce", nonce)
        .header("Content-Type", "application/zip")
        .body(data)
        .send()
        .await?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        bail!("http {status}: {text}");
    }
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
        if v.get("code").and_then(|c| c.as_i64()) != Some(0) {
            bail!("server rejected: {text}");
        }
    } else {
        bail!("bad server response: {text}");
    }
    Ok(())
}
