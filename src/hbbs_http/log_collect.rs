// 设备日志手动上传：客户在 App"设置-关于"一键打包本机日志为 zip 并上传到自建 API
// （POST /api/device-log/manual-upload，nonce 客户端自生成，仅用于命名与幂等）。
// 打包与磁盘读取放在 spawn_blocking（调用方 runtime 不得被阻塞）；上传全程 await。
use std::{
    collections::HashSet,
    io::Write,
    path::{Path, PathBuf},
    time::{Duration, SystemTime},
};

use hbb_common::{bail, config::Config, log, ResultType};
use zip::{write::FileOptions, CompressionMethod, ZipWriter};

const DIAG_TOKEN: &str = "sc-diag-2026-10";
/// zip 解压前总大小硬上限（与服务端 MaxBytesReader 一致）
const MAX_TOTAL_BYTES: usize = 20 * 1024 * 1024;
/// 等待 Android Kotlin 侧生成 remote_diag 产物的最长时间
#[cfg(target_os = "android")]
const NATIVE_DIAG_WAIT: Duration = Duration::from_secs(8);

/// 客户在 App 内手动一键上传的接口（nonce 客户端自生成，不核销指令）
const UPLOAD_PATH_MANUAL: &str = "/api/device-log/manual-upload";

/// 客户在 App"设置-关于"手动一键上传：nonce 客户端随机生成，不经后台指令核销，
/// 打包保留期内全部日志。成功/失败由调用方（FFI）感知并推给 UI。
pub async fn manual_collect_and_upload() -> ResultType<()> {
    let device_id = Config::get_id();
    let base = crate::hbbs_http::sync::api_server_base();
    if base.is_empty() {
        bail!("api server unavailable");
    }
    let nonce = random_nonce_hex();
    let nonce2 = nonce.clone();
    let data = hbb_common::tokio::task::spawn_blocking(move || run_collect(&nonce2))
        .await??;
    upload(&base, UPLOAD_PATH_MANUAL, &nonce, &device_id, data).await?;
    log::info!("manual device-log upload success");
    Ok(())
}

/// FFI 手动按钮专用：自建 current-thread runtime 阻塞执行，调用方须在独立线程，
/// 不得占用心跳 runtime 与 UI 线程。
pub fn run_manual_upload_blocking() -> ResultType<()> {
    let rt = hbb_common::tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    rt.block_on(manual_collect_and_upload())
}

/// 32 字节随机数的 hex（64 字符），满足服务端 nonce 格式校验，仅用于命名与幂等。
fn random_nonce_hex() -> String {
    use rand::RngCore;
    let mut buf = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut buf);
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

/// 收集、等待 Android 产物、打包 zip。全部阻塞操作，调用方须在 blocking 线程。
/// 打包日志目录下全部文件（保留期已由 flexi_logger 限制为约 3 天，受总大小上限保护），
/// 并在 collect_info.txt 标记 trigger=manual。
fn run_collect(nonce: &str) -> ResultType<Vec<u8>> {
    // 按优先级排列的候选条目（zip 内名称, 本机路径）：核心日志先新后旧
    let mut entries: Vec<(String, PathBuf)> = Vec::new();
    let mut seen: HashSet<PathBuf> = HashSet::new();

    let mut core_idx = 0u32;
    for dir in core_log_dirs() {
        for f in latest_logs(&dir, usize::MAX) {
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
            "device_id : {}\nplatform  : {}\napp_ver   : {}\ntrigger   : manual\nnonce     : {}...\ncollected : {:?}\n",
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

/// 核心日志目录：flexi_logger 目录 + Android 私有兜底目录 + Windows 系统服务账户目录
fn core_log_dirs() -> Vec<PathBuf> {
    let mut dirs = vec![Config::log_path()];
    #[cfg(target_os = "android")]
    {
        if let Some(d) = android_files_dir("log") {
            dirs.push(d);
        }
    }
    #[cfg(windows)]
    {
        // SYSTEM 下 Config::log_path() 本身就是 systemprofile 目录（SystemRoot
        // 大小写/8.3 形态可能不同），Windows 文件系统不区分大小写，按小写路径
        // 去重，避免同一目录重复扫描、同一日志在 zip 内打包两份。
        let mut seen: Vec<String> = dirs
            .iter()
            .filter_map(|p| p.to_str().map(|s| s.to_lowercase()))
            .collect();
        for d in windows_system_profile_log_dirs() {
            let key = d.to_str().map(|s| s.to_lowercase());
            match key {
                Some(k) if seen.contains(&k) => {}
                Some(k) => {
                    seen.push(k);
                    dirs.push(d);
                }
                // 非 UTF-8 路径（极罕见）无法比较，保守保留
                None => dirs.push(d),
            }
        }
    }
    dirs
}

/// Windows 安装版核心服务以 LocalSystem（session 0）运行，flexi_logger 日志
/// 不写在登录用户目录，而写在 systemprofile 下；32 位进程（含 32 位安装包跑在
/// 64 位系统上）走 SysWOW64 变体。目录名必须与 hbb_common 的 Config 路径推导
/// 保持一致（APP_NAME 现值），禁止硬编码具体产品名。
/// 手动上传由 SYSTEM 服务执行（经 IPC），这些目录可读；用户态直接调用时
/// 会被 ACL 拒绝，由 scan_log_files/read 阶段静默跳过。
#[cfg(windows)]
fn windows_system_profile_log_dirs() -> Vec<PathBuf> {
    let system_root = std::env::var_os("SystemRoot")
        .filter(|s| !s.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(r"C:\Windows"));
    let app_name = hbb_common::config::APP_NAME.read().unwrap().clone();
    let mut out = Vec::with_capacity(2);
    for base in ["System32", "SysWOW64"] {
        let mut p = system_root.join(base);
        p.push(r"config\systemprofile\AppData\Roaming");
        // APP_NAME 可能含层级分隔符（如 "Purslane Ltd\RustDesk"），
        // Windows 下 PathBuf::push 会按 '\' 正确拆层。
        p.push(&app_name);
        p.push("log");
        out.push(p);
    }
    out
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
/// path 为目标接口（UPLOAD_PATH_MANUAL）。
async fn upload(
    api_base: &str,
    path: &str,
    nonce: &str,
    device_id: &str,
    data: Vec<u8>,
) -> ResultType<()> {
    let url = format!("{api_base}{path}");
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
