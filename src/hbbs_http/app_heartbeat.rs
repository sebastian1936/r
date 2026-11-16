// App 级心跳的 Rust 侧实现，与 Flutter 侧 AppHeartbeat（app_heartbeat.dart）
// 同协议、同职责，解决 Flutter 引擎不在场时的探测在线/派单问题。
//
// 进程拓扑（Windows 安装版）：
//   --service  session 0 / LocalSystem，服务主进程，负责 IPC 与会话看护
//     └─ --server  借 winlogon token 在活动会话拉起（prelogin/无人登录亦可，
//                  winlogon 每会话常驻），本模块即运行于此进程：
//                  RendezvousMediator::start_all → app_heartbeat::start
//   --tray     纯 Rust 托盘进程，无 Flutter 引擎、无网络心跳
//   无参进程   Flutter UI，Dart 侧 AppHeartbeat 随引擎存活
//
// 为什么需要本模块：Dart 心跳随 Flutter 引擎生灭，UI 关闭到托盘（进程退出）或
// 无人登录（无 UI）时，探测注册表 60s 后掉线，设备当不了探测器。--server 由服务
// 看护且持有同一份经 IPC 落盘的 api-server 配置（ui_interface::set_options 经
// IPC 转发到服务侧 Config），是最可靠的常驻心跳载体。
//
// 双发安全性：安装版 UI 存活时 Dart 与 Rust 两条心跳并存（同 peer id）。服务端
// Presence/ProbeNode 按 peer id 幂等覆盖；探测槽位一旦 Dispatched 即不再 Open，
// 任务只会被其中一条心跳取走，不会重复探测。未安装服务/便携版没有 --server，
// 仍由 Dart 侧单独承担。
//
// 与 sync.rs 服务心跳的区别：本通道不受 stop-service 开关影响（服务停止后 App
// 在线态与探测资格仍保留），os 字段驱动服务端桌面白名单（当前仅 windows 进探测）。

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use hbb_common::{log, tokio};
use serde_json::{json, Value};

use crate::hbbs_http::{probe::ProbeTask, sync::api_server_base};

const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(3);

static STARTED: AtomicBool = AtomicBool::new(false);

/// 进程内单例启动：重复调用直接返回。独立 std 线程 + current-thread 运行时，
/// 与 sync/probe worker 同模式，不占用调用方运行时。
pub fn start() {
    if STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    let builder = std::thread::Builder::new().name("app-heartbeat".to_owned());
    if let Err(e) = builder.spawn(|| {
        let rt = match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => rt,
            Err(e) => {
                log::error!("app heartbeat runtime build failed: {}", e);
                return;
            }
        };
        rt.block_on(heartbeat_loop());
    }) {
        log::error!("app heartbeat thread spawn failed: {}", e);
    }
}

async fn heartbeat_loop() {
    log::info!(
        "app heartbeat started os={} interval={}s",
        std::env::consts::OS,
        HEARTBEAT_INTERVAL.as_secs()
    );
    let mut interval = tokio::time::interval(HEARTBEAT_INTERVAL);
    let mut tick: u64 = 0;
    let mut ok_count: u64 = 0;
    let mut skip_count: u64 = 0;
    let mut fail_count: u64 = 0;
    let mut tasks_total: u64 = 0;
    let mut last_result = String::new();

    loop {
        interval.tick().await;
        tick += 1;
        let result = tick_once().await;
        match result.kind {
            TickKind::Ok => ok_count += 1,
            TickKind::Skip => skip_count += 1,
            TickKind::Fail => fail_count += 1,
        }
        tasks_total += result.tasks;
        // 状态变化立即留痕（首次成功/首次失败都能抓到）；稳态每 100 拍（约 5
        // 分钟）补一条存活汇总，避免 3s 高频 Info 刷屏；其余走 Debug 按需开启。
        if result.summary != last_result || tick % 100 == 0 {
            log::info!(
                "app heartbeat tick#{} {} (ok={} skip={} fail={} tasks={})",
                tick,
                result.summary,
                ok_count,
                skip_count,
                fail_count,
                tasks_total
            );
        } else {
            log::debug!("app heartbeat tick#{} {}", tick, result.summary);
        }
        if result.tasks > 0 {
            log::info!(
                "app heartbeat probe tasks enqueued={} cumulative={}",
                result.tasks,
                tasks_total
            );
        }
        last_result = result.summary;
    }
}

enum TickKind {
    Ok,
    Skip,
    Fail,
}

struct TickResult {
    kind: TickKind,
    summary: String,
    tasks: u64,
}

/// 单拍心跳。串行循环天然防请求堆积：上一拍未返回（post_request 内部 12s
/// 超时兜底）不会进入下一拍，弱网下自动降频而非叠加请求。
async fn tick_once() -> TickResult {
    let base = api_server_base();
    if base.is_empty() {
        return TickResult {
            kind: TickKind::Skip,
            summary: "skip empty-base".to_owned(),
            tasks: 0,
        };
    }
    let id = hbb_common::config::Config::get_id();
    if id.is_empty() {
        return TickResult {
            kind: TickKind::Skip,
            summary: "skip empty-id".to_owned(),
            tasks: 0,
        };
    }
    let uuid = crate::ui_interface::get_uuid();
    // 字段与 Dart 侧 AppHeartbeat 严格对齐；os 取值 std::env::consts::OS
    // （windows/macos/linux），与 Dart Platform.operatingSystem 字面量一致。
    let body = json!({
        "id": id,
        "uuid": uuid,
        "ver": 1,
        "source": "app",
        "os": std::env::consts::OS,
    })
    .to_string();
    let url = format!("{}/api/heartbeat", base);
    match crate::post_request(url, body, "").await {
        Err(e) => TickResult {
            kind: TickKind::Fail,
            summary: format!("fail {}", e),
            tasks: 0,
        },
        Ok(text) => {
            let tasks = dispatch_probe_tasks(&text);
            // uuid 为空时服务端在白名单逻辑前静默返回 {} 不注册，客户端这拍却是
            // 200，显式标注避免误判为"已注册"。
            let warn = if uuid.is_empty() {
                " warn-empty-uuid"
            } else {
                ""
            };
            TickResult {
                kind: TickKind::Ok,
                summary: format!(
                    "ok os={} uuid_len={}{}",
                    std::env::consts::OS,
                    uuid.len(),
                    warn
                ),
                tasks,
            }
        }
    }
}

/// 解析心跳响应里的 probe_tasks 并交给同进程 probe worker。
/// 无任务时服务端返回 {}，老服务端没有该字段，均安全跳过。
fn dispatch_probe_tasks(body: &str) -> u64 {
    let Ok(data) = serde_json::from_str::<Value>(body) else {
        return 0;
    };
    let Some(arr) = data.get("probe_tasks").and_then(|v| v.as_array()) else {
        return 0;
    };
    if arr.is_empty() {
        return 0;
    }
    // 服务端 DTO 与客户端 ProbeTask 同名字段，未知字段 serde 默认忽略。
    let tasks: Vec<ProbeTask> = arr
        .iter()
        .filter_map(|v| serde_json::from_value(v.clone()).ok())
        .collect();
    let n = tasks.len() as u64;
    for task in tasks {
        crate::hbbs_http::probe::spawn(task);
    }
    n
}
