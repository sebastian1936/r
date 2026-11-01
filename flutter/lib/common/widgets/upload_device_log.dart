// 客户手动一键上传诊断日志：设置-关于入口共用组件（移动端与 PC 端）。
// 点击后二次确认，再调用 Rust 侧 mainUploadDeviceLog（独立线程打包上传），
// Rust 完成后经全局事件 upload_device_log 回传 success/failed。
import 'dart:async';

import 'package:flutter/material.dart';

import '../common.dart';

/// 上传过程控制器：Rust 事件经 main.dart 注册的全局 handler 转发到这里。
class UploadDeviceLogController {
  UploadDeviceLogController._();
  static final UploadDeviceLogController instance = UploadDeviceLogController._();

  Completer<_UploadResult>? _completer;
  bool running = false;

  /// 由全局事件 handler 调用：state 为 success / failed（started 仅忽略）。
  void onEvent(Map<String, dynamic> evt) {
    final state = evt['state']?.toString() ?? '';
    final completer = _completer;
    if (completer == null || completer.isCompleted) return;
    if (state == 'success') {
      completer.complete(const _UploadResult(ok: true));
    } else if (state == 'failed') {
      completer.complete(
          _UploadResult(ok: false, msg: evt['msg']?.toString() ?? '上传失败'));
    }
  }

  /// 触发一次上传，等待 Rust 侧事件回执。进行中再次调用直接复用本次等待。
  Future<_UploadResult> start() {
    final existing = _completer;
    if (running && existing != null && !existing.isCompleted) {
      return existing.future;
    }
    running = true;
    final completer = Completer<_UploadResult>();
    _completer = completer;
    // 防止 Rust 侧异常导致永远等不到事件
    Timer(const Duration(seconds: 120), () {
      if (!completer.isCompleted) {
        completer.complete(const _UploadResult(
            ok: false, msg: '上传超时，请检查网络后重试'));
      }
    });
    completer.future.whenComplete(() => running = false);
    bind.mainUploadDeviceLog();
    return completer.future;
  }
}

class _UploadResult {
  final bool ok;
  final String msg;
  const _UploadResult({required this.ok, this.msg = ''});
}

/// 入口：先弹确认框，确认后进入 上传中 -> 成功/失败 的流程弹窗。
Future<void> showUploadDeviceLogDialog(BuildContext context) async {
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('上传诊断日志'),
      content: const Text(
          '将打包本机最近 3 天的运行日志并上传到客服后台，用于协助排查问题。\n\n是否继续？'),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(false),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(true),
          child: const Text('继续上传'),
        ),
      ],
    ),
  );
  if (confirmed != true || !context.mounted) return;

  await showDialog(
    context: context,
    barrierDismissible: false,
    builder: (ctx) => const _UploadProgressDialog(),
  );
}

class _UploadProgressDialog extends StatefulWidget {
  const _UploadProgressDialog();

  @override
  State<_UploadProgressDialog> createState() => _UploadProgressDialogState();
}

class _UploadProgressDialogState extends State<_UploadProgressDialog> {
  _UploadResult? _result;
  String _deviceId = '';

  @override
  void initState() {
    super.initState();
    _run();
  }

  Future<void> _run() async {
    final result = await UploadDeviceLogController.instance.start();
    String deviceId = '';
    if (result.ok) {
      try {
        deviceId = await bind.mainGetMyId();
      } catch (_) {}
    }
    if (mounted) {
      setState(() {
        _result = result;
        _deviceId = deviceId;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final result = _result;
    return WillPopScope(
      onWillPop: () async => false,
      child: AlertDialog(
        title: Text(result == null
            ? '正在上传'
            : result.ok
                ? '上传成功'
                : '上传失败'),
        content: result == null
            ? const Row(
                children: [
                  SizedBox(
                      width: 22,
                      height: 22,
                      child: CircularProgressIndicator(strokeWidth: 2.5)),
                  SizedBox(width: 14),
                  Expanded(child: Text('正在打包并上传日志，请稍候…')),
                ],
              )
            : result.ok
                ? Text('日志已上传成功。\n\n请向客服提供您的设备 ID：\n$_deviceId')
                : Text('日志上传失败：\n${result.msg}'),
        actions: [
          if (result == null)
            const TextButton(onPressed: null, child: Text('请稍候…')),
          if (result != null && !result.ok)
            TextButton(
              onPressed: () {
                setState(() => _result = null);
                _run();
              },
              child: const Text('重试'),
            ),
          if (result != null)
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(result.ok ? '完成' : '关闭'),
            ),
        ],
      ),
    );
  }
}
