// 主页“兼容 iOS”开关：
// 勾选   -> 官方明文模式 + 官方线路（可被 App Store 官方 RustDesk / iOS 连接）
// 不勾选 -> 混淆加密模式 + 内置线路（仅加密版客户端互通）
// 切换会断开当前所有入站连接，并重建信令注册。
import 'package:flutter/material.dart';
import 'package:flutter_hbb/utils/endpoints.dart';

import '../../common.dart';

class IosCompatSwitch extends StatefulWidget {
  const IosCompatSwitch({Key? key}) : super(key: key);

  @override
  State<IosCompatSwitch> createState() => _IosCompatSwitchState();
}

class _IosCompatSwitchState extends State<IosCompatSwitch> {
  late bool _official = EndpointStore.officialMode;
  bool _busy = false;

  Future<bool?> _confirm(bool target) {
    return showDialog<bool>(
      context: context,
      barrierDismissible: true,
      builder: (ctx) => AlertDialog(
        title: Text(target ? '开启 iOS 兼容模式？' : '关闭 iOS 兼容模式？'),
        content: Text(
          target
              ? '开启后：\n\n'
                  '• 本机可被苹果 App Store 版 RustDesk（iPhone/iPad）发现和连接\n'
                  '• 其他加密版设备将无法看到本机，也无法连接本机\n'
                  '• 对方需使用官方 RustDesk，并填写同一官方服务器地址'
              : '切换后将立即断开当前所有远程连接，并切回加密服务器；\n'
                  '此后仅加密版客户端可以发现和连接本机。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(
              '确认切换',
              style: TextStyle(color: Colors.redAccent),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _onChanged(bool target) async {
    if (_busy || target == _official) return;
    final ok = await _confirm(target);
    if (ok != true) {
      // 用户取消，刷新以还原勾选状态
      if (mounted) setState(() {});
      return;
    }
    setState(() => _busy = true);
    try {
      // 先断开所有正在连入本机的会话（用户已确认）
      await gFFI.serverModel.closeAll();
      // 切换模式 option + 写入对应线路，Rust 侧自动重建信令注册
      await EndpointStore.switchMode(target);
      _official = target;
      showToast(target ? '已切换到 iOS 官方模式' : '已切换到加密模式');
    } catch (e) {
      debugPrint('switch traffic mode failed: $e');
      showToast('切换失败，请重试');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: CheckboxListTile(
        value: _official,
        onChanged: _busy ? null : (v) => _onChanged(v ?? false),
        dense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 8),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(8),
          side: BorderSide(
            color: _official
                ? Colors.blueAccent.withOpacity(0.6)
                : Theme.of(context).dividerColor,
          ),
        ),
        title: const Text('兼容 iOS（接受苹果官方 RustDesk 控制）'),
        subtitle: Text(
          _official
              ? '当前：官方模式，仅官方版 / iOS 客户端可发现本机'
              : '当前：加密模式，仅加密版客户端可发现本机',
          style: const TextStyle(fontSize: 12),
        ),
      ),
    );
  }
}
