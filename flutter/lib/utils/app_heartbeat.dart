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
//   - 频率 15s：服务端在线窗口 60s，可容忍连续 3 次丢包；
//   - 复用 HttpFallback：https 传输失败自动回退 http，与业务接口一致；
//   - 服务端在服务心跳权威窗口（10s）内自动忽略 app 心跳的在线态部分，
//     不会降级设备列表的绿色在线态；探测槽位状态机保证任务只下发一次；
//   - 任何异常静默处理，心跳永不影响主流程；
//   - App 进程/引擎销毁后 Timer 自然停止，服务端 60s 后判离线。
import 'dart:async';
import 'dart:convert';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter_hbb/common.dart';
import 'package:flutter_hbb/models/platform_model.dart';

import 'http_service.dart';

class AppHeartbeat {
  static const Duration _interval = Duration(seconds: 15);
  static const Duration _requestTimeout = Duration(seconds: 10);
  static Timer? _timer;
  static bool _sending = false;

  /// 随主 App 启动调用；重复调用会先取消旧 Timer。
  static void start() {
    // Web 端只作为主控使用，不是被控设备，无需上报。
    if (kIsWeb) return;
    _timer?.cancel();
    _tick();
    _timer = Timer.periodic(_interval, (_) => _tick());
  }

  static void stop() {
    _timer?.cancel();
    _timer = null;
  }

  static Future<void> _tick() async {
    // 上一轮还没回来就跳过，避免弱网下请求堆积。
    if (_sending) return;
    _sending = true;
    try {
      final id = await bind.mainGetMyId();
      if (id.isEmpty) return;
      final base = apiBase;
      if (base.isEmpty) return;
      final uuid = await bind.mainGetUuid();
      // os 标识客户端平台（android/windows/macos/linux）：服务端桌面白名单据此
      // 拒绝移动端进入探测注册表（fail-closed），探测在线态与派单只认桌面端。
      final body = jsonEncode({
        'id': id,
        'uuid': uuid,
        'ver': 1,
        'source': 'app',
        'os': Platform.operatingSystem,
      });
      final resp = await post(
        Uri.parse('$base/api/heartbeat'),
        body: body,
      ).timeout(_requestTimeout);
      if (resp.statusCode != 200) {
        debugPrint('app-heartbeat: unexpected status=${resp.statusCode}');
        return;
      }
      _dispatchProbeTasks(resp.body);
    } catch (e) {
      // 网络抖动/服务不可用等：下一轮自动重试，不打扰用户。
      debugPrint('app-heartbeat failed: $e');
    } finally {
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
      debugPrint('app-heartbeat: probe tasks enqueued=$n');
    } catch (e) {
      debugPrint('app-heartbeat: dispatch probe tasks failed: $e');
    }
  }
}
