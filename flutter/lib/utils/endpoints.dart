// 线路（服务器）配置管理：
// - 安装包内置 assets/config/endpoints_v1.json 作为出厂兜底；
// - 可写缓存文件（应用数据目录）在 COS 更新后覆盖；
// - 启动读缓存/内置并立即拉一次 COS；
// - 运行期以"连不上信令服务器"为主要触发（见 onConnectStatusChanged）：
//   正常在线时零请求；另保留 [syncInterval] 一次的低频保底轮询，
//   防止极端僵尸连接状态；校验通过才原子替换，内容未变不写盘；
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

// ===== 线路诊断日志（定位"装机后到底用了哪条线路/COS 为何没拉到"）=====
// Android 写到 getExternalStorageDirectory()/endpoint_diag.log，
// 文件管理器可见：Android/data/<包名>/files/endpoint_diag.log；
// 其他平台写到系统临时目录。不依赖 logcat，用户可直接取回。
File? _epDiagFile;

/// 把异常拆成有证据价值的描述：DNS 失败 / 超时 / TLS 握手失败都能区分。
String epErrDesc(Object e) {
  if (e is SocketException) {
    final oe = e.osError;
    return 'SocketException msg="${e.message}"'
        '${oe != null ? ' osError=${oe.errorCode}:${oe.message}' : ''}'
        ' addr=${e.address?.address}:${e.port}';
  }
  if (e is TimeoutException) {
    return 'TimeoutException(after ${e.duration})';
  }
  if (e is HandshakeException) {
    return 'HandshakeException type=${e.type} msg="${e.message}"';
  }
  return '${e.runtimeType}: $e';
}

void epDiag(String msg) {
  final line = '[${DateTime.now().toIso8601String()}] $msg';
  debugPrint(line);
  final f = _epDiagFile;
  if (f == null) return;
  try {
    // 超过 1MB 直接截断重写，避免无限增长。
    final mode = f.lengthSync() > 1024 * 1024 ? FileMode.write : FileMode.append;
    f.writeAsStringSync('$line\n', mode: mode, flush: true);
  } catch (_) {}
}

Future<void> epDiagInit() async {
  Directory? dir;
  try {
    if (Platform.isAndroid) dir = await getExternalStorageDirectory();
  } catch (e) {
    debugPrint('ep-diag external dir failed: $e');
  }
  dir ??= Directory.systemTemp;
  _epDiagFile =
      File('${dir.path}${Platform.pathSeparator}endpoint_diag.log');
  epDiag('=== endpoint diag session start; file=${_epDiagFile!.path} ===');
}

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

/// 老系统（Win7 等）TLS 兼容：业务 API 先走 https，发生传输层失败
/// （TLS 握手失败 / 连接被拒 / 超时，HTTP 状态码不算）后自动改走 http，
/// 替代过去的 USE_HTTP 单独编译包。
///
/// - 切换结果持久化在 option [optionForceHttp]，重启不丢，后续请求零损耗；
/// - [reProbeAfter] 后用下一次请求再探一次 https，服务端证书/网络修复后自动切回；
/// - 地址改写规则与旧 USE_HTTP 包一致：scheme https→http，端口 41112→41111；
/// - USE_HTTP 编译包（[kUseHttpApi]）行为不变，恒走 http。
class HttpFallback {
  static const String optionForceHttp = 'api-force-http';
  static const String optionForceHttpAt = 'api-force-http-at';
  static const Duration reProbeAfter = Duration(hours: 12);

  static bool? _forceHttp;

  /// 老旧 Windows（Win7/8/8.1/Server 2008R2/2012/2012R2）。
  /// 由 main() 在任何网络请求前置位：这些系统上 Flutter 3.24 引擎的 Dart
  /// TLS(BoringSSL) 发起首个 HTTPS 会直接 native 崩溃(0x40000015)，
  /// try-catch 拦不住，"先试 https"会把进程打死。置位后全程 http、
  /// 永不重探 https（见 [_probeDue]）。
  static bool legacyOs = false;

  /// 编译期 http 包恒为 true；旧系统恒为 true；其余看持久化 option。
  static bool get forceHttp {
    if (kUseHttpApi || legacyOs) return true;
    _forceHttp ??= bind.mainGetOptionSync(key: optionForceHttp) == 'Y';
    return _forceHttp!;
  }

  /// https 地址对应的 http 兜底地址（无 https 前缀时原样返回）。
  static String toHttpAlt(String url) {
    if (!url.startsWith('https://')) return url;
    return url
        .replaceFirst('https://', 'http://')
        .replaceFirst(':41112', ':41111');
  }

  /// 按当前回退状态解析业务 API 基址。
  static String resolve(String url) =>
      forceHttp && url.startsWith('https://') ? toHttpAlt(url) : url;

  /// 传输层失败才允许回退；拿到 HTTP 响应（含 4xx/5xx）一律不回退。
  static bool isTransportFailure(Object e) {
    if (e is HandshakeException ||
        e is SocketException ||
        e is TlsException ||
        e is TimeoutException ||
        e is http.ClientException) {
      return true;
    }
    // Rust 通道（reqwest）传输层失败时，状态不是合法 JSON，
    // HttpService 会包成 "Failed to parse response" 抛出。
    final s = e.toString();
    return s.contains('Failed to parse response') ||
        s.contains('The HTTP request failed');
  }

  /// 以 https 先发，失败自动重试 http；url 非 https 时直通。
  /// 调用方只需保证 [run] 对传入的 Uri 各执行一次完整请求。
  /// [diagTag] 非空时把每次尝试的真实结果（状态码/耗时/底层错误）写入诊断日志。
  static Future<http.Response> send(
    Uri url,
    Future<http.Response> Function(Uri) run, {
    String? diagTag,
  }) async {
    Future<http.Response> tagged(Uri u, String attempt) async {
      final t0 = DateTime.now();
      try {
        final r = await run(u);
        if (diagTag != null) {
          final ms = DateTime.now().difference(t0).inMilliseconds;
          epDiag('$diagTag $attempt -> ${r.statusCode} '
              '${r.bodyBytes.length}B ${ms}ms url=$u');
        }
        return r;
      } catch (e) {
        if (diagTag != null) {
          final ms = DateTime.now().difference(t0).inMilliseconds;
          epDiag('$diagTag $attempt FAIL ${ms}ms url=$u :: ${epErrDesc(e)}');
        }
        rethrow;
      }
    }

    if (!url.isScheme('https')) {
      return tagged(url, 'http(direct)');
    }
    // 已切 http：未到重探周期直接走 http；到周期则先用 https 探一次。
    if (forceHttp && !_probeDue) {
      return tagged(Uri.parse(toHttpAlt(url.toString())), 'http(cached)');
    }
    try {
      final resp = await tagged(url, 'https');
      if (forceHttp) {
        // 重探 https 成功，清除回退标记，后续恢复 https。
        _forceHttp = false;
        bind.mainSetOption(key: optionForceHttp, value: 'N');
        debugPrint('api-fallback: https recovered, back to https');
      }
      return resp;
    } catch (e) {
      if (!isTransportFailure(e)) rethrow;
      if (!forceHttp) {
        _forceHttp = true;
        final now = DateTime.now().millisecondsSinceEpoch.toString();
        bind.mainSetOption(key: optionForceHttp, value: 'Y');
        bind.mainSetOption(key: optionForceHttpAt, value: now);
        debugPrint('api-fallback: https failed ($e), switch to http');
      } else {
        // 重探失败：刷新计时，接下来 12h 不再为每次请求付出 https 尝试代价。
        bind.mainSetOption(
            key: optionForceHttpAt,
            value: DateTime.now().millisecondsSinceEpoch.toString());
      }
      return tagged(Uri.parse(toHttpAlt(url.toString())), 'http(fallback)');
    }
  }

  static bool get _probeDue {
    // 旧系统上 https 探测本身会打死进程，永不重探。
    if (!forceHttp || kUseHttpApi || legacyOs) return false;
    final at =
        int.tryParse(bind.mainGetOptionSync(key: optionForceHttpAt)) ?? 0;
    return DateTime.now().millisecondsSinceEpoch - at >
        reProbeAfter.inMilliseconds;
  }
}

class EndpointStore {
  static const String _assetName = 'assets/config/endpoints_v1.json';
  static const String _fileName = 'endpoints_v1.json';
  static const String _cosPath = '/endpoints_v1.json';

  /// 低频保底同步间隔。主力是"连不上就拉"的事件触发（正常在线零请求）；
  /// 保底只为覆盖极端情况（旧信令一直半死不活、状态始终未变 -1）。
  /// 万级客户端 6 小时一次约 1200 万次/月，费用可忽略。
  static const Duration syncInterval = Duration(hours: 6);

  /// 应急回源退避间隔：检测到连不上后立即拉一次，仍不通则依次
  /// 30s/60s/2min/5min 重试，封顶后保持 5 分钟一次直到恢复在线。
  static const List<Duration> _fallbackIntervals = [
    Duration(seconds: 30),
    Duration(seconds: 60),
    Duration(minutes: 2),
    Duration(minutes: 5),
  ];

  static EndpointsConfig? _current;
  static Timer? _syncTimer;
  static Timer? _fallbackTimer;
  static int _fallbackRound = 0;
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

  /// 业务 API 地址。正常 https；老系统 https 传输失败后由 [HttpFallback]
  /// 自动改写为 http:41111（USE_HTTP 编译包则直接恒 http）。
  static String get apiBase {
    final url = current.section(officialMode).api;
    if (kUseHttpApi) {
      return url
          .replaceFirst('https://', 'http://')
          .replaceFirst(':41112', ':41111');
    }
    return HttpFallback.resolve(url);
  }

  static Future<File> _cacheFile() async {
    final dir = await getApplicationSupportDirectory();
    return File('${dir.path}${Platform.pathSeparator}$_fileName');
  }

  /// 启动早期同步准备：优先可写缓存，失败回退安装包内置 asset。
  static Future<void> init() async {
    await epDiagInit();
    epDiag('init begin; asset=$_assetName');
    var source = '';
    try {
      final f = await _cacheFile();
      final exists = await f.exists();
      epDiag('cache file=${f.path} exists=$exists');
      if (exists) {
        final raw = await f.readAsString();
        final cfg = EndpointsConfig.tryParse(raw);
        epDiag('cache parse ${cfg != null ? 'OK' : 'FAILED'} bytes=${raw.length}');
        if (cfg != null) {
          _current = cfg;
          source = 'cache';
        }
      }
    } catch (e) {
      epDiag('cache read EXC ${epErrDesc(e)}');
    }
    if (_current == null) {
      try {
        final raw = await rootBundle.loadString(_assetName);
        final cfg = EndpointsConfig.tryParse(raw);
        epDiag('asset load OK bytes=${raw.length} parse=${cfg != null}');
        if (cfg != null) {
          _current = cfg;
          source = 'asset';
        }
      } catch (e) {
        // Flutter 里 asset 未打包统一抛 Unable to load asset，这是关键证据。
        epDiag('asset load EXC ${e.runtimeType}: $e');
      }
    }
    if (_current == null) {
      _current = _fallback;
      source = 'hardcode-fallback';
    }
    epDiag('init done source=$source '
        'obfs.id=${_current!.obfs.id} official.id=${_current!.official.id}');
  }

  /// 按文件内容把当前模式的服务器 option 写进 Rust 配置（值没变不写，避免无谓重连）。
  /// 每次启动都执行，保证"文件为唯一事实来源"，覆盖手填/旧客户端残留配置。
  static Future<void> applyActive() async {
    final ep = active;
    await _setIfChanged('custom-rendezvous-server', ep.id);
    await _setIfChanged('relay-server', ep.relay);
    // api-server 同步走 http 回退改写：旧系统 Rust 侧(reqwest)也不得碰 https。
    await _setIfChanged('api-server', HttpFallback.resolve(ep.api));
    await _setIfChanged('key', ep.key);
    // 未设置过模式时默认混淆，默认值以诊断日志留证。
    final mode = bind.mainGetOptionSync(key: kTrafficObfuscateOption);
    if (mode.isEmpty) {
      await bind.mainSetOption(key: kTrafficObfuscateOption, value: 'Y');
    }
    epDiag('applyActive officialMode=$officialMode '
        'id=${ep.id} api=${ep.api} relay="${ep.relay}"');
  }

  static Future<void> _setIfChanged(String key, String value) async {
    if (bind.mainGetOptionSync(key: key) != value) {
      await bind.mainSetOption(key: key, value: value);
    }
  }

  /// 主页开关切换：true=官方（兼容 iOS），false=混淆。
  /// 模式 option 与全套线路必须在**同一个批量调用**里原子生效：
  /// 若先写模式触发重启、再写服务器，新 mediator 会用新 codec 连旧服务器，
  /// 发出错配包（明文包打到混淆 hbbs 报 "bytes remaining on stream"，
  /// 反之亦然）；先写服务器同理。批量写入后 Rust 侧只重启一次。
  static Future<void> switchMode(bool official) async {
    final mode = official ? 'N' : 'Y';
    final ep = current.section(official);
    // 一个 FFI 调用原子完成（Rust 侧落盘全部 option 后只重启一次信令），
    // 绑定 mainApplyTrafficMode 由 flutter_rust_bridge 在 CI 构建时生成
    await bind.mainApplyTrafficMode(
      mode: mode,
      idServer: ep.id,
      relayServer: ep.relay,
      apiServer: ep.api,
      key: ep.key,
    );
  }

  /// 启动低频保底 COS 同步（每 [syncInterval] 一次）。随主 isolate 存活：
  /// Android 被控端以前台服务保活，进程在计时就在；Doze 深度休眠期间
  /// 系统会推迟触发，亮屏/唤醒后补一次。
  static void startPeriodicSync() {
    _syncTimer?.cancel();
    _syncTimer =
        Timer.periodic(syncInterval, (_) => refreshFromCos(reason: 'periodic'));
  }

  /// 信令连接状态变化回调（由主窗口监听 serverModel.connectStatus 驱动）。
  /// status 语义（底层 mainGetConnectStatus）：
  ///   >0 = 已注册在线；0 = 连接中；-1 = 连续多次注册无响应（约 15~30s）。
  /// 变 -1 时立即回源 COS 拉最新线路，并按退避间隔持续重试；
  /// 恢复在线后取消重试、重置退避。正常在线期间不产生任何请求。
  static void onConnectStatusChanged(int status) {
    if (status > 0) {
      if (_fallbackTimer != null || _fallbackRound != 0) {
        _fallbackTimer?.cancel();
        _fallbackTimer = null;
        _fallbackRound = 0;
        debugPrint('endpoints: rendezvous online, fallback sync reset');
      }
      return;
    }
    if (status != -1 || _fallbackTimer != null) return;
    debugPrint('endpoints: rendezvous unreachable, fetch COS now');
    refreshFromCos(reason: 'rendezvous-unreachable');
    _scheduleFallback();
  }

  static void _scheduleFallback() {
    final idx = _fallbackRound < _fallbackIntervals.length
        ? _fallbackRound
        : _fallbackIntervals.length - 1;
    final delay = _fallbackIntervals[idx];
    _fallbackRound++;
    _fallbackTimer?.cancel();
    _fallbackTimer = Timer(delay, () async {
      await refreshFromCos(reason: 'backoff-$_fallbackRound');
      // 期间若已恢复在线，onConnectStatusChanged 会把 timer 置空，
      // 不再安排下一轮；否则继续退避（封顶后停留在 5 分钟）。
      if (_fallbackTimer != null) {
        _scheduleFallback();
      }
    });
  }

  /// 从 COS 拉取一次；5s 超时、静默失败，校验不过保留本地。
  /// 内容与本地缓存完全一致时直接返回：不写盘、不重对齐 option，
  /// 周期性轮询的绝大多数请求都走这条最省路径。
  static Future<void> refreshFromCos({String reason = ''}) async {
    if (_refreshing) {
      epDiag('cos fetch skipped(already-running) reason=$reason');
      return;
    }
    _refreshing = true;
    try {
      // COS 同样 https 优先；老系统 TLS 失败自动改 http（站点两种 scheme 都支持）。
      final uri = Uri.parse('$kNodeConfigUrl$_cosPath');
      epDiag('cos fetch begin reason="$reason" url=$uri '
          'forceHttp=${HttpFallback.forceHttp}');
      final t0 = DateTime.now();
      final resp = await HttpFallback.send(
        uri,
        (u) => http.get(u).timeout(const Duration(seconds: 5)),
        diagTag: 'cos',
      );
      final ms = DateTime.now().difference(t0).inMilliseconds;
      if (resp.statusCode != 200) {
        epDiag('cos fetch non-200 status=${resp.statusCode} ${ms}ms');
        return;
      }
      final body = utf8.decode(resp.bodyBytes);
      final cfg = EndpointsConfig.tryParse(body);
      if (cfg == null) {
        epDiag('cos fetch body-invalid bytes=${body.length}');
        return;
      }
      final f = await _cacheFile();
      if (await f.exists() && await f.readAsString() == body) {
        epDiag('cos fetch unchanged bytes=${body.length} ${ms}ms');
        return;
      }
      // 原子写：临时文件 + rename
      final tmp = File('${f.path}.tmp');
      await tmp.writeAsBytes(resp.bodyBytes, flush: true);
      await tmp.rename(f.path);
      _current = cfg;
      epDiag('cos fetch applied new-config ${ms}ms '
          'obfs.id=${cfg.obfs.id} official.id=${cfg.official.id}');
      // 远端配置改了地址，按当前模式重新对齐 option（值没变不会重连）
      await applyActive();
    } catch (e, s) {
      // 记录完整异常类型 + 栈顶，区分 DNS 失败 / 超时 / TLS / 连接被拒。
      final stackHead = const LineSplitter()
          .convert(s.toString())
          .take(3)
          .join(' | ');
      epDiag('cos fetch EXC ${epErrDesc(e)} stack=$stackHead');
    } finally {
      _refreshing = false;
    }
  }

  /// asset 缺失/损坏时的最终兜底（正常不会走到）；值与 COS 保持一致
  static const EndpointsConfig _fallback = EndpointsConfig(
    v: 1,
    obfs: EndpointInfo(
      id: '111.229.43.215:16606',
      relay: '',
      api: 'https://api.nemocc.top:41112',
      key: 'k3lsu+CTLs4OhFpq5Lh38Uvo2m8Cyb1jLz6gTCAnyCw=',
    ),
    official: EndpointInfo(
      id: 'hk.nemoco.top:60016',
      relay: '',
      api: 'https://api.nemocc.top:41112',
      key: 'k3lsu+CTLs4OhFpq5Lh38Uvo2m8Cyb1jLz6gTCAnyCw=',
    ),
  );
}
