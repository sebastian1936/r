// App 级心跳链路：
// Rust 核心的服务心跳（hbbs_http/sync.rs，3s 一次）受 stop-service 开关控制，
// 用户在 App 内"停止服务"后管理后台立即判离线，无法区分"App 没开"和
// "App 开着但服务停了"。本链路只要 Flutter 引擎存活就周期性上报
// source=app 心跳，服务端据此展示"App在线"（服务未开、暂不可被控）。
//
// 设计要点：
//   - 频率 15s：服务端在线窗口 60s，可容忍连续 3 次丢包；
//   - 复用 HttpFallback：https 传输失败自动回退 http，与业务接口一致；
//   - 服务端在服务心跳权威窗口（10s）内自动忽略 app 心跳，不会降级在线态；
//   - 任何异常静默处理，心跳永不影响主流程；
//   - App 进程/引擎销毁后 Timer 自然停止，服务端 60s 后判离线。
import 'dart:async';
import 'dart:convert';

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
      final body = jsonEncode({
        'id': id,
        'uuid': uuid,
        'ver': 1,
        'source': 'app',
      });
      final resp = await post(
        Uri.parse('$base/api/heartbeat'),
        body: body,
      ).timeout(_requestTimeout);
      if (resp.statusCode != 200) {
        debugPrint('app-heartbeat: unexpected status=${resp.statusCode}');
      }
    } catch (e) {
      // 网络抖动/服务不可用等：下一轮自动重试，不打扰用户。
      debugPrint('app-heartbeat failed: $e');
    } finally {
      _sending = false;
    }
  }
}
