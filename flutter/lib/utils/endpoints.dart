// 线路（服务器）配置管理：
// - 安装包内置 assets/config/endpoints_v1.json 作为出厂兜底；
// - 可写缓存文件（应用数据目录）在 COS 更新后覆盖；
// - 启动读缓存/内置并立即拉一次 COS，之后每 5 分钟周期同步，
//   校验通过才原子替换，内容未变不写盘；
// - 当前生效哪一段由 Rust option `traffic-obfuscate` 记忆（Y=混淆，N=官方）。
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

import '../common.dart';
import '../models/platform_model.dart';

/// 流量模式 option（对应 Rust 侧 hbb_common::config::OPTION_TRAFFIC_OBFUSCATE）。
/// Y = 混淆模式（默认），N = 官方明文模式（兼容 iOS App Store 官方客户端）。
const String kTrafficObfuscateOption = 'traffic-obfuscate';

class EndpointInfo {
  /// ID/信令服务器，含端口
  final String id;

  /// 中继服务器，空表示由 hbbs 自动分配（服务端可配多个中继）
  final String relay;
  final String api;

  /// RustDesk server 公钥
  final String key;

  const EndpointInfo({
    required this.id,
    required this.relay,
    required this.api,
    required this.key,
  });

  bool get valid => id.isNotEmpty && api.isNotEmpty && key.isNotEmpty;
}

class EndpointsConfig {
  final int v;
  final EndpointInfo obfs;
  final EndpointInfo official;

  const EndpointsConfig({
    required this.v,
    required this.obfs,
    required this.official,
  });

  bool get valid => obfs.valid && official.valid;

  EndpointInfo section(bool officialMode) =>
      officialMode ? official : obfs;

  /// COS/内置文件沿用老线路文件的字符伪装：999 -> '.'，333 -> ':'
  static String decodeBody(String raw) =>
      raw.replaceAll('999', '.').replaceAll('333', ':');

  /// 解析并校验；任何字段缺失/格式错误都返回 null（调用方丢弃，不覆盖本地）
  static EndpointsConfig? tryParse(String rawBody) {
    try {
      final root = jsonDecode(decodeBody(rawBody));
      if (root is! Map<String, dynamic>) return null;
      final v = (root['v'] as num?)?.toInt() ?? 1;
      EndpointInfo parseSection(dynamic raw) {
        if (raw is! Map) throw const FormatException('bad section');
        return EndpointInfo(
          id: (raw['id'] ?? '').toString().trim(),
          relay: (raw['relay'] ?? '').toString().trim(),
          api: (raw['api'] ?? '').toString().trim(),
          key: (raw['key'] ?? '').toString().trim(),
        );
      }

      final cfg = EndpointsConfig(
        v: v,
        obfs: parseSection(root['obfs']),
        official: parseSection(root['official']),
      );
      return cfg.valid ? cfg : null;
    } catch (e) {
      debugPrint('endpoints parse failed: $e');
      return null;
    }
  }
}

class EndpointStore {
  static const String _assetName = 'assets/config/endpoints_v1.json';
  static const String _fileName = 'endpoints_v1.json';
  static const String _cosPath = '/endpoints_v1.json';

  /// 周期性从 COS 同步线路的间隔。配置很少变化，5 分钟既能让换线路
  /// 在可接受时间内全网生效，请求量也仍在可控范围。
  static const Duration syncInterval = Duration(minutes: 5);

  static EndpointsConfig? _current;
  static Timer? _syncTimer;
  static bool _refreshing = false;

  /// 启动早期（runApp 前）已可用
  static EndpointsConfig get current => _current ?? _fallback;

  static bool get isReady => _current != null;

  /// 当前是否官方明文模式
  static bool get officialMode =>
      bind.mainGetOptionSync(key: kTrafficObfuscateOption).toUpperCase() ==
      'N';

  /// 当前模式生效的线路段
  static EndpointInfo get active => current.section(officialMode);

  /// 业务 API 地址。USE_HTTP 测试包（Win7）强制 http:41111。
  static String get apiBase {
    var url = current.section(officialMode).api;
    if (kUseHttpApi) {
      url = url
          .replaceFirst('https://', 'http://')
          .replaceFirst(':41112', ':41111');
    }
    return url;
  }

  static Future<File> _cacheFile() async {
    final dir = await getApplicationSupportDirectory();
    return File('${dir.path}${Platform.pathSeparator}$_fileName');
  }

  /// 启动早期同步准备：优先可写缓存，失败回退安装包内置 asset。
  static Future<void> init() async {
    try {
      final f = await _cacheFile();
      if (await f.exists()) {
        final cfg = EndpointsConfig.tryParse(await f.readAsString());
        if (cfg != null) {
          _current = cfg;
          return;
        }
      }
    } catch (e) {
      debugPrint('endpoints read cache failed: $e');
    }
    try {
      final raw = await rootBundle.loadString(_assetName);
      _current = EndpointsConfig.tryParse(raw);
    } catch (e) {
      debugPrint('endpoints read asset failed: $e');
    }
    _current ??= _fallback;
  }

  /// 按文件内容把当前模式的服务器 option 写进 Rust 配置（值没变不写，避免无谓重连）。
  /// 每次启动都执行，保证"文件为唯一事实来源"，覆盖手填/旧客户端残留配置。
  static Future<void> applyActive() async {
    final ep = active;
    await _setIfChanged('custom-rendezvous-server', ep.id);
    await _setIfChanged('relay-server', ep.relay);
    await _setIfChanged('api-server', ep.api);
    await _setIfChanged('key', ep.key);
    // 未设置过模式时默认混淆，兼容旧版本行为。
    final mode = bind.mainGetOptionSync(key: kTrafficObfuscateOption);
    if (mode.isEmpty) {
      await bind.mainSetOption(key: kTrafficObfuscateOption, value: 'Y');
    }
  }

  static Future<void> _setIfChanged(String key, String value) async {
    if (bind.mainGetOptionSync(key: key) != value) {
      await bind.mainSetOption(key: key, value: value);
    }
  }

  /// 主页开关切换：true=官方（兼容 iOS），false=混淆。
  /// 先写模式再写服务器，保证随后的 mediator 重启用新 codec 探测新服务器。
  static Future<void> switchMode(bool official) async {
    final mode = official ? 'N' : 'Y';
    if (bind.mainGetOptionSync(key: kTrafficObfuscateOption) != mode) {
      await bind.mainSetOption(key: kTrafficObfuscateOption, value: mode);
    }
    await applyActive();
  }

  /// 启动周期性 COS 同步（每 [syncInterval] 一次）。随主 isolate 存活：
  /// Android 被控端以前台服务保活，进程在计时就在；Doze 深度休眠期间
  /// 系统会推迟触发，亮屏/唤醒后补一次——不追求强实时，只追求最终一致。
  static void startPeriodicSync() {
    _syncTimer?.cancel();
    _syncTimer = Timer.periodic(syncInterval, (_) => refreshFromCos());
  }

  /// 从 COS 拉取一次；5s 超时、静默失败，校验不过保留本地。
  /// 内容与本地缓存完全一致时直接返回：不写盘、不重对齐 option，
  /// 周期性轮询的绝大多数请求都走这条最省路径。
  static Future<void> refreshFromCos() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final resp = await http
          .get(Uri.parse('$kNodeConfigUrl$_cosPath'))
          .timeout(const Duration(seconds: 5));
      if (resp.statusCode != 200) {
        debugPrint('endpoints cos status: ${resp.statusCode}');
        return;
      }
      final body = utf8.decode(resp.bodyBytes);
      final cfg = EndpointsConfig.tryParse(body);
      if (cfg == null) {
        debugPrint('endpoints remote invalid, keep local');
        return;
      }
      final f = await _cacheFile();
      if (await f.exists() && await f.readAsString() == body) {
        return;
      }
      // 原子写：临时文件 + rename
      final tmp = File('${f.path}.tmp');
      await tmp.writeAsBytes(resp.bodyBytes, flush: true);
      await tmp.rename(f.path);
      _current = cfg;
      // 远端配置改了地址，按当前模式重新对齐 option（值没变不会重连）
      await applyActive();
    } catch (e) {
      debugPrint('endpoints cos update failed: $e');
    } finally {
      _refreshing = false;
    }
  }

  /// asset 损坏时的最终兜底（正常不会走到）
  static const EndpointsConfig _fallback = EndpointsConfig(
    v: 1,
    obfs: EndpointInfo(
      id: 'obfs.nemocc.top:21116',
      relay: '',
      api: 'https://api.nemocc.top:41112',
      key: 'k3lsu+CTLs4OhFpq5Lh38Uvo2m8Cyb1jLz6gTCAnyCw=',
    ),
    official: EndpointInfo(
      id: 'ios.nemocc.top:21116',
      relay: '',
      api: 'https://api.nemocc.top:41112',
      key: 'k3lsu+CTLs4OhFpq5Lh38Uvo2m8Cyb1jLz6gTCAnyCw=',
    ),
  );
}
