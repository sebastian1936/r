// 被控"未就绪"时的本地网络自检：
// 用户在服务状态为"未就绪"时点【检查网络】，就地跑一遍诊断并展示结果，
// 全程本地完成、不上传任何信息。检查目标取自当前生效线路（EndpointsConfig）：
//   1) Rust 侧信令注册状态（mainGetConnectStatus）
//   2) DNS 解析（信令服务器域名；直配 IP 时跳过）
//   3) TCP 连通：hbbs(默认21116) / hbbr(默认21117) / API(443或端口)
// 结果可一键复制发给客服。
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hbb/common.dart';

import 'endpoints.dart';

/// 单条检查结果。[state]: ok / fail / info / waiting
class NetCheckItem {
  final String name;
  final String state;
  final String detail;

  const NetCheckItem(this.name, this.state, this.detail);

  bool get ok => state == 'ok';
}

class _HostPort {
  final String host;
  final int port;
  const _HostPort(this.host, this.port);
}

/// "host:port" / "[ipv6]:port" / "host"（用 defaultPort）
_HostPort _parseHostPort(String s, int defaultPort) {
  final t = s.trim();
  if (t.startsWith('[')) {
    final end = t.indexOf(']');
    if (end > 1) {
      final h = t.substring(1, end);
      var p = defaultPort;
      if (end + 2 < t.length && t[end + 1] == ':') {
        p = int.tryParse(t.substring(end + 2)) ?? defaultPort;
      }
      return _HostPort(h, p);
    }
  }
  final idx = t.lastIndexOf(':');
  if (idx >= 0) {
    final p = int.tryParse(t.substring(idx + 1));
    if (p != null) return _HostPort(t.substring(0, idx), p);
  }
  return _HostPort(t, defaultPort);
}

const int _tcpTimeoutMs = 4000;

/// 执行网络检查，每完成一项通过 [onProgress] 回传当前完整列表。
/// 返回可复制的纯文本报告。
Future<String> runNetworkCheck({
  void Function(List<NetCheckItem> items)? onProgress,
}) async {
  final items = <NetCheckItem>[];
  final buf = StringBuffer();
  final ts = DateTime.now().toLocal().toString().substring(0, 19);
  buf.writeln('网络诊断 $ts');
  buf.writeln('线路模式: ${EndpointsConfig.officialMode ? "官方明文" : "混淆"}');

  void emit(String name, String state, String detail) {
    final item = NetCheckItem(name, state, detail);
    items.add(item);
    buf.writeln(
        '[${state == 'ok' ? '正常' : state == 'fail' ? '失败' : '信息'}] '
        '$name：$detail');
    onProgress?.call(List.unmodifiable(items));
  }

  // 1) 当前信令注册状态
  var statusNum = 99;
  try {
    final m = jsonDecode(await bind.mainGetConnectStatus())
        as Map<String, dynamic>;
    statusNum = m['status_num'] as int? ?? 99;
  } catch (_) {}
  final statusText = statusNum == 1
      ? '已就绪'
      : statusNum == 0
          ? '连接中'
          : statusNum == -1
              ? '未就绪'
              : '未知';
  emit('服务注册状态', statusNum == 1 ? 'ok' : 'fail', statusText);

  final ep = EndpointsConfig.active;
  final idHp = _parseHostPort(ep.id, 21116);

  // 2) DNS（直配 IP 无需解析）
  var dnsOk = true;
  if (InternetAddress.tryParse(idHp.host) != null) {
    emit('DNS 解析', 'info', '${idHp.host}（直连 IP，无需解析）');
  } else {
    final sw = Stopwatch()..start();
    try {
      final addrs = await InternetAddress.lookup(idHp.host);
      dnsOk = addrs.isNotEmpty;
      emit(
          'DNS 解析',
          dnsOk ? 'ok' : 'fail',
          dnsOk
              ? '${idHp.host} → ${addrs.map((a) => a.address).join(", ")}'
                  '（${sw.elapsedMilliseconds}ms）'
              : '${idHp.host} 无解析结果');
    } catch (e) {
      dnsOk = false;
      emit('DNS 解析', 'fail', '${idHp.host} 解析失败：$e');
    }
  }

  // 3) TCP 探测
  Future<bool> tcpProbe(String label, String host, int port) async {
    final sw = Stopwatch()..start();
    Socket? socket;
    try {
      socket = await Socket.connect(host, port,
          timeout: const Duration(milliseconds: _tcpTimeoutMs));
      emit('TCP $label', 'ok', '$host:$port 可达（${sw.elapsedMilliseconds}ms）');
      return true;
    } catch (e) {
      emit('TCP $label', 'fail', '$host:$port 不通：$e');
      return false;
    } finally {
      socket?.destroy();
    }
  }

  // DNS 失败时继续 TCP 也只会卡在解析超时，结果没有额外价值，跳过。
  var hbbsOk = false;
  var apiOk = false;
  if (dnsOk) {
    hbbsOk = await tcpProbe('信令服务器(hbbs)', idHp.host, idHp.port);
    if (ep.relay.trim().isNotEmpty) {
      final rHp = _parseHostPort(ep.relay.trim(), 21117);
      await tcpProbe('中继服务器(hbbr)', rHp.host, rHp.port);
    } else {
      emit('TCP 中继服务器(hbbr)', 'info', '未单独配置，由 hbbs 自动分配');
    }
    if (ep.api.trim().isNotEmpty) {
      try {
        final raw = ep.api.trim();
        final uri = Uri.parse(raw.contains('://') ? raw : 'http://$raw');
        final host = uri.host;
        final port = uri.port > 0
            ? uri.port
            : (uri.scheme == 'https' ? 443 : 80);
        apiOk = await tcpProbe('API 服务(${uri.scheme})', host, port);
      } catch (e) {
        emit('TCP API 服务', 'fail', '地址解析失败：$e');
      }
    }
  } else {
    emit('TCP 连通性', 'info', 'DNS 已失败，跳过 TCP 探测');
  }

  // 4) 结论与建议
  String advice;
  if (statusNum == 1) {
    advice = '服务当前已就绪，无需处理。';
  } else if (!dnsOk) {
    advice = 'DNS 解析失败：当前网络可能限制了域名解析。请尝试切换 Wi-Fi/移动网络后重试。';
  } else if (hbbsOk) {
    advice = '网络可达信令服务器但仍未就绪：可能是服务器繁忙、密钥不匹配或注册被拒绝，'
        '请稍后重试；若持续如此请把本报告发给客服。';
  } else if (apiOk) {
    advice = '仅信令端口（21116）不通：该网络可能针对性阻断了远控信令端口，'
        '请切换网络（Wi-Fi↔移动数据）后重试。';
  } else {
    advice = '无法连接到服务器：请确认手机已联网且未启用 VPN/代理/防火墙，'
        '然后切换网络重试。';
  }
  buf.writeln('结论：$advice');
  onProgress?.call(List.unmodifiable(items));
  return buf.toString();
}

/// 弹出网络自检窗口（手机/桌面共用）。
Future<void> showNetworkCheckDialog(BuildContext context) async {
  await showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (_) => const _NetworkCheckDialog(),
  );
}

class _NetworkCheckDialog extends StatefulWidget {
  const _NetworkCheckDialog();

  @override
  State<_NetworkCheckDialog> createState() => _NetworkCheckDialogState();
}

class _NetworkCheckDialogState extends State<_NetworkCheckDialog> {
  final List<NetCheckItem> _items = [];
  String _report = '';
  bool _running = true;

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    final report = await runNetworkCheck(onProgress: (list) {
      if (!mounted) return;
      setState(() => _items
        ..clear()
        ..addAll(list));
    });
    if (!mounted) return;
    setState(() {
      _report = report;
      _running = false;
    });
  }

  IconData _icon(String state) {
    switch (state) {
      case 'ok':
        return Icons.check_circle;
      case 'fail':
        return Icons.cancel;
      default:
        return Icons.info_outline;
    }
  }

  Color _color(String state) {
    switch (state) {
      case 'ok':
        return Colors.green;
      case 'fail':
        return Colors.redAccent;
      default:
        return Colors.grey;
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Row(children: [
        const Icon(Icons.network_check, size: 22),
        const SizedBox(width: 8),
        const Expanded(child: Text('网络诊断')),
        if (_running)
          const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2)),
      ]),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Flexible(
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: _items.length,
                separatorBuilder: (_, __) => const Divider(height: 12),
                itemBuilder: (_, i) {
                  final it = _items[i];
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(_icon(it.state), color: _color(it.state), size: 18),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(it.name,
                                style: const TextStyle(
                                    fontWeight: FontWeight.w600)),
                            const SizedBox(height: 2),
                            Text(it.detail,
                                style: const TextStyle(fontSize: 12)),
                          ],
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
            if (_items.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('正在检查…', style: TextStyle(color: Colors.grey)),
              ),
          ],
        ),
      ),
      actions: [
        if (!_running)
          TextButton.icon(
            icon: const Icon(Icons.copy, size: 18),
            label: const Text('复制报告'),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: _report));
              showToast(translate('Copied'));
            },
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(_running ? '后台运行' : '关闭'),
        ),
      ],
    );
  }
}
