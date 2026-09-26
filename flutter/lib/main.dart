import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:bot_toast/bot_toast.dart';
import 'package:desktop_multi_window/desktop_multi_window.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hbb/common/widgets/overlay.dart';
import 'package:flutter_hbb/desktop/pages/desktop_setting_page.dart';
import 'package:flutter_hbb/desktop/pages/desktop_tab_page.dart';
import 'package:flutter_hbb/desktop/pages/install_page.dart';
import 'package:flutter_hbb/desktop/pages/server_page.dart';
import 'package:flutter_hbb/desktop/screen/desktop_file_transfer_screen.dart';
import 'package:flutter_hbb/desktop/screen/desktop_view_camera_screen.dart';
import 'package:flutter_hbb/desktop/screen/desktop_port_forward_screen.dart';
import 'package:flutter_hbb/desktop/screen/desktop_remote_screen.dart';
import 'package:flutter_hbb/desktop/screen/desktop_terminal_screen.dart';
import 'package:flutter_hbb/desktop/widgets/refresh_wrapper.dart';
import 'package:flutter_hbb/models/state_model.dart';
import 'package:flutter_hbb/utils/multi_window_manager.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:get/get.dart';
import 'package:provider/provider.dart';
import 'package:window_manager/window_manager.dart';

import 'common.dart';
import 'consts.dart';
import 'mobile/pages/home_page.dart';
import 'mobile/pages/server_page.dart';
import 'models/platform_model.dart';
import 'utils/endpoints.dart';

import 'package:flutter_hbb/plugin/handlers.dart'
    if (dart.library.html) 'package:flutter_hbb/web/plugin/handlers.dart';

/// Basic window and launch properties.
int? kWindowId;
WindowType? kWindowType;
late List<String> kBootArgs;

Future<void> main(List<String> args) async {
  earlyAssert();
  WidgetsFlutterBinding.ensureInitialized();

  // 全平台启动诊断日志（桌面端白屏时无控制台，只能落盘）
  startupLog('进程启动 exe=${Platform.resolvedExecutable} args=$args');
  // 必须在任何网络请求之前完成：Win7/8/8.1 上 Flutter 3.24 引擎的 Dart TLS
  // 栈发起首个 HTTPS 请求会直接 native 崩溃(0x40000015)，try-catch 无法拦截，
  // "https 失败再回退"来不及生效。判定为旧系统后全程只走 http、永不重探 https。
  if (Platform.isWindows) {
    try {
      final build = getWindowsTargetBuildNumber();
      HttpFallback.legacyOs =
          getWindowsTarget(build).index < WindowsTarget.w10.index;
      startupLog('Windows legacyOs=${HttpFallback.legacyOs} build=$build');
    } catch (e) {
      startupLog('Windows 版本判定失败：$e');
    }
  }
  try {
    // 最早加载线路配置（缓存文件 → 内置 asset），后续 apiBase/首启写入依赖它
    await EndpointStore.init();
  } catch (e, s) {
    startupLog('EndpointStore.init 失败：$e\n$s');
    if (isDesktop) {
      _installErrorGuard();
      runDesktopFatalErrorApp(e, s, 'EndpointStore.init');
      return;
    }
    rethrow;
  }

  debugPrint("launch args: $args");
  kBootArgs = List.from(args);

  // 桌面/移动都安装：build 异常红屏 + 异步异常落盘，release 白屏时可定位
  _installErrorGuard();
  if (!isDesktop) {
    runMobileApp();
    return;
  }
  // main window
  if (args.isNotEmpty && args.first == 'multi_window') {
    kWindowId = int.parse(args[1]);
    stateGlobal.setWindowId(kWindowId!);
    if (!isMacOS) {
      WindowController.fromWindowId(kWindowId!).showTitleBar(false);
    }
    final argument = args[2].isEmpty
        ? <String, dynamic>{}
        : jsonDecode(args[2]) as Map<String, dynamic>;
    int type = argument['type'] ?? -1;
    // to-do: No need to parse window id ?
    // Because stateGlobal.windowId is a global value.
    argument['windowId'] = kWindowId;
    kWindowType = type.windowType;
    switch (kWindowType) {
      case WindowType.RemoteDesktop:
        desktopType = DesktopType.remote;
        runMultiWindow(
          argument,
          kAppTypeDesktopRemote,
        );
        break;
      case WindowType.FileTransfer:
        desktopType = DesktopType.fileTransfer;
        runMultiWindow(
          argument,
          kAppTypeDesktopFileTransfer,
        );
        break;
      case WindowType.ViewCamera:
        desktopType = DesktopType.viewCamera;
        runMultiWindow(
          argument,
          kAppTypeDesktopViewCamera,
        );
        break;
      case WindowType.PortForward:
        desktopType = DesktopType.portForward;
        runMultiWindow(
          argument,
          kAppTypeDesktopPortForward,
        );
        break;
      case WindowType.Terminal:
        desktopType = DesktopType.terminal;
        runMultiWindow(
          argument,
          kAppTypeDesktopTerminal,
        );
      default:
        break;
    }
  } else if (args.isNotEmpty && args.first == '--cm') {
    debugPrint("--cm started");
    desktopType = DesktopType.cm;
    await windowManager.ensureInitialized();
    runConnectionManagerScreen();
  } else if (args.contains('--install')) {
    runInstallPage();
  } else {
    desktopType = DesktopType.main;
    await windowManager.ensureInitialized();
    windowManager.setPreventClose(true);
    if (isMacOS) {
      disableWindowMovable(kWindowId);
    }
    runMainApp(true);
  }
}

/// 移动端启动的当前子步骤，仅用于 release 白屏时在错误页定位具体卡点。
String mobileStartupStep = '';

Future<void> initEnv(String appType) async {
  // global shared preference
  mobileStartupStep = 'platformFFI.init';
  await platformFFI.init(appType);
  // global FFI, use this **ONLY** for global configuration
  // for convenience, use global FFI on mobile platform
  // focus on multi-ffi on desktop first
  mobileStartupStep = 'initGlobalFFI';
  await initGlobalFFI();
  // 主窗口：先按线路文件对齐服务器配置（文件是唯一事实来源），
  // 再异步从 COS 更新；更新成功且地址有变化时会自动重新对齐并重连
  if (appType == kAppTypeMain) {
    // Socks5/Http(s) 代理入口已下线：普通用户易误填导致完全无法连接，
    // 启动时清空老版本残留的代理配置（空 proxy 会触发一次 mediator 重连）
    mobileStartupStep = 'clearLegacySocks';
    final legacySocks = await bind.mainGetSocks();
    if (legacySocks.isNotEmpty && legacySocks.first.trim().isNotEmpty) {
      await bind.mainSetSocks(proxy: '', username: '', password: '');
    }
    mobileStartupStep = 'EndpointStore.applyActive';
    await EndpointStore.applyActive();
    // 精简设置：UI 已移除的网络/备注开关在此强制对齐策略值，
    // 避免老版本持久化的用户选择残留继续生效
    mobileStartupStep = 'applyForcedClientOptions';
    await applyForcedClientOptions();
    // 每次启动都从 COS 同步一次最新线路（内容未变不写盘、不重连）
    mobileStartupStep = 'refreshFromCos';
    EndpointStore.refreshFromCos(reason: 'startup');
    // 低频保底同步；主力触发是"连不上信令服务器"（下面的状态监听），
    // 正常在线时不产生 COS 请求
    mobileStartupStep = 'startPeriodicSync';
    EndpointStore.startPeriodicSync();
    // connectStatus=-1（连续注册无响应）时立即回源 COS 拉最新线路，
    // 恢复在线后自动停止重试，全平台生效（桌面状态经 IPC 取自服务进程）
    mobileStartupStep = 'serverModel.addListener';
    gFFI.serverModel.addListener(() {
      EndpointStore.onConnectStatusChanged(gFFI.serverModel.connectStatus);
    });
  }
  // await Firebase.initializeApp();
  mobileStartupStep = 'registerEventHandler';
  _registerEventHandler();
  // Update the system theme.
  mobileStartupStep = 'updateSystemWindowTheme';
  updateSystemWindowTheme();
  mobileStartupStep = '';
}

/// 精简客户端设置：以下开关已从设置界面（移动端 + PC 端）移除，
/// 启动时强制写入策略值。仅删 UI 而不清理持久化配置，老用户残留选择
/// 仍会生效，所以必须在每次启动时主动对齐。
Future<void> applyForcedClientOptions() async {
  // —— 普通 option（桌面端经 IPC 写入独立服务进程）——
  // 使用 WebSocket：关闭
  await bind.mainSetOption(key: kOptionAllowWebSocket, value: 'N');
  // 允许回退到不安全的 TLS：启用（自建/反代线路需要）
  await bind.mainSetOption(key: kOptionAllowInsecureTLSFallback, value: 'Y');
  // 禁用 UDP：不启用（即保持 UDP 可用）
  await bind.mainSetOption(key: kOptionDisableUdp, value: 'N');

  // —— 本地 option ——
  // UDP 打洞：固定打开
  await bind.mainSetLocalOption(key: kOptionEnableUdpPunch, value: 'Y');
  // IPv6 P2P：固定打开
  await bind.mainSetLocalOption(key: kOptionEnableIpv6Punch, value: 'Y');
  // 连接结束时请求备注：关闭
  await bind.mainSetLocalOption(
      key: kOptionAllowAskForNoteAtEndOfConnection, value: 'N');

  // 硬件编码：默认启用（空值即默认开，这里补成显式 Y），
  // 用户显式关闭过（N）时尊重其选择；设置页的开关保留
  if (bind.mainGetOptionSync(key: kOptionEnableHwcodec).isEmpty) {
    await bind.mainSetOption(key: kOptionEnableHwcodec, value: 'Y');
  }
}

void runMainApp(bool startService) async {
  // 桌面 release 双击启动没有控制台，runApp 前任何异常都只表现为白屏。
  // 记录每一步，异常时渲染错误页并强制把窗口显示出来，另落盘日志。
  var step = 'initEnv';
  final watchdog = Timer(const Duration(seconds: 30), () {
    const msg = '启动超过 30 秒未进入界面（卡住，非崩溃）';
    startupLog('WATCHDOG: $msg，当前步骤：$step');
    runDesktopFatalErrorApp(msg, StackTrace.current, step);
  });
  try {
    // register uni links
    startupLog('runMainApp startService=$startService');
    await initEnv(kAppTypeMain);
    step = 'checkUpdate';
    startupLog(step);
    checkUpdate();
    // trigger connection status updater
    step = 'mainCheckConnectStatus';
    startupLog(step);
    await bind.mainCheckConnectStatus();
    if (startService) {
      step = 'serverModel.startService';
      startupLog(step);
      gFFI.serverModel.startService();
      step = 'pluginSyncUi';
      startupLog(step);
      bind.pluginSyncUi(syncTo: kAppTypeMain);
      step = 'pluginListReload';
      startupLog(step);
      bind.pluginListReload();
    }
    step = 'loadCache';
    startupLog(step);
    await Future.wait([gFFI.abModel.loadCache(), gFFI.groupModel.loadCache()]);
    step = 'refreshCurrentUser';
    gFFI.userModel.refreshCurrentUser();
    step = 'runApp';
    startupLog(step);
    runApp(App());

    bool? alwaysOnTop;
    if (isDesktop) {
      alwaysOnTop =
          bind.mainGetBuildinOption(key: "main-window-always-on-top") == 'Y';
    }

    // Set window option.
    WindowOptions windowOptions = getHiddenTitleBarWindowOptions(
        isMainWindow: true, alwaysOnTop: alwaysOnTop);
    step = 'waitUntilReadyToShow';
    startupLog(step);
    windowManager.waitUntilReadyToShow(windowOptions, () async {
      try {
        // Restore the location of the main window before window hide or show.
        step = 'restoreWindowPosition';
        await restoreWindowPosition(WindowType.Main);
        // Check the startup argument, if we successfully handle the argument, we keep the main window hidden.
        step = 'initUniLinks';
        final handledByUniLinks = await initUniLinks();
        debugPrint("handled by uni links: $handledByUniLinks");
        step = 'show/hide window';
        if (handledByUniLinks || handleUriLink(cmdArgs: kBootArgs)) {
          await windowManager.hide();
        } else {
          await windowManager.show();
          await windowManager.focus();
          // Move registration of active main window here to prevent from async visible check.
          rustDeskWinManager.registerActiveWindow(kWindowMainId);
        }
        await windowManager.setOpacity(1);
        await windowManager.setTitle(getWindowName());
        // Do not use `windowManager.setResizable()` here.
        setResizable(!bind.isIncomingOnly());
        watchdog.cancel();
        startupLog('主窗口启动完成');
      } catch (e, s) {
        watchdog.cancel();
        startupLog('FATAL(window callback) at $step: $e\n$s');
        runDesktopFatalErrorApp(e, s, step);
      }
    });
  } catch (e, s) {
    watchdog.cancel();
    startupLog('FATAL at $step: $e\n$s');
    runDesktopFatalErrorApp(e, s, step);
  }
}

void runMobileApp() {
  // 整个移动端启动链放进 zone：runApp 之前任何 await 抛异常在 release 下
  // 都表现为永久白屏，这里兜底渲染错误页，无 adb 也能截图定位。
  var step = 'main()';
  // runApp 是否已成功：启动链异常要红屏兜底；进入运行期后的单个异步
  // 回调异常只记录，不能冲掉整个 UI（否则一个无害异常也会强制用户重启）
  var appRunning = false;
  String stepLabel() =>
      mobileStartupStep.isEmpty ? step : '$step > $mobileStartupStep';
  final watchdog = Timer(const Duration(seconds: 15), () {
    runMobileFatalErrorApp(
      '启动超过 15 秒未进入界面（卡住，非崩溃），当前步骤：${stepLabel()}',
      StackTrace.current,
      stepLabel(),
    );
  });
  runZonedGuarded(() async {
    step = 'initEnv';
    await initEnv(kAppTypeMain);
    step = 'checkUpdate';
    checkUpdate();
    if (isAndroid) androidChannelInit();
    if (isAndroid) platformFFI.syncAndroidServiceAppDirConfigPath();
    step = 'draggablePositions';
    draggablePositions.load();
    step = 'loadCache';
    await Future.wait([gFFI.abModel.loadCache(), gFFI.groupModel.loadCache()]);
    step = 'refreshCurrentUser';
    gFFI.userModel.refreshCurrentUser();
    step = 'runApp';
    watchdog.cancel();
    runApp(App());
    appRunning = true;
    await initUniLinks();
  }, (error, stack) {
    watchdog.cancel();
    if (appRunning) {
      // 运行期：单个异步回调异常（如某个延迟回调竞态）不应冲掉整个界面，
      // 与 PlatformDispatcher.onError「仅记录不杀进程」策略一致
      startupLog('runtime zone error: $error\n$stack');
      debugPrint('runtime zone error: $error');
      return;
    }
    runMobileFatalErrorApp(error, stack, stepLabel());
  });
}

/// release 下把异常可视化/落盘（默认白屏无任何信息）。
void _installErrorGuard() {
  // build 阶段抛异常：release 默认是空白灰块，改成可见的错误文本
  ErrorWidget.builder = (FlutterErrorDetails details) => Material(
        color: const Color(0xFF2B0000),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(12),
            child: SelectableText(
              'Widget 异常：${details.exceptionAsString()}\n\n${details.stack}',
              style: const TextStyle(color: Colors.white, fontSize: 11),
            ),
          ),
        ),
      );
  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    startupLog('flutter-onError: ${details.exceptionAsString()}\n'
        '${details.stack}');
    debugPrint('flutter-onError: ${details.exceptionAsString()}');
  };
  // 未被 zone 捕获的异步异常，仅记录不杀进程
  PlatformDispatcher.instance.onError = (error, stack) {
    startupLog('platform-onError: $error\n$stack');
    debugPrint('platform-onError: $error\n$stack');
    return true;
  };
}

/// 启动诊断日志文件：Windows 下为 %TEMP%\starcare-startup.log。
/// GUI release 程序没有控制台，所有启动异常只能靠这个文件回溯。
String get startupLogPath =>
    '${Directory.systemTemp.path}${Platform.pathSeparator}starcare-startup.log';

void startupLog(String msg) {
  try {
    final line = '[${DateTime.now().toIso8601String()}] $msg';
    File(startupLogPath).writeAsStringSync('$line\n',
        mode: FileMode.append, flush: true);
  } catch (_) {}
}

/// 桌面端启动异常/卡死的最终兜底页面：渲染错误信息，并强制把窗口显示出来。
void runDesktopFatalErrorApp(Object error, StackTrace? stack, String step) {
  runApp(MaterialApp(
    debugShowCheckedModeBanner: false,
    home: Scaffold(
      backgroundColor: const Color(0xFF1B1B1B),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '启动异常（请截图反馈）',
                style: TextStyle(
                    color: Colors.redAccent,
                    fontSize: 18,
                    fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Text(
                '启动步骤：$step',
                style: const TextStyle(
                    color: Colors.amberAccent,
                    fontSize: 13,
                    fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              SelectableText(
                '日志文件：$startupLogPath',
                style:
                    const TextStyle(color: Colors.lightBlueAccent, fontSize: 12),
              ),
              const SizedBox(height: 12),
              SelectableText(
                '$error\n\n$stack',
                style: const TextStyle(
                    color: Colors.white70, fontSize: 12, height: 1.4),
              ),
            ],
          ),
        ),
      ),
    ),
  ));
  // 正常路径的 waitUntilReadyToShow 可能根本没走到，窗口停在隐藏/透明状态，
  // 这里独立确保错误页可见。
  windowManager.ensureInitialized();
  windowManager.waitUntilReadyToShow(
      const WindowOptions(center: true), () async {
    await windowManager.show();
    await windowManager.setOpacity(1);
    await windowManager.focus();
  });
}

/// runApp 前启动链异常的最终兜底页面。
void runMobileFatalErrorApp(Object error, StackTrace? stack, String step) {
  runApp(MaterialApp(
    debugShowCheckedModeBanner: false,
    home: Scaffold(
      backgroundColor: const Color(0xFF1B1B1B),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '启动异常（请截图反馈）',
                style: TextStyle(
                    color: Colors.redAccent,
                    fontSize: 18,
                    fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Text(
                '启动步骤：$step',
                style: const TextStyle(
                    color: Colors.amberAccent,
                    fontSize: 13,
                    fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 12),
              SelectableText(
                '$error\n\n$stack',
                style: const TextStyle(
                    color: Colors.white70, fontSize: 12, height: 1.4),
              ),
            ],
          ),
        ),
      ),
    ),
  ));
}

void runMultiWindow(
  Map<String, dynamic> argument,
  String appType,
) async {
  await initEnv(appType);
  final title = getWindowName();
  // set prevent close to true, we handle close event manually
  WindowController.fromWindowId(kWindowId!).setPreventClose(true);
  if (isMacOS) {
    disableWindowMovable(kWindowId);
  }
  late Widget widget;
  switch (appType) {
    case kAppTypeDesktopRemote:
      draggablePositions.load();
      widget = DesktopRemoteScreen(
        params: argument,
      );
      break;
    case kAppTypeDesktopFileTransfer:
      widget = DesktopFileTransferScreen(
        params: argument,
      );
      break;
    case kAppTypeDesktopViewCamera:
      draggablePositions.load();
      widget = DesktopViewCameraScreen(
        params: argument,
      );
      break;
    case kAppTypeDesktopPortForward:
      widget = DesktopPortForwardScreen(
        params: argument,
      );
      break;
    case kAppTypeDesktopTerminal:
      widget = DesktopTerminalScreen(
        params: argument,
      );
      break;
    default:
      // no such appType
      exit(0);
  }
  _runApp(
    title,
    widget,
    MyTheme.currentThemeMode(),
  );
  // we do not hide titlebar on win7 because of the frame overflow.
  if (kUseCompatibleUiMode) {
    WindowController.fromWindowId(kWindowId!).showTitleBar(true);
  }
  switch (appType) {
    case kAppTypeDesktopRemote:
      // If screen rect is set, the window will be moved to the target screen and then set fullscreen.
      if (argument['screen_rect'] == null) {
        // display can be used to control the offset of the window.
        await restoreWindowPosition(
          WindowType.RemoteDesktop,
          windowId: kWindowId!,
          peerId: argument['id'] as String?,
          display: argument['display'] as int?,
        );
      }
      break;
    case kAppTypeDesktopFileTransfer:
      await restoreWindowPosition(WindowType.FileTransfer,
          windowId: kWindowId!);
      break;
    case kAppTypeDesktopViewCamera:
      // If screen rect is set, the window will be moved to the target screen and then set fullscreen.
      if (argument['screen_rect'] == null) {
        // display can be used to control the offset of the window.
        await restoreWindowPosition(
          WindowType.ViewCamera,
          windowId: kWindowId!,
          peerId: argument['id'] as String?,
          // FIXME: fix display index.
          display: argument['display'] as int?,
        );
      }
      break;
    case kAppTypeDesktopPortForward:
      await restoreWindowPosition(WindowType.PortForward, windowId: kWindowId!);
      break;
    case kAppTypeDesktopTerminal:
      await restoreWindowPosition(WindowType.Terminal, windowId: kWindowId!);
      break;
    default:
      // no such appType
      exit(0);
  }
  // show window from hidden status
  WindowController.fromWindowId(kWindowId!).show();
}

void runConnectionManagerScreen() async {
  await initEnv(kAppTypeConnectionManager);
  _runApp(
    '',
    const DesktopServerPage(),
    MyTheme.currentThemeMode(),
  );
  final hide = await bind.cmGetConfig(name: "hide_cm") == 'true';
  gFFI.serverModel.hideCm = hide;
  if (hide) {
    await hideCmWindow(isStartup: true);
  } else {
    await showCmWindow(isStartup: true);
  }
  setResizable(false);
  // Start the uni links handler and redirect links to Native, not for Flutter.
  listenUniLinks(handleByFlutter: false);
}

bool _isCmReadyToShow = false;

showCmWindow({bool isStartup = false}) async {
  if (isStartup) {
    WindowOptions windowOptions = getHiddenTitleBarWindowOptions(
        size: kConnectionManagerWindowSizeClosedChat, alwaysOnTop: true);
    await windowManager.waitUntilReadyToShow(windowOptions, null);
    bind.mainHideDock();
    await Future.wait([
      windowManager.show(),
      windowManager.focus(),
      windowManager.setOpacity(1)
    ]);
    // ensure initial window size to be changed
    await windowManager.setSizeAlignment(
        kConnectionManagerWindowSizeClosedChat, Alignment.topRight);
    _isCmReadyToShow = true;
  } else if (_isCmReadyToShow) {
    if (await windowManager.getOpacity() != 1) {
      await windowManager.setOpacity(1);
      await windowManager.focus();
      await windowManager.minimize(); //needed
      await windowManager.setSizeAlignment(
          kConnectionManagerWindowSizeClosedChat, Alignment.topRight);
      windowOnTop(null);
    }
  }
}

hideCmWindow({bool isStartup = false}) async {
  if (isStartup) {
    WindowOptions windowOptions = getHiddenTitleBarWindowOptions(
        size: kConnectionManagerWindowSizeClosedChat);
    windowManager.setOpacity(0);
    await windowManager.waitUntilReadyToShow(windowOptions, null);
    bind.mainHideDock();
    await windowManager.minimize();
    await windowManager.hide();
    _isCmReadyToShow = true;
  } else if (_isCmReadyToShow) {
    if (await windowManager.getOpacity() != 0) {
      await windowManager.setOpacity(0);
      bind.mainHideDock();
      await windowManager.minimize();
      await windowManager.hide();
    }
  }
}

void _runApp(
  String title,
  Widget home,
  ThemeMode themeMode,
) {
  final botToastBuilder = BotToastInit();
  runApp(RefreshWrapper(
    builder: (context) => GetMaterialApp(
      navigatorKey: globalKey,
      debugShowCheckedModeBanner: false,
      title: title,
      theme: MyTheme.lightTheme,
      darkTheme: MyTheme.darkTheme,
      themeMode: themeMode,
      home: home,
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: supportedLocales,
      navigatorObservers: [
        // FirebaseAnalyticsObserver(analytics: analytics),
        BotToastNavigatorObserver(),
      ],
      builder: (context, child) {
        child = _keepScaleBuilder(context, child);
        child = botToastBuilder(context, child);
        return child;
      },
    ),
  ));
}

void runInstallPage() async {
  await windowManager.ensureInitialized();
  await initEnv(kAppTypeMain);
  _runApp('', const InstallPage(), MyTheme.currentThemeMode());
  WindowOptions windowOptions =
      getHiddenTitleBarWindowOptions(size: Size(800, 600), center: true);
  windowManager.waitUntilReadyToShow(windowOptions, () async {
    windowManager.show();
    windowManager.focus();
    windowManager.setOpacity(1);
    windowManager.setAlignment(Alignment.center); // ensure
  });
}

WindowOptions getHiddenTitleBarWindowOptions(
    {bool isMainWindow = false,
    Size? size,
    bool center = false,
    bool? alwaysOnTop}) {
  var defaultTitleBarStyle = TitleBarStyle.hidden;
  // we do not hide titlebar on win7 because of the frame overflow.
  if (kUseCompatibleUiMode) {
    defaultTitleBarStyle = TitleBarStyle.normal;
  }
  return WindowOptions(
    size: size,
    center: center,
    backgroundColor: (isMacOS && isMainWindow) ? null : Colors.transparent,
    skipTaskbar: false,
    titleBarStyle: defaultTitleBarStyle,
    alwaysOnTop: alwaysOnTop,
  );
}

class App extends StatefulWidget {
  @override
  State<App> createState() => _AppState();
}

class _AppState extends State<App> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.window.onPlatformBrightnessChanged = () {
      final userPreference = MyTheme.getThemeModePreference();
      if (userPreference != ThemeMode.system) return;
      WidgetsBinding.instance.handlePlatformBrightnessChanged();
      final systemIsDark =
          WidgetsBinding.instance.platformDispatcher.platformBrightness ==
              Brightness.dark;
      final ThemeMode to;
      if (systemIsDark) {
        to = ThemeMode.dark;
      } else {
        to = ThemeMode.light;
      }
      Get.changeThemeMode(to);
      // Synchronize the window theme of the system.
      updateSystemWindowTheme();
      if (desktopType == DesktopType.main) {
        bind.mainChangeTheme(dark: to.toShortString());
      }
    };
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => _updateOrientation());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeMetrics() {
    _updateOrientation();
  }

  void _updateOrientation() {
    if (isDesktop) return;

    // Don't use `MediaQuery.of(context).orientation` in `didChangeMetrics()`,
    // my test (Flutter 3.19.6, Android 14) is always the reverse value.
    // https://github.com/flutter/flutter/issues/60899
    // stateGlobal.isPortrait.value =
    //     MediaQuery.of(context).orientation == Orientation.portrait;

    final orientation = View.of(context).physicalSize.aspectRatio > 1
        ? Orientation.landscape
        : Orientation.portrait;
    stateGlobal.isPortrait.value = orientation == Orientation.portrait;
  }

  @override
  Widget build(BuildContext context) {
    // final analytics = FirebaseAnalytics.instance;
    final botToastBuilder = BotToastInit();
    return RefreshWrapper(builder: (context) {
      return MultiProvider(
        providers: [
          // global configuration
          // use session related FFI when in remote control or file transfer page
          ChangeNotifierProvider.value(value: gFFI.ffiModel),
          ChangeNotifierProvider.value(value: gFFI.imageModel),
          ChangeNotifierProvider.value(value: gFFI.cursorModel),
          ChangeNotifierProvider.value(value: gFFI.canvasModel),
          ChangeNotifierProvider.value(value: gFFI.peerTabModel),
        ],
        child: GetMaterialApp(
          navigatorKey: globalKey,
          debugShowCheckedModeBanner: false,
          title: isWeb
              ? '${bind.mainGetAppNameSync()} Web Client V2 (Preview)'
              : bind.mainGetAppNameSync(),
          theme: MyTheme.lightTheme,
          darkTheme: MyTheme.darkTheme,
          themeMode: MyTheme.currentThemeMode(),
          home: isDesktop
              ? const DesktopTabPage()
              : isWeb
                  ? WebHomePage()
                  : HomePage(),
          localizationsDelegates: const [
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: supportedLocales,
          navigatorObservers: [
            // FirebaseAnalyticsObserver(analytics: analytics),
            BotToastNavigatorObserver(),
          ],
          builder: isAndroid
              ? (context, child) => AccessibilityListener(
                    child: MediaQuery(
                      data: MediaQuery.of(context).copyWith(
                        textScaler: TextScaler.linear(1.0),
                      ),
                      child: child ?? Container(),
                    ),
                  )
              : (context, child) {
                  child = _keepScaleBuilder(context, child);
                  child = botToastBuilder(context, child);
                  if ((isDesktop && desktopType == DesktopType.main) ||
                      isWebDesktop) {
                    child = keyListenerBuilder(context, child);
                  }
                  if (isLinux) {
                    return buildVirtualWindowFrame(context, child);
                  } else {
                    return workaroundWindowBorder(context, child);
                  }
                },
        ),
      );
    });
  }
}

Widget _keepScaleBuilder(BuildContext context, Widget? child) {
  return MediaQuery(
    data: MediaQuery.of(context).copyWith(
      textScaler: TextScaler.linear(1.0),
    ),
    child: child ?? Container(),
  );
}

_registerEventHandler() {
  if (isDesktop && desktopType != DesktopType.main) {
    platformFFI.registerEventHandler('theme', 'theme', (evt) async {
      String? dark = evt['dark'];
      if (dark != null) {
        await MyTheme.changeDarkMode(MyTheme.themeModeFromString(dark));
      }
    });
    platformFFI.registerEventHandler('language', 'language', (_) async {
      reloadAllWindows();
    });
  }
  // Register native handlers.
  if (isDesktop) {
    platformFFI.registerEventHandler('native_ui', 'native_ui', (evt) async {
      NativeUiHandler.instance.onEvent(evt);
    });
  }
  // MUST_LOGIN：服务端(hbbs/hbbr)判定未登录时推送该事件，
  // 提示用户并直接跳到账户入口，方便立即登录。
  // 桌面端跳到 设置-账户 页签；移动端跳到底部导航的设置页。
  if (isDesktop) {
    if (desktopType == DesktopType.main) {
      platformFFI.registerEventHandler(
          'login_required', 'login_required', (evt) async {
        final msg = evt['msg'];
        if (msg is String && msg.isNotEmpty) {
          showToast(msg);
        }
        DesktopSettingPage.switch2page(SettingsTabKey.account);
      }, replace: true);
    }
  } else if (isMobile) {
    platformFFI.registerEventHandler(
        'login_required', 'login_required', (evt) async {
      final msg = evt['msg'];
      if (msg is String && msg.isNotEmpty) {
        showToast(msg);
      }
      HomePage.homeKey.currentState?.goToSettings();
    }, replace: true);
  }
}

Widget keyListenerBuilder(BuildContext context, Widget? child) {
  return RawKeyboardListener(
    focusNode: FocusNode(),
    child: child ?? Container(),
    onKey: (RawKeyEvent event) {
      if (event.logicalKey == LogicalKeyboardKey.shiftLeft) {
        if (event is RawKeyDownEvent) {
          gFFI.peerTabModel.setShiftDown(true);
        } else if (event is RawKeyUpEvent) {
          gFFI.peerTabModel.setShiftDown(false);
        }
      }
    },
  );
}
