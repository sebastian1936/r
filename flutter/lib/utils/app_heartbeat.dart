// App 级心跳链路（探测功能的在线通道）：
// Rust 核心的服务心跳（hbbs_http/sync.rs，3s 一次）受 stop-service 开关控制，
// 用户在 App 内"停止服务"后管理后台立即判离线。本链路只要 Flutter 引擎存活
// 就周期性上报 source=app 心跳，承担两个职责：
//   1. 在线态：服务端据此展示"App在线"（服务未开、暂不可被控）；
//   2. 探测（仅桌面端）：pull 探测任务转交给 Rust probe worker 执行——
//      worker 是独立线程，stop-service=Y 时照样工作。
//      移动端探测已下线：安卓不再领取/执行探测任务，心跳仅保留在线态，
//      并随心跳上报 os 供服务端拒绝移动端进入探测注册表。
//
// 设计要点：
//   - 频率 3s：探测全量 PC 化后的低延迟 pull 节奏，服务端在线窗口 60s，
//     可容忍连续约 20 次丢拍；默认走 Rust 缓存 HTTP client（连接池 keep-alive），
//     不重复 TLS 握手，单台日流量约 10MB 量级；
//   - 复用 HttpFallback：https 传输失败自动回退 http，与业务接口一致；
//   - 服务端在服务心跳权威窗口（10s）内自动忽略 app 心跳的在线态部分，
//     不会降级设备列表的绿色在线态；探测槽位状态机保证任务只下发一次；
//   - 任何异常静默处理，心跳永不影响主流程；
//   - App 进程/引擎销毁后 Timer 自然停止，服务端 60s 后判离线。
//
// 可观测性：release 桌面程序没有控制台，debugPrint 全部不可见。心跳又是
// "静默失败、下拍重试"的后台链路，一旦某台机器不注册（空 id/uuid 被服务端
// 拒绝、apiBase 为空、HTTP 持续失败、start() 根本没跑到）将毫无痕迹。
// 因此把每一拍的结果落到 %TEMP%\starcare-heartbeat.log（与启动日志同目录、
// 同命名族），现场只需取回这一个文件即可区分"没发"还是"发了被拒/失败"。
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_hbb/common.dart';
import 'package:flutter_hbb/models/platform_model.dart';

import 'http_service.dart';

class AppHeartbeat {
  static const Duration _interval = Duration(seconds: 3);
  static const Duration _requestTimeout = Duration(seconds: 10);
  static Timer? _timer;
  static bool _sending = false;

  // ===== 持久化诊断（仅观测，不参与任何业务判断）=====
  // 文件超 512KB 整体重写，保证留存的总是最近一段历史，避免无限增长。
  static final File _diagFile = File(
      '${Directory.systemTemp.path}${Platform.pathSeparator}starcare-heartbeat.log');
  static int _tickCount = 0;
  static int _okCount = 0;
  static int _skipCount = 0;
  static int _failCount = 0;
  static int _tasksTotal = 0;
  static String _lastResult = '';

  static void _hbDiag(String msg) {
    if (kIsWeb) return;
    try {
      final line = '[${DateTime.now().toIso8601String()}] $msg';
      debugPrint(line);
      var truncate = false;
      try {
        truncate =
            _diagFile.existsSync() && _diagFile.lengthSync() > 512 * 1024;
      } catch (_) {}
      _diagFile.writeAsStringSync('$line\n',
          mode: truncate ? FileMode.write : FileMode.append, flush: true);
    } catch (_) {}
  }

  /// 结果状态机：结果变化立即落盘（首个成功/首次失败都能抓到）；
  /// 结果不变时每 20 拍（约 1 分钟）补一条，既能发现"持续静默失败"也不刷屏；
  /// 每 100 拍（约 5 分钟）写一条存活汇总，证明 Timer 仍在跑并给出累计计数。
  static void _record(String result) {
    _tickCount++;
    if (result != _lastResult || _tickCount % 20 == 0) {
      _hbDiag('tick#$_tickCount $result');
    }
    if (_tickCount % 100 == 0) {
      _hbDiag('alive ticks=$_tickCount ok=$_okCount skip=$_skipCount '
          'fail=$_failCount tasks=$_tasksTotal last="$result"');
    }
    _lastResult = result;
  }

  /// 随主 App 启动调用；重复调用会先取消旧 Timer。
  static void start() {
    // Web 端只作为主控使用，不是被控设备，无需上报。
    if (kIsWeb) return;
    _timer?.cancel();
    _hbDiag('=== AppHeartbeat.start os=${Platform.operatingSystem} '
        'interval=${_interval.inSeconds}s file=${_diagFile.path} ===');
    _tick();
    _timer = Timer.periodic(_interval, (_) => _tick());
  }

  static void stop() {
    _timer?.cancel();
    _timer = null;
    _hbDiag('AppHeartbeat.stop');
  }

  static Future<void> _tick() async {
    // 上一轮还没回来就跳过，避免弱网下请求堆积。
    if (_sending) return;
    _sending = true;
    // 统一在 finally 里 _record，保证任何分支（含异常）都恰好留一条结果。
    var result = 'UNKNOWN';
    try {
      final id = await bind.mainGetMyId();
      if (id.isEmpty) {
        _skipCount++;
        result = 'SKIP empty-id（mainGetMyId 返回空，心跳未发出）';
      } else {
        final base = apiBase;
        if (base.isEmpty) {
          _skipCount++;
          result = 'SKIP empty-base（apiBase 为空，心跳未发出）';
        } else {
          final uuid = await bind.mainGetUuid();
          // os 标识客户端平台（android/windows/macos/linux）：服务端桌面白名单据此
          // 拒绝移动端进入探测注册表（fail-closed），探测在线态与派单只认桌面端。
          final os = Platform.operatingSystem;
          final body = jsonEncode({
            'id': id,
            'uuid': uuid,
            'ver': 1,
            'source': 'app',
            'os': os,
          });
          final resp = await post(
            Uri.parse('$base/api/heartbeat'),
            body: body,
          ).timeout(_requestTimeout);
          if (resp.statusCode != 200) {
            _failCount++;
            result = 'HTTP-${resp.statusCode} id=$id os=$os base=$base';
          } else {
            _okCount++;
            // uuid 为空时服务端会在白名单逻辑前静默返回 {}（不写探测注册表），
            // 客户端这拍却是 200，必须在日志里显式标出，否则会误判为"已注册"。
            final warn = uuid.isEmpty
                ? ' WARN empty-uuid（服务端将静默拒绝、不注册）'
                : '';
            result = 'OK id=$id os=$os uuidLen=${uuid.length} '
                'base=$base$warn';
            _dispatchProbeTasks(resp.body);
          }
        }
      }
    } catch (e) {
      // 网络抖动/服务不可用等：下一轮自动重试，不打扰用户。
      _failCount++;
      result = 'FAIL ${e.runtimeType}: $e';
    } finally {
      _record(result);
      _sending = false;
    }
  }

  /// 解析心跳响应里的 probe_tasks 并交给 Rust probe worker。
  /// 无任务时服务端返回 {}，老服务端没有该字段，均安全跳过。
  /// 安卓端探测已下线，客户端侧直接拦截（服务端 os 判定为主，本处双保险）。
  static void _dispatchProbeTasks(String body) {
    if (Platform.isAndroid) return;
    try {
      final data = jsonDecode(body);
      final tasks = (data is Map && data['probe_tasks'] is List)
          ? data['probe_tasks'] as List
          : null;
      if (tasks == null || tasks.isEmpty) return;
      // FFI 入队非阻塞，队列满由 Rust 端丢弃并告警，服务端 TTL 兜底。
      final n = bind.mainEnqueueProbeTasks(tasksJson: jsonEncode(tasks));
      _tasksTotal += n;
      _hbDiag('probe tasks enqueued=$n (cumulative=$_tasksTotal)');
    } catch (e) {
      _hbDiag('dispatch probe tasks failed: ${e.runtimeType}: $e');
    }
  }
}
