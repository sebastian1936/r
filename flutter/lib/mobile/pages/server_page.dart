import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hbb/desktop/pages/desktop_home_page.dart';
import 'package:flutter_hbb/mobile/widgets/dialog.dart';
import 'package:flutter_hbb/models/chat_model.dart';
import 'package:get/get.dart';
import 'package:provider/provider.dart';

import '../../common.dart';
import '../../common/widgets/dialog.dart';
import '../../consts.dart';
import '../../models/platform_model.dart';
import '../../models/server_model.dart';
import 'home_page.dart';

class ServerPage extends StatefulWidget implements PageShape {
  @override
  final title = translate("Share screen");

  @override
  final icon = const Icon(Icons.mobile_screen_share);

  @override
  final appBarActions = (!bind.isDisableSettings() &&
          bind.mainGetBuildinOption(key: kOptionHideSecuritySetting) != 'Y')
      ? [_DropDownAction()]
      : [];

  ServerPage({Key? key}) : super(key: key);

  @override
  State<StatefulWidget> createState() => _ServerPageState();
}

class _DropDownAction extends StatelessWidget {
  _DropDownAction();

  // should only have one action
  final actions = [
    PopupMenuButton<String>(
        tooltip: "",
        icon: const Icon(Icons.more_vert),
        itemBuilder: (context) {
          listTile(String text, bool checked) {
            return ListTile(
                title: Text(translate(text)),
                trailing: Icon(
                  Icons.check,
                  color: checked ? null : Colors.transparent,
                ));
          }

          final approveMode = gFFI.serverModel.approveMode;
          final verificationMethod = gFFI.serverModel.verificationMethod;
          final showPasswordOption = approveMode != 'click';
          final isApproveModeFixed = isOptionFixed(kOptionApproveMode);
          final isNumericOneTimePasswordFixed =
              isOptionFixed(kOptionAllowNumericOneTimePassword);
          final isAllowNumericOneTimePassword =
              gFFI.serverModel.allowNumericOneTimePassword;
          return [
            PopupMenuItem(
              enabled: gFFI.serverModel.connectStatus > 0,
              value: "changeID",
              child: Text(translate("Change ID")),
            ),
            const PopupMenuDivider(),
            PopupMenuItem(
              value: 'AcceptSessionsViaPassword',
              child: listTile(
                  'Accept sessions via password', approveMode == 'password'),
              enabled: !isApproveModeFixed,
            ),
            PopupMenuItem(
              value: 'AcceptSessionsViaClick',
              child:
                  listTile('Accept sessions via click', approveMode == 'click'),
              enabled: !isApproveModeFixed,
            ),
            PopupMenuItem(
              value: "AcceptSessionsViaBoth",
              child: listTile("Accept sessions via both",
                  approveMode != 'password' && approveMode != 'click'),
              enabled: !isApproveModeFixed,
            ),
            if (showPasswordOption) const PopupMenuDivider(),
            if (showPasswordOption &&
                verificationMethod != kUseTemporaryPassword)
              PopupMenuItem(
                value: "setPermanentPassword",
                child: Text(translate("Set permanent password")),
              ),
            if (showPasswordOption &&
                verificationMethod != kUsePermanentPassword)
              PopupMenuItem(
                value: "setTemporaryPasswordLength",
                child: Text(translate("One-time password length")),
              ),
            if (showPasswordOption &&
                verificationMethod != kUsePermanentPassword)
              PopupMenuItem(
                value: "allowNumericOneTimePassword",
                child: listTile(translate("Numeric one-time password"),
                    isAllowNumericOneTimePassword),
                enabled: !isNumericOneTimePasswordFixed,
              ),
            if (showPasswordOption) const PopupMenuDivider(),
            if (showPasswordOption)
              PopupMenuItem(
                value: kUseTemporaryPassword,
                child: listTile('Use one-time password',
                    verificationMethod == kUseTemporaryPassword),
              ),
            if (showPasswordOption)
              PopupMenuItem(
                value: kUsePermanentPassword,
                child: listTile('Use permanent password',
                    verificationMethod == kUsePermanentPassword),
              ),
            if (showPasswordOption)
              PopupMenuItem(
                value: kUseBothPasswords,
                child: listTile(
                    'Use both passwords',
                    verificationMethod != kUseTemporaryPassword &&
                        verificationMethod != kUsePermanentPassword),
              ),
          ];
        },
        onSelected: (value) async {
          if (value == "changeID") {
            changeIdDialog();
          } else if (value == "setPermanentPassword") {
            setPasswordDialog();
          } else if (value == "setTemporaryPasswordLength") {
            setTemporaryPasswordLengthDialog(gFFI.dialogManager);
          } else if (value == "allowNumericOneTimePassword") {
            gFFI.serverModel.switchAllowNumericOneTimePassword();
            gFFI.serverModel.updatePasswordModel();
          } else if (value == kUsePermanentPassword ||
              value == kUseTemporaryPassword ||
              value == kUseBothPasswords) {
            callback() {
              bind.mainSetOption(key: kOptionVerificationMethod, value: value);
              gFFI.serverModel.updatePasswordModel();
            }

            if (value == kUsePermanentPassword &&
                (await bind.mainGetPermanentPassword()).isEmpty) {
              setPasswordDialog(notEmptyCallback: callback);
            } else {
              callback();
            }
          } else if (value.startsWith("AcceptSessionsVia")) {
            value = value.substring("AcceptSessionsVia".length);
            if (value == "Password") {
              gFFI.serverModel.setApproveMode('password');
            } else if (value == "Click") {
              gFFI.serverModel.setApproveMode('click');
            } else {
              gFFI.serverModel.setApproveMode(defaultOptionApproveMode);
            }
          }
        })
  ];

  @override
  Widget build(BuildContext context) {
    return actions[0];
  }
}

class _ServerPageState extends State<ServerPage> {
  Timer? _updateTimer;

  @override
  void initState() {
    super.initState();
    _updateTimer = periodic_immediate(const Duration(seconds: 3), () async {
      await gFFI.serverModel.fetchID();
    });
    gFFI.serverModel.checkAndroidPermission();
  }

  @override
  void dispose() {
    _updateTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    checkService();
    return ChangeNotifierProvider.value(
        value: gFFI.serverModel,
        child: Consumer<ServerModel>(
            builder: (context, serverModel, child) => SingleChildScrollView(
                  controller: gFFI.serverModel.controller,
                  child: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.start,
                      children: [
                        buildPresetPasswordWarningMobile(),
                        // Android 11+ 服务启停统一由"接受控制（谨防诈骗）"
                        // 总开关控制，不再显示"服务未运行/启动服务"卡片；
                        // 鸿蒙 2/3/4 虽报 API30+ 但无无线调试，与 Android 10
                        // 及以下一样保留传统手动启动入口
                        if (gFFI.serverModel.isStart)
                          ServerInfo()
                        else if (androidVersion < 30 || isHarmonyOs)
                          ServiceNotRunningNotification(),
                        const ConnectionManager(),
                        const PermissionChecker(),
                        SizedBox.fromSize(size: const Size(0, 15.0)),
                      ],
                    ),
                  ),
                )));
  }
}

void checkService() async {
  gFFI.invokeMethod("check_service");
  // for Android 10/11, request MANAGE_EXTERNAL_STORAGE permission from system setting page
  if (AndroidPermissionManager.isWaitingFile() && !gFFI.serverModel.fileOk) {
    AndroidPermissionManager.complete(kManageExternalStorage,
        await AndroidPermissionManager.check(kManageExternalStorage));
    debugPrint("file permission finished");
  }
}

class ServiceNotRunningNotification extends StatelessWidget {
  ServiceNotRunningNotification({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);

    return PaddingCard(
        title: translate("Service is not running"),
        titleIcon:
            const Icon(Icons.warning_amber_sharp, color: Colors.redAccent),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(translate("android_start_service_tip"),
                    style:
                        const TextStyle(fontSize: 12, color: MyTheme.darkGray))
                .marginOnly(bottom: 8),
            ElevatedButton.icon(
                icon: const Icon(Icons.play_arrow),
                onPressed: () {
                  if (gFFI.userModel.userName.value.isEmpty &&
                      bind.mainGetLocalOption(key: "show-scam-warning") !=
                          "N") {
                    showScamWarning(context, serverModel);
                  } else {
                    serverModel.toggleService();
                  }
                },
                label: Text(translate("Start service")))
          ],
        ));
  }
}

class ScamWarningDialog extends StatefulWidget {
  final ServerModel serverModel;

  ScamWarningDialog({required this.serverModel});

  @override
  ScamWarningDialogState createState() => ScamWarningDialogState();
}

class ScamWarningDialogState extends State<ScamWarningDialog> {
  int _countdown = bind.isCustomClient() ? 0 : 12;
  bool show_warning = false;
  late Timer _timer;
  late ServerModel _serverModel;

  @override
  void initState() {
    super.initState();
    _serverModel = widget.serverModel;
    startCountdown();
  }

  void startCountdown() {
    const oneSecond = Duration(seconds: 1);
    _timer = Timer.periodic(oneSecond, (timer) {
      setState(() {
        _countdown--;
        if (_countdown <= 0) {
          timer.cancel();
        }
      });
    });
  }

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isButtonLocked = _countdown > 0;

    return AlertDialog(
      content: ClipRRect(
        borderRadius: BorderRadius.circular(20.0),
        child: SingleChildScrollView(
          child: Container(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topRight,
                end: Alignment.bottomLeft,
                colors: [
                  Color(0xffe242bc),
                  Color(0xfff4727c),
                ],
              ),
            ),
            padding: EdgeInsets.all(25.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.warning_amber_sharp,
                      color: Colors.white,
                    ),
                    SizedBox(width: 10),
                    Text(
                      translate("Warning"),
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        fontSize: 20.0,
                      ),
                    ),
                  ],
                ),
                SizedBox(height: 20),
                Center(
                  child: Image.asset(
                    'assets/scam.png',
                    width: 180,
                  ),
                ),
                SizedBox(height: 18),
                Text(
                  translate("scam_title"),
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 22.0,
                  ),
                ),
                SizedBox(height: 18),
                Text(
                  "${translate("scam_text1")}\n\n${translate("scam_text2")}\n",
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 16.0,
                  ),
                ),
                Row(
                  children: <Widget>[
                    Checkbox(
                      value: show_warning,
                      onChanged: (value) {
                        setState(() {
                          show_warning = value!;
                        });
                      },
                    ),
                    Text(
                      translate("Don't show again"),
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        fontSize: 15.0,
                      ),
                    ),
                  ],
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Container(
                      constraints: BoxConstraints(maxWidth: 150),
                      child: ElevatedButton(
                        onPressed: isButtonLocked
                            ? null
                            : () {
                                Navigator.of(context).pop();
                                _serverModel.toggleService();
                                if (show_warning) {
                                  bind.mainSetLocalOption(
                                      key: "show-scam-warning", value: "N");
                                }
                              },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.blueAccent,
                        ),
                        child: Text(
                          isButtonLocked
                              ? "${translate("I Agree")} (${_countdown}s)"
                              : translate("I Agree"),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 13.0,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                    SizedBox(width: 15),
                    Container(
                      constraints: BoxConstraints(maxWidth: 150),
                      child: ElevatedButton(
                        onPressed: () {
                          Navigator.of(context).pop();
                        },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.blueAccent,
                        ),
                        child: Text(
                          translate("Decline"),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 13.0,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
      contentPadding: EdgeInsets.all(0.0),
    );
  }
}

class ServerInfo extends StatelessWidget {
  final model = gFFI.serverModel;
  final emptyController = TextEditingController(text: "-");

  ServerInfo({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);

    const Color colorPositive = Colors.green;
    const Color colorNegative = Colors.red;
    const double iconMarginRight = 15;
    const double iconSize = 24;
    const TextStyle textStyleHeading = TextStyle(
        fontSize: 16.0, fontWeight: FontWeight.bold, color: Colors.grey);
    const TextStyle textStyleValue =
        TextStyle(fontSize: 25.0, fontWeight: FontWeight.bold);

    void copyToClipboard(String value) {
      Clipboard.setData(ClipboardData(text: value));
      showToast(translate('Copied'));
    }

    Widget ConnectionStateNotification() {
      if (serverModel.connectStatus == -1) {
        return Row(children: [
          const Icon(Icons.warning_amber_sharp,
                  color: colorNegative, size: iconSize)
              .marginOnly(right: iconMarginRight),
          Expanded(child: Text(translate('not_ready_status')))
        ]);
      } else if (serverModel.connectStatus == 0) {
        return Row(children: [
          SizedBox(width: 20, height: 20, child: CircularProgressIndicator())
              .marginOnly(left: 4, right: iconMarginRight),
          Expanded(child: Text(translate('connecting_status')))
        ]);
      } else {
        return Row(children: [
          const Icon(Icons.check, color: colorPositive, size: iconSize)
              .marginOnly(right: iconMarginRight),
          Expanded(child: Text(translate('Ready')))
        ]);
      }
    }

    final showOneTime = serverModel.approveMode != 'click' &&
        serverModel.verificationMethod != kUsePermanentPassword;
    return PaddingCard(
        title: translate('Your Device'),
        child: Column(
          // ID
          children: [
            Row(children: [
              const Icon(Icons.perm_identity,
                      color: Colors.grey, size: iconSize)
                  .marginOnly(right: iconMarginRight),
              Text(
                translate('ID'),
                style: textStyleHeading,
              )
            ]),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text(
                model.serverId.value.text,
                style: textStyleValue,
              ),
              IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: Icon(Icons.copy_outlined),
                  onPressed: () {
                    copyToClipboard(model.serverId.value.text.trim());
                  })
            ]).marginOnly(left: 39, bottom: 10),
            // Password
            Row(children: [
              const Icon(Icons.lock_outline, color: Colors.grey, size: iconSize)
                  .marginOnly(right: iconMarginRight),
              Text(
                translate('One-time Password'),
                style: textStyleHeading,
              )
            ]),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text(
                !showOneTime ? '-' : model.serverPasswd.value.text,
                style: textStyleValue,
              ),
              !showOneTime
                  ? SizedBox.shrink()
                  : Row(children: [
                      IconButton(
                          visualDensity: VisualDensity.compact,
                          icon: const Icon(Icons.refresh),
                          onPressed: () => bind.mainUpdateTemporaryPassword()),
                      IconButton(
                          visualDensity: VisualDensity.compact,
                          icon: Icon(Icons.copy_outlined),
                          onPressed: () {
                            copyToClipboard(
                                model.serverPasswd.value.text.trim());
                          })
                    ])
            ]).marginOnly(left: 40, bottom: 15),
            ConnectionStateNotification()
          ],
        ));
  }
}

class PermissionChecker extends StatefulWidget {
  const PermissionChecker({Key? key}) : super(key: key);

  @override
  State<PermissionChecker> createState() => _PermissionCheckerState();
}

class _PermissionCheckerState extends State<PermissionChecker> {
  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);
    final hasAudioPermission = androidVersion >= 30;
    // Android 11+ 有无线调试：屏幕共享/输入控制/服务三者统一收进
    // "接受控制（谨防诈骗）"总开关（见 AdbAuthSection），不再单列；
    // Android 10 及以下、以及阉割了无线调试的鸿蒙 2/3/4 保留传统手动两行
    final unifiedControl = androidVersion >= 30 && !isHarmonyOs;
    return PaddingCard(
        // 安卓 11+ 卡片里只有"接受控制"总开关，不再显示"权限"标题
        title: unifiedControl ? null : translate("Permissions"),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (!unifiedControl && serverModel.mediaOk)
            ElevatedButton.icon(
                    style: ButtonStyle(
                        backgroundColor:
                            MaterialStateProperty.all(Colors.red)),
                    icon: const Icon(Icons.stop),
                    onPressed: serverModel.toggleService,
                    label: Text(translate("Stop service")))
                .marginOnly(bottom: 8),
          if (!unifiedControl)
            PermissionRow(
                translate("Screen Capture"),
                serverModel.mediaOk,
                !serverModel.mediaOk &&
                        gFFI.userModel.userName.value.isEmpty &&
                        bind.mainGetLocalOption(key: "show-scam-warning") !=
                            "N"
                    ? () => showScamWarning(context, serverModel)
                    : serverModel.toggleService),
          if (!unifiedControl)
            PermissionRow(translate("Input Control"), serverModel.inputOk,
                serverModel.toggleInput),
          // 文件传输/音频采集/剪贴板在总开关模式下随"接受控制"开关
          // 一起授权和启停，不再单列；传统模式（Android10 以下/鸿蒙）保留
          if (!unifiedControl)
            PermissionRow(translate("Transfer file"), serverModel.fileOk,
                serverModel.toggleFile),
          if (!unifiedControl && hasAudioPermission)
            PermissionRow(translate("Audio Capture"), serverModel.audioOk,
                serverModel.toggleAudio),
          if (!unifiedControl && !hasAudioPermission)
            Row(children: [
              Icon(Icons.info_outline).marginOnly(right: 15),
              Expanded(
                  child: Text(
                translate("android_version_audio_tip"),
                style: const TextStyle(color: MyTheme.darkGray),
              ))
            ]),
          if (!unifiedControl)
            PermissionRow(translate("Enable clipboard"),
                serverModel.clipboardOk, serverModel.toggleClipboard),
          const AdbAuthSection(),
        ]));
  }
}

/// ADB 一键授权（无线调试配对）入口：配对成功后获得 WRITE_SECURE_SETTINGS，
/// 系统解绑无障碍时可自动恢复（重启/升级 App 不丢失）
class AdbAuthSection extends StatefulWidget {
  const AdbAuthSection({Key? key}) : super(key: key);

  @override
  State<AdbAuthSection> createState() => _AdbAuthSectionState();
}

class _AdbAuthSectionState extends State<AdbAuthSection>
    with WidgetsBindingObserver {
  bool _supported = true;
  bool _granted = false;
  /// 最近一次状态查询的原始返回/异常（诊断弹窗用）
  Map<dynamic, dynamic> _lastSnap = const {};
  String _lastPackage = "";
  String _lastError = "";

  /// 总开关操作中的乐观状态：真实 mediaOk 未跟上时先显示用户所选值，
  /// 定时器到期或真实状态一致后清除（用户取消录屏框会弹回关）
  bool? _controlPending;
  bool _controlBusy = false;

  /// 防诈骗警告是否已勾选确认。未勾选不能进行权限检查/开启接受控制；
  /// 关闭控制永远允许，不受此状态限制
  bool _scamAcknowledged = false;

  /// "接受控制"总开关。
  /// 开：只做三件必要的事——确认配对记录、恢复无障碍、启动服务（系统
  ///    录屏确认框是录屏授权唯一无法绕过的手动确认）。全程不弹任何其他
  ///    授权框：通知/存储/麦克风等全部由配对前检查清单交代，可选能力按
  ///    清单已授权状态静默启用（没开麦克风=不传音频，没开存储=不传文件）。
  /// 关：停服务 + disableSelf 关无障碍（保留 ADB 配对，下次免配对）。
  void _toggleControl(bool on) async {
    if (_controlBusy) return;
    _controlBusy = true;
    try {
      if (on) {
        // 1) 拉最新授权状态，消除进页面首次 _refresh 尚未返回的竞态
        var paired = false;
        try {
          final dynamic st =
              await gFFI.invokeMethod("adb_auth_status", null);
          if (st is Map) {
            final dynamic snap = st["snap"];
            paired = snap is Map && snap["paired"] == true;
            if (mounted) {
              setState(() {
                _supported = (st["supported"] ?? false) as bool;
                _granted = (st["granted"] ?? false) as bool;
                _lastSnap = snap is Map ? snap : const {};
                _lastPackage = (st["package"] ?? "") as String;
              });
            }
          }
        } catch (_) {
          paired = _lastSnap["paired"] == true;
        }

        // 2) 从未配对：开关保持"关"，直接进配对引导。
        //    立刻释放忙碌锁（配对对话框是独立流程，不能卡住开关），
        //    也绝不能先弹存储/录音权限。
        if (!paired) {
          _controlBusy = false;
          _startPairing();
          return;
        }

        // 3) 已配对：先恢复无障碍。恢复过程最长约 30 秒（找调试服务+连接），
        //    显示加载框；失败时按原生返回的原因码给针对性指引——配对失效
        //    直接引导重新配对，其他情况引导处理无线调试后重试。恢复成功前
        //    不申请任何权限。
        var inputOk = false;
        String? failMode;
        while (mounted) {
          // 3.1) 显示不可取消的恢复中加载框（替代一闪而过的 toast）
          BuildContext? loadingCtx;
          showDialog(
            context: context,
            barrierDismissible: false,
            builder: (ctx) {
              loadingCtx = ctx;
              return WillPopScope(
                onWillPop: () async => false,
                child: const AlertDialog(
                  content: Row(children: [
                    CircularProgressIndicator(),
                    SizedBox(width: 16),
                    Expanded(
                      child: Text("正在恢复控制权限，请稍候…",
                          style: TextStyle(fontSize: 14)),
                    ),
                  ]),
                ),
              );
            },
          );
          try {
            final dynamic r =
                await gFFI.invokeMethod("adb_enable_input", null);
            inputOk = r is Map && r["ok"] == true;
            failMode = (r is Map && r["mode"] is String)
                ? r["mode"] as String
                : null;
          } catch (_) {
            inputOk = false;
          }
          // 无论页面是否还在，都关掉加载框（其 context 独立于 State.context）
          if (loadingCtx != null && loadingCtx!.mounted) {
            Navigator.of(loadingCtx!).pop();
          }
          if (inputOk || !mounted) break;

          // 本地直写失败/记录异常：直接走配对流程兜底
          if (failMode == "not_paired" || failMode == "local_failed") {
            _controlBusy = false;
            _startPairing();
            return;
          }

          final action = await showDialog<String>(
            context: context,
            barrierDismissible: false,
            builder: (_) =>
                _EnvCheckDialog(retryMode: true, failMode: failMode),
          );
          if (action == "repair") {
            // 配对失效：释放忙碌锁后进配对流程（独立对话框）
            _controlBusy = false;
            _startPairing();
            return;
          }
          if (action != "retry") {
            // 用户取消：开关保持关闭
            return;
          }
        }
        if (!mounted) return;

        // 4) 恢复成功：此时才乐观置开 → 按检查清单已授权状态静默启用
        //    文件/音频/剪贴板（不弹任何权限框：清单没开麦克风就不传音频，
        //    没开存储就不传文件）→ 启动服务。唯一会出现的系统框是录屏
        //    授权确认——那是录屏权限技术上必需的手动确认，无法绕过。
        setState(() => _controlPending = true);
        await gFFI.serverModel.applySharedCapabilitiesSilently();
        // 系统绑定 InputService 有 1~2s 延迟，等它就绪后再拉录屏框，
        // Android 11~13 才能赶上自动点"立即开始"
        await Future.delayed(const Duration(milliseconds: 1800));
        await gFFI.serverModel.startService();
        // 12s 后以真实录屏状态为准（用户在系统框点取消则开关弹回关）
        Future.delayed(const Duration(seconds: 12), () {
          if (mounted) setState(() => _controlPending = null);
        });
      } else {
        setState(() => _controlPending = false);
        await gFFI.serverModel.stopService();
        // 随总开关一并关闭文件传输/音频/剪贴板
        gFFI.serverModel.disableSharedCapabilities();
        try {
          await gFFI.invokeMethod("adb_disable_input", null);
        } catch (_) {}
        await Future.delayed(const Duration(milliseconds: 1500));
        await _refresh();
        if (mounted) {
          setState(() {
            _controlPending = null;
            // 关闭后防诈骗确认重置：下次开启必须重新勾选
            _scamAcknowledged = false;
          });
        }
      }
    } finally {
      _controlBusy = false;
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 用户在通知栏完成配对授权后回到 App，自动刷新授权状态并衔接录屏授权
    if (state == AppLifecycleState.resumed) _refresh(delayedRescan: true);
  }

  /// [delayedRescan]：首次查询若显示未授权，1.5 秒后补查一次。
  /// 系统绑定无障碍服务是异步的，配对刚成功/刚回前台时服务可能还没 bind 完，
  /// 会导致页面短暂（或一直）停留在「一键开启」
  _refresh({bool delayedRescan = false}) async {
    var grantedNow = false;
    try {
      final dynamic status = await gFFI.invokeMethod("adb_auth_status", null);
      if (!mounted) return;
      if (status is Map) {
        final capturePending = (status["capture_pending"] ?? false) as bool;
        grantedNow = (status["granted"] ?? false) as bool;
        final dynamic snap = status["snap"];
        setState(() {
          _supported = (status["supported"] ?? false) as bool;
          _granted = grantedNow;
          _lastSnap = snap is Map ? snap : const {};
          _lastPackage = (status["package"] ?? "") as String;
          _lastError = "";
        });
        // 回写共享状态：权限页据此在"未授权只显一键入口"与
        // "已授权显示运行时开关"两种布局间切换
        gFFI.serverModel.setAdbAuthState(
            supported: _supported, granted: grantedNow);
        // 通知栏配对成功后用户回到 App：按检查清单已授权状态静默启用
        // 文件/音频/剪贴板（不弹权限框），再自动拉起一次系统录屏授权框
        // （录屏是系统级授权，无法静默授予；已授权/缓存有效时不会弹窗）
        if (capturePending) {
          gFFI.serverModel.applySharedCapabilitiesSilently();
          gFFI.invokeMethod("adb_ensure_capture", null);
        }
      } else {
        // 通道返回了非 Map（防御：某些异常路径可能返回 bool）
        setState(() {
          _lastError = "通道返回异常: $status";
        });
        debugPrint("adb_auth_status unexpected result: $status");
      }
    } catch (e) {
      if (mounted) setState(() => _lastError = "$e");
      debugPrint("adb_auth_status failed: $e");
    }
    if (delayedRescan && !grantedNow && mounted) {
      await Future.delayed(const Duration(milliseconds: 1500));
      if (mounted) _refresh();
    }
  }

  /// 配对状态诊断：显示三重判据与系统名单原始内容，便于定位
  /// "已配对/系统无障碍开关已开但页面仍显示一键开启"
  void _showDiag() {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text("配对状态诊断", style: TextStyle(fontSize: 16)),
        content: SingleChildScrollView(
          child: SelectableText(
            "包名: $_lastPackage\n"
            "已完成过配对（持久记录）: ${_lastSnap["paired"]}\n"
            "WRITE_SECURE_SETTINGS 已授权: ${_lastSnap["wss"]}\n"
            "系统无障碍名单含本应用: ${_lastSnap["a11y_listed"]}\n"
            "无障碍服务运行中: ${_lastSnap["a11y_running"]}\n"
            "目标组件: ${_lastSnap["component"]}\n"
            "系统名单原始内容:\n${_lastSnap["a11y_list_raw"] ?? "(空/读不到)"}\n"
            "${_lastError.isNotEmpty ? "查询异常: $_lastError" : ""}",
            style: const TextStyle(fontSize: 12, height: 1.4),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () {
                Navigator.pop(ctx);
                _refresh();
              },
              child: const Text("重新检测")),
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text("关闭")),
        ],
      ),
    );
  }

  /// 一键开启：立即弹出环境清单窗（窗内转圈执行检查，点开关零等待）；
  /// 检查全部就绪时窗口自动关闭并直接启动通知配对；有未开项则停留
  /// 在清单引导页；仅在权限被拒/通知开关关闭/启动失败时才弹配对兜底对话框
  _startPairing() async {
    final go = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const _EnvCheckDialog(autoContinue: true),
    );
    if (go != true) return;
    // 先直达系统「无线调试」页（用户接着点「使用配对码配对设备」），
    // 再发配对通知——用户在设置页直接下拉通知栏输入配对码即可
    try {
      await gFFI.invokeMethod("adb_open_env", "wireless_debug");
    } catch (_) {}
    await _launchPairing();
  }

  /// 保活设置复查（已配对状态下也可随时打开清单调整自启动/电池等）。
  /// 关闭后若服务正在运行，按清单里新补开的文件/麦克风授权立即生效，
  /// 不用关了再开总开关
  _openEnvReview() async {
    await showDialog<bool>(
      context: context,
      builder: (_) => const _EnvCheckDialog(reviewMode: true),
    );
    if (mounted && gFFI.serverModel.mediaOk) {
      gFFI.serverModel.applySharedCapabilitiesSilently();
    }
  }

  Future<void> _launchPairing() async {
    try {
      await gFFI.invokeMethod("adb_start_pairing", null);
      showToast("配对通知已发出，请下拉通知栏输入配对码");
    } on PlatformException catch (e) {
      if (!mounted) return;
      showDialog<bool>(
        context: context,
        builder: (_) => _AdbPairDialog(
          initialError: e.message,
          initialErrorCode: e.code,
        ),
      ).then((ok) async {
        if (ok == true) await _onPairingSucceeded();
      });
    } catch (_) {
      if (!mounted) return;
      showDialog<bool>(
        context: context,
        builder: (_) => const _AdbPairDialog(),
      ).then((ok) async {
        if (ok == true) await _onPairingSucceeded();
      });
    }
  }

  /// 分屏/对话框内配对成功后的收尾：按检查清单已授权状态静默启用
  /// 文件/音频/剪贴板（不弹权限框），再刷状态
  /// （_refresh 内会消费 pending 并自动拉起录屏授权）
  _onPairingSucceeded() async {
    await gFFI.serverModel.applySharedCapabilitiesSilently();
    await _refresh(delayedRescan: true);
    checkService();
    gFFI.serverModel.checkAndroidPermission();
  }

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);
    // Android 10 及以下无无线调试，无法一键授权：只提供保活设置检查，
    // 屏幕录制/输入控制仍走上面对话框里的传统手动按钮
    if (!_supported) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Divider(height: 20),
          Row(children: [
            const Icon(Icons.fact_check_outlined, size: 22, color: Colors.grey)
                .marginOnly(right: 10),
            const Expanded(
                child: Text("保活设置", style: TextStyle(fontSize: 14))),
          ]),
          const Padding(
            padding: EdgeInsets.only(left: 32, top: 4),
            child: Text("检查自启动/通知/后台弹出/电池等设置，保证远程服务稳定运行",
                style: TextStyle(fontSize: 12, color: MyTheme.darkGray)),
          ),
          Container(
            margin: const EdgeInsets.only(left: 32, top: 8),
            child: OutlinedButton.icon(
                icon: const Icon(Icons.fact_check_outlined, size: 18),
                style: OutlinedButton.styleFrom(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 12),
                    minimumSize: const Size(0, 36),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap),
                onPressed: _openEnvReview,
                label: const Text("保活设置检查（自启动/通知/后台弹出/电池）",
                    style: TextStyle(fontSize: 13))),
          ),
        ],
      );
    }

    // Android 11+：接受控制总开关（统一无障碍 + 录屏授权 + 启动服务）
    final mediaOk = serverModel.mediaOk;
    if (_controlPending != null && _controlPending == mediaOk) {
      _controlPending = null;
    }
    final switchOn = _controlPending ?? mediaOk;
    // 锁屏后部分 ROM（MIUI）会销毁 Flutter 界面引擎而服务进程仍在，
    // 解锁回来内存勾选状态已重置但服务实际运行中——开关开着本身即代表
    // 已完成防诈骗确认，用派生值兜底，避免"开关开着却显示未勾选"
    final scamAck = _scamAcknowledged || switchOn;
    final pairedBefore = _lastSnap["paired"] == true;
    final String statusText = switchOn
        ? "正在接受远程控制，关闭后他人无法查看和操作本机"
        : (pairedBefore
            ? "授权已就绪，打开开关即可接受远程控制"
            : "首次开启按引导完成一次无线调试配对，仅需一次");
    final Color stateColor = switchOn ? Colors.green : Colors.grey;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // ① 状态头部：状态色底板 + 盾牌图标 + 标题/状态文案 + 总开关
        Container(
          padding:
              const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: stateColor.withOpacity(switchOn ? 0.08 : 0.06),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
                color: stateColor.withOpacity(switchOn ? 0.3 : 0.15)),
          ),
          child: Row(children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: stateColor,
                shape: BoxShape.circle,
              ),
              child: Icon(
                switchOn ? Icons.shield_rounded : Icons.shield_outlined,
                color: Colors.white,
                size: 22,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text("接受控制",
                      style: TextStyle(
                          fontSize: 15, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 3),
                  Text(
                    statusText,
                    style: TextStyle(
                        fontSize: 12,
                        color: switchOn ? Colors.green[700] : MyTheme.darkGray),
                  ),
                ],
              ),
            ),
            Switch(
              value: switchOn,
              activeColor: Colors.green,
              // 开启必须先勾选防诈骗确认；关闭永远允许（随时能停止受控）
              onChanged: _controlBusy
                  ? null
                  : (v) {
                      if (v == true && !scamAck) {
                        showToast("请先确认上方的警告");
                        return;
                      }
                      _toggleControl(v == true);
                    },
            ),
          ]),
        ),
        const SizedBox(height: 10),
        // ② 防诈骗红色警告（图标 + 文案一行，圆角红底）
        Container(
          width: double.maxFinite,
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: Colors.red.withOpacity(0.06),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: Colors.red.withOpacity(0.3)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.warning_amber_rounded,
                  color: Colors.red, size: 20),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    Text("谨防电信诈骗",
                        style: TextStyle(
                          fontSize: 13,
                          color: Colors.red,
                          fontWeight: FontWeight.bold,
                        )),
                    SizedBox(height: 2),
                    Text(
                      "公检法办案、投资理财、客户退款，要求您共享屏幕的，都是诈骗。",
                      style: TextStyle(fontSize: 12, height: 1.5, color: Colors.red),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 6),
        // ③ 知晓确认：勾选后才允许开启总开关；
        // 已在接受控制时保持勾选且不可取消
        InkWell(
          onTap: switchOn
              ? null
              : () => setState(
                  () => _scamAcknowledged = !_scamAcknowledged),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(children: [
              SizedBox(
                width: 22,
                height: 22,
                child: Checkbox(
                  value: scamAck,
                  onChanged: switchOn
                      ? null
                      : (v) => setState(
                          () => _scamAcknowledged = v ?? false),
                ),
              ),
              const SizedBox(width: 8),
              const Text("我已知晓，自愿开启",
                  style: TextStyle(fontSize: 13, color: Colors.black87)),
            ]),
          ),
        ),
        const Divider(height: 20),
        // ④ 辅助操作区：保活检查常驻；未配对时附带配对诊断入口
        Wrap(
          spacing: 8,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            OutlinedButton.icon(
                icon: const Icon(Icons.fact_check_outlined, size: 17),
                style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    minimumSize: const Size(0, 34),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap),
                onPressed: scamAck
                    ? _openEnvReview
                    : () => showToast("请先确认上方的警告"),
                label: const Text("权限检查（自启动/通知/后台/电池）",
                    style: TextStyle(fontSize: 12.5))),
            if (!switchOn && !pairedBefore)
              GestureDetector(
                onTap: _showDiag,
                child: const Text("配对没成功？点此诊断",
                    style: TextStyle(fontSize: 12, color: MyTheme.darkGray)),
              ),
          ],
        ),
      ],
    );
  }
}

/// 配对 / 保活前置环境清单。点「去开启」跳系统设置页，返回本页自动重新检查。
/// 必选项（开发者模式/USB 调试/无线调试/通知）未开启时不能直接开始配对；
/// 自启动/电池/悬浮窗为保活建议项；MIUI 无法读取的状态显示「需确认」。
class _EnvCheckDialog extends StatefulWidget {
  const _EnvCheckDialog(
      {Key? key,
      this.reviewMode = false,
      this.retryMode = false,
      this.failMode,
      this.autoContinue = false})
      : super(key: key);

  /// true=已配对后的复查入口（底部只显示「完成」，不显示开始配对）
  final bool reviewMode;

  /// true=已配对但恢复失败后的引导（底部：取消 / 我已打开重试）
  final bool retryMode;

  /// retryMode 时原生返回的具体失败原因码（adb_enable_input 的 mode）：
  /// wireless_debug_off / shell_no_service / shell_rejected /
  /// shell_put_denied / shell_verify_failed / shell_connect_error
  final String? failMode;

  /// true=配对前入口：检查在窗内执行（避免点开关后无反馈等待数秒），
  /// 首刷完成后若所有项目均已开启，窗口自动关闭并返回 true 直接配对
  final bool autoContinue;

  @override
  State<_EnvCheckDialog> createState() => _EnvCheckDialogState();
}

class _EnvCheckDialogState extends State<_EnvCheckDialog>
    with WidgetsBindingObserver {
  List<dynamic> _items = const [];
  bool _loading = true;
  String _brand = "other";
  String _brandLabel = "";

  // key → (标题, 操作指引, 是否必选项)。文案只给操作步骤，不讲原理
  static const Map<String, List<dynamic>> _meta = {
    "developer_options": [
      "开发者选项（已开启）",
      "没开：到 设置 → 关于手机，连续点「版本号」7 次",
      true,
    ],
    "adb_master": [
      "USB 调试",
      "开发者选项里打开「USB 调试」",
      true,
    ],
    "wireless_debug": [
      "无线调试",
      "开发者选项里打开「无线调试」总开关；配对后保持开启，输入控制可一键直开",
      true,
    ],
    "notification": [
      "通知权限",
      "允许通知：用于服务常驻运行提示；配对时配对码也在通知栏输入",
      true,
    ],
    "overlay": [
      "显示悬浮窗",
      "设置 → 应用管理 → 本应用 → 权限管理 → 显示悬浮窗，允许",
      false,
    ],
    "battery_optimization": [
      "电池策略：无限制",
      "设置 → 应用管理 → 本应用 → 省电策略，选「无限制」",
      false,
    ],
    "miui_autostart": [
      "允许自启动",
      "设置 → 应用管理 → 本应用 → 自启动，打开（锁屏被杀后能否自动恢复的关键）",
      false,
    ],
    "miui_background_start": [
      "后台弹出界面",
      "设置 → 应用管理 → 本应用 → 权限管理 → 后台弹出界面，允许",
      false,
    ],
    "miui_notif_style": [
      "通知栏样式：经典（小米/红米必设）",
      "设置 → 通知与控制中心 → 通知通知栏 → 通知栏样式，选「经典」"
          "（部分版本入口在该页右上角齿轮里）。默认样式会吞掉通知上的"
          "配对码输入框，不设置将无法在通知栏输入配对码",
      true,
    ],
    "miui_battery_unrestricted": [
      "省电策略：无限制（小米/红米必设）",
      "设置 → 应用设置 → 应用管理 → 本应用 → 省电策略，选「无限制」"
          "（旧版入口在设置 → 电量 → 应用配置 / 神隐模式）。"
          "不设置的话，锁屏一小时左右系统会强制断网休眠，被控端必然离线，"
          "亮屏后才恢复；自启动和电池无限制两项缺一不可",
      true,
    ],
    "samsung_sleep_apps": [
      "防止应用被休眠（三星必看）",
      "设置 → 电池和设备维护 → 电池 → 后台使用限制：关闭"
          "「让未使用的应用进入休眠」，并确认本应用不在「休眠的应用程序」"
          "列表中；再到 设置 → 应用 → 本应用 → 电池，选「不受限制」。"
          "否则长期不打开会被系统深度休眠，远程服务无法自动恢复",
      false,
    ],
    "file_storage": [
      "文件传输权限（可选）",
      "用途：授权后远程才能查看、收发这台手机上的文件。"
          "不授权不影响远程看屏幕和操作",
      false,
    ],
    "record_audio": [
      "远程声音权限（可选）",
      "用途：授权后远程能听到这台手机播放的声音（视频、音乐等外放）。"
          "安卓规定采集任何声音都要授予「麦克风/录音」权限，没有单独的"
          "内部声音权限；本应用只用它传输手机播放声。"
          "不授权不影响远程看屏幕和操作",
      false,
    ],
  };

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 从系统设置页返回时自动复查
    if (state == AppLifecycleState.resumed) _refresh();
  }

  _refresh() async {
    setState(() => _loading = true);
    try {
      // 是否显示开发者模式/USB调试/无线调试/通知样式由原生侧按机型决定
      // （Android11+ 非鸿蒙：任何场景都显示——已配对后无线调试也可能被
      // 系统关掉；Android10以下/鸿蒙：永不显示）。mode=review 只影响
      // 配对专用通知渠道是否检查，不影响这些项目的显示。
      final dynamic raw = await gFFI.invokeMethod(
          "adb_env_check",
          widget.reviewMode ? const {"mode": "review"} : null);
      // 新格式 {brand, brand_label, items:[...]}；兼容旧 List 格式
      final items = raw is Map && raw["items"] is List
          ? raw["items"] as List
          : (raw is List ? raw : const []);
      if (!mounted) return;
      setState(() {
        _items = items;
        if (raw is Map) {
          _brand = (raw["brand"] ?? "other") as String;
          _brandLabel = (raw["brand_label"] ?? "") as String;
        }
        _loading = false;
      });
      _maybeAutoContinue(items, allChecked: true);
    } catch (_) {
      if (mounted) setState(() => _loading = false);
      // 检查通道异常不阻断配对（与旧版预检查 catch 后直接配对一致）
      _maybeAutoContinue(const [], allChecked: false);
    }
  }

  /// 配对前入口（autoContinue）：首刷/复查结束后，全部项目已开启就
  /// 自动关窗继续配对，用户无感知等待；有任何未开/需确认项则停留展示。
  void _maybeAutoContinue(List<dynamic> items, {required bool allChecked}) {
    if (!widget.autoContinue || !mounted) return;
    final ready = !allChecked ||
        items.isNotEmpty &&
            items.every((e) => e is Map && e["status"] == "ok");
    if (ready) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.of(context).pop(true);
      });
    }
  }

  _openSetting(String key) async {
    // 两个可选功能权限：直接走 App 内授权请求
    // （录音弹系统授权框；所有文件访问跳系统授权页），完成后立即复查
    if (key == "record_audio" || key == "file_storage") {
      final perm = key == "record_audio" ? kRecordAudio : kManageExternalStorage;
      try {
        await AndroidPermissionManager.request(perm);
      } catch (_) {}
      _refresh();
      return;
    }
    try {
      final ok = await gFFI.invokeMethod("adb_open_env", key);
      if (ok != true) {
        showToast("未找到对应设置页，请按文字指引手动开启");
      } else {
        showToast("设置完成后返回本页，将自动重新检查");
      }
    } catch (_) {
      showToast("未找到对应设置页，请按文字指引手动开启");
    }
  }

  /// 恢复失败时按原生分级原因给对应操作提示（只讲操作，不讲技术原理）
  String get _retryHint {
    switch (widget.failMode) {
      case "shell_rejected":
        return "之前的配对已失效（系统更新、恢复出厂，或在无线调试里撤销过"
            "授权都会导致）。不需要折腾开关，点下方「重新配对」，按提示再"
            "配对一次即可（约 30 秒）";
      case "shell_no_service":
        return "「无线调试」开关虽然开着，但调试服务还没完全启动。请进 "
            "开发者选项 →「无线调试」页面停留 5～10 秒（不要马上退出），"
            "再点重试；仍失败就把无线调试关掉重新打开，再停留几秒后重试";
      case "shell_put_denied":
        return "小米/红米手机还需要打开开发者选项里的「USB 调试（安全设置）」"
            "（允许通过 USB 调试修改权限或模拟点击；部分版本要求登录小米账号"
            "并插入 SIM 卡后才能打开）。打开后点重试";
      case "shell_verify_failed":
        return "系统没有确认权限生效。请把开发者选项里的「无线调试」关掉再"
            "重新打开，等几秒后点重试；仍失败请重启一次手机再试";
      case "wireless_debug_off":
        return "没有检测到无线调试在运行（关过 WiFi 或重启手机后，系统会"
            "自动把它关掉）。请打开开发者选项里的「无线调试」；如果开关"
            "本来就是开的，点进「无线调试」页面停留 5～10 秒再点重试。"
            "配对只做第一次，这里不需要重新配对";
      default:
        return "连接无线调试服务失败。请确认开发者选项里「无线调试」是"
            "开着的，然后进「无线调试」页面停留 5～10 秒再点重试；仍失败"
            "就把它关掉重新打开。配对只做第一次，这里不需要重新配对";
    }
  }

  /// shell_rejected 时底部主按钮走重新配对，其余原因走重试
  bool get _isRepairNeeded => widget.failMode == "shell_rejected";

  String _statusOf(String key) {
    final it = _items.firstWhere(
      (e) => e is Map && e["key"] == key,
      orElse: () => null,
    );
    if (it == null) return "unknown";
    return (it["status"] ?? "unknown") as String;
  }

  Widget _statusIcon(String status) {
    switch (status) {
      case "ok":
        return const Icon(Icons.check_circle, color: Colors.green, size: 20);
      case "off":
        return const Icon(Icons.cancel, color: Colors.redAccent, size: 20);
      default:
        return const Icon(Icons.help_outline,
            color: Colors.orangeAccent, size: 20);
    }
  }

  Widget _buildRow(String key) {
    final meta = _meta[key];
    if (meta == null) return const SizedBox.shrink();
    final title = meta[0] as String;
    var desc = meta[1] as String;
    // 开发者选项入口各品牌路径不同，按识别到的品牌给准确路径
    if (key == "developer_options") {
      desc = const {
            "xiaomi": "没开：设置 → 我的设备 → 全部参数，连续点「MIUI 版本」7 次",
            "samsung": "没开：设置 → 关于手机 → 软件信息，连续点「编译编号」7 次",
            "huawei": "没开：设置 → 关于手机，连续点「版本号」7 次",
          }[_brand] ??
          "没开：设置 → 关于手机，连续点「版本号」7 次";
    }
    final required_ = meta[2] as bool;
    final status = _statusOf(key);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: _statusIcon(status),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text.rich(TextSpan(children: [
                  TextSpan(
                    text: title,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w500),
                  ),
                  if (required_)
                    const TextSpan(
                      text: " 必选",
                      style: TextStyle(fontSize: 11, color: Colors.redAccent),
                    ),
                ])),
                const SizedBox(height: 2),
                Text(desc,
                    style:
                        TextStyle(fontSize: 12, color: MyTheme.darkGray)),
                if (status == "unknown")
                  const Padding(
                    padding: EdgeInsets.only(top: 2),
                    child: Text("系统不允许读取此状态，请手动确认是否已开启",
                        style: TextStyle(fontSize: 11, color: Colors.orangeAccent)),
                  ),
              ],
            ),
          ),
          if (status != "ok")
            TextButton(
              style: TextButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 0),
                minimumSize: const Size(0, 32),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              onPressed: () => _openSetting(key),
              child: Text(status == "off" ? "去开启" : "去确认",
                  style: const TextStyle(fontSize: 13)),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final requiredOff = _items.any((e) =>
        e is Map &&
        e["status"] == "off" &&
        (_meta[e["key"]]?[2] == true));
    final titleText = widget.retryMode
        ? "开启前检查"
        : (widget.reviewMode ? "权限检查" : "配对前设置检查");
    return AlertDialog(
      title: Row(children: [
        const Icon(Icons.fact_check_outlined, size: 22),
        const SizedBox(width: 8),
        Text(titleText),
      ]),
      content: SizedBox(
        width: double.maxFinite,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_brandLabel.isNotEmpty)
                Container(
                  width: double.maxFinite,
                  margin: const EdgeInsets.only(bottom: 8),
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    "当前机型：$_brandLabel\n以下只显示与你这台手机相关的设置项",
                    style: const TextStyle(fontSize: 12, height: 1.5),
                  ),
                ),
              if (widget.retryMode)
                Container(
                  width: double.maxFinite,
                  margin: const EdgeInsets.only(bottom: 8),
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: (_isRepairNeeded
                            ? Colors.redAccent
                            : Colors.orangeAccent)
                        .withOpacity(0.1),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    _retryHint,
                    style: const TextStyle(fontSize: 12, height: 1.5),
                  ),
                ),
              if (widget.reviewMode)
                const Padding(
                  padding: EdgeInsets.only(bottom: 6),
                  child: Text("全部开启后，锁屏久了被系统杀掉也能自动恢复",
                      style: TextStyle(fontSize: 12, color: Colors.black54)),
                ),
              if (_loading)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const CircularProgressIndicator(),
                      const SizedBox(width: 16),
                      Text(widget.autoContinue ? "正在检查手机设置…" : "加载中…",
                          style: const TextStyle(fontSize: 14)),
                    ],
                  ),
                )
              else
                // 只渲染本机实际下发的检查项（按品牌不同而不同），
                // 不能遍历 _meta：否则别的品牌专属项（如小米上的三星项）
                // 也会被画出来
                ..._items
                    .whereType<Map>()
                    .map((e) => e["key"])
                    .whereType<String>()
                    .where((k) => _meta.containsKey(k))
                    .map(_buildRow)
                    .toList(),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Colors.orangeAccent.withOpacity(0.08),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Text(
                  "另外：最近任务列表里把本应用卡片下拉加锁，可进一步防止被清理",
                  style: TextStyle(fontSize: 12, color: Colors.black87),
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : _refresh,
          child: const Text("重新检查"),
        ),
        if (widget.retryMode) ...[
          TextButton(
            onPressed: () => Navigator.of(context).pop("cancel"),
            child: const Text("取消"),
          ),
          if (_isRepairNeeded)
            ElevatedButton(
              onPressed: () => Navigator.of(context).pop("repair"),
              child: const Text("重新配对"),
            )
          else
            ElevatedButton(
              onPressed: () => Navigator.of(context).pop("retry"),
              child: const Text("我已打开，重试"),
            ),
        ] else if (widget.reviewMode)
          ElevatedButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text("完成"),
          )
        else ...[
          TextButton(
            // 检测误报（尤其无线调试状态）时允许用户强行走配对
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text("跳过，直接配对"),
          ),
          ElevatedButton(
            onPressed: requiredOff
                ? null
                : () => Navigator.of(context).pop(true),
            child: Text(requiredOff ? "请先开启必选项" : "开始配对"),
          ),
        ],
      ],
    );
  }
}

/// 无线调试配对对话框。
/// 默认走通知栏配对（仿 Shizuku，无需分屏）；
/// 收不到通知时可切到分屏手动输入配对码（端口同样自动发现）。
class _AdbPairDialog extends StatefulWidget {
  const _AdbPairDialog({Key? key, this.initialError, this.initialErrorCode})
      : super(key: key);

  /// 一键开启直连失败时带入的错误（权限拒绝/通知开关关闭等），对话框直接展示
  final String? initialError;
  final String? initialErrorCode;

  @override
  State<_AdbPairDialog> createState() => _AdbPairDialogState();
}

class _AdbPairDialogState extends State<_AdbPairDialog> {
  final _codeController = TextEditingController();
  bool _busy = false;
  bool _manualMode = false;
  late String? _error = widget.initialError;
  late String? _errorCode = widget.initialErrorCode;

  @override
  void dispose() {
    _codeController.dispose();
    super.dispose();
  }

  /// 通知栏方式：启动前台配对服务，后续全部在通知里完成
  _startNotificationPairing() async {
    setState(() {
      _busy = true;
      _error = null;
      _errorCode = null;
    });
    try {
      await gFFI.invokeMethod("adb_start_pairing", null);
      if (!mounted) return;
      Navigator.of(context).pop(true);
      showToast("配对通知已发出，请下拉通知栏输入配对码");
    } on PlatformException catch (e) {
      setState(() {
        _busy = false;
        _error = e.message ?? "启动失败，请重试";
        _errorCode = e.code;
      });
    } catch (e) {
      setState(() {
        _busy = false;
        _error = "启动失败，请重试";
        _errorCode = null;
      });
    }
  }

  /// 跳系统通知设置（国产 ROM 通知被默认关闭时的自救入口）
  _openNotificationSettings() async {
    try {
      await gFFI.invokeMethod("adb_open_notification_settings", null);
    } catch (_) {}
  }

  /// 手动方式（分屏兜底）：只需 6 位配对码，端口同样自动发现
  _submitManual() async {
    final code = _codeController.text.trim();
    if (code.length != 6) {
      setState(() => _error = "请输入 6 位配对码");
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _errorCode = null;
    });
    try {
      final dynamic res = await gFFI.invokeMethod("adb_pair_and_grant", {"code": code});
      if (!mounted) return;
      final shellDirect = res is Map && res["shell_direct"] == true;
      Navigator.of(context).pop(true);
      showToast(shellDirect ? "授权成功，无障碍服务已开启" : "授权成功，权限自动恢复已开启");
    } on PlatformException catch (e) {
      setState(() {
        _busy = false;
        _error = e.message ?? "授权失败，请重试";
      });
    } catch (e) {
      setState(() {
        _busy = false;
        _error = "授权失败，请重试";
      });
    }
  }

  Widget _buildErrorBox() {
    if (_error == null) return const SizedBox.shrink();
    return Container(
      width: double.maxFinite,
      margin: const EdgeInsets.only(top: 12),
      constraints: const BoxConstraints(maxHeight: 220),
      decoration: BoxDecoration(
        color: Colors.red.withOpacity(0.08),
        border: Border.all(color: Colors.red.withOpacity(0.4)),
        borderRadius: BorderRadius.circular(6),
      ),
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.error_outline, size: 16, color: Colors.red),
            const SizedBox(width: 6),
            const Expanded(
              child: Text("失败详情（可长按选择文字）",
                  style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                      color: Colors.red)),
            ),
            InkWell(
              onTap: () {
                Clipboard.setData(ClipboardData(text: _error!));
                showToast("错误信息已复制");
              },
              child: const Padding(
                padding: EdgeInsets.all(4),
                child: Icon(Icons.copy, size: 15, color: Colors.red),
              ),
            )
          ]),
          const Divider(height: 14),
          Flexible(
            child: SingleChildScrollView(
              child: SelectableText(
                _error!,
                style: const TextStyle(fontSize: 12, height: 1.5),
              ),
            ),
          ),
          if (_errorCode == "NEED_PERMISSION" ||
              _errorCode == "NOTIFICATION_DISABLED")
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                style: TextButton.styleFrom(
                  foregroundColor: Colors.red,
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  minimumSize: const Size(0, 36),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                onPressed: _openNotificationSettings,
                icon: const Icon(Icons.notifications_active, size: 16),
                label: const Text("打开通知设置", style: TextStyle(fontSize: 12)),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildGuideContent() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          "1. 打开系统「开发者选项 → 无线调试」并开启\n"
          "2. 点「使用配对码配对设备」，停在显示 6 位码的页面\n"
          "3. 下拉通知栏，点本应用通知上的「输入配对码」\n"
          "4. 输入 6 位配对码并发送，等待授权成功",
          style: TextStyle(fontSize: 13, height: 1.8),
        ),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton(
            style: TextButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 6),
              minimumSize: const Size(0, 32),
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            onPressed: _busy ? null : _openNotificationSettings,
            child: const Text("通知栏没有通知？打开系统通知设置",
                style: TextStyle(fontSize: 12)),
          ),
        ),
        _buildErrorBox(),
        if (_busy) ...[
          const SizedBox(height: 12),
          Row(children: const [
            SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2)),
            SizedBox(width: 10),
            Expanded(child: Text("正在启动配对服务…", style: TextStyle(fontSize: 13)))
          ])
        ],
      ],
    );
  }

  Widget _buildManualContent() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          "1. 分屏：上屏打开系统「使用配对码配对设备」页\n"
          "2. 在下屏输入 6 位配对码，点确定",
          style: TextStyle(fontSize: 13, height: 1.8),
        ),
        const SizedBox(height: 14),
        TextField(
          controller: _codeController,
          enabled: !_busy,
          keyboardType: TextInputType.number,
          maxLength: 6,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          autofocus: true,
          decoration: const InputDecoration(
            isDense: true,
            labelText: "6 位配对码",
            counterText: "",
            border: OutlineInputBorder(),
            contentPadding: EdgeInsets.symmetric(horizontal: 10, vertical: 12),
          ),
          onSubmitted: (_) => _busy ? null : _submitManual(),
        ),
        _buildErrorBox(),
        if (_busy) ...[
          const SizedBox(height: 12),
          Row(children: const [
            SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2)),
            SizedBox(width: 10),
            Expanded(child: Text("正在配对并授权，请保持分屏…",
                style: TextStyle(fontSize: 13)))
          ])
        ]
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text("无线调试一键授权"),
      content: SizedBox(
        width: double.maxFinite,
        child: SingleChildScrollView(
          child: _manualMode ? _buildManualContent() : _buildGuideContent(),
        ),
      ),
      actions: [
        if (_manualMode)
          TextButton(
            onPressed: _busy
                ? null
                : () => setState(() {
                      _manualMode = false;
                      _error = null;
                      _errorCode = null;
                    }),
            child: const Text("返回通知方式"),
          )
        else
          TextButton(
            onPressed: _busy
                ? null
                : () => setState(() {
                      _manualMode = true;
                      _error = null;
                      _errorCode = null;
                    }),
            child: const Text("收不到通知？手动输入", style: TextStyle(fontSize: 12)),
          ),
        TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(false),
            child: Text(translate("Cancel"))),
        ElevatedButton(
          onPressed: _busy
              ? null
              : (_manualMode ? _submitManual : _startNotificationPairing),
          child: Text(_manualMode ? "确定" : "开始配对"),
        ),
      ],
    );
  }
}

class PermissionRow extends StatelessWidget {
  const PermissionRow(this.name, this.isOk, this.onPressed, {Key? key})
      : super(key: key);

  final String name;
  final bool isOk;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
        visualDensity: VisualDensity.compact,
        contentPadding: EdgeInsets.all(0),
        title: Text(name),
        value: isOk,
        onChanged: (bool value) {
          onPressed();
        });
  }
}

class ConnectionManager extends StatelessWidget {
  const ConnectionManager({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);
    return Column(
        children: serverModel.clients
            .map((client) => PaddingCard(
                title: translate(client.isFileTransfer
                    ? "Transfer file"
                    : "Share screen"),
                titleIcon: client.isFileTransfer
                    ? Icon(Icons.folder_outlined)
                    : Icon(Icons.mobile_screen_share),
                child: Column(children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Expanded(child: ClientInfo(client)),
                      Expanded(
                          flex: -1,
                          child: client.isFileTransfer || !client.authorized
                              ? const SizedBox.shrink()
                              : IconButton(
                                  onPressed: () {
                                    gFFI.chatModel.changeCurrentKey(
                                        MessageKey(client.peerId, client.id));
                                    final bar = navigationBarKey.currentWidget;
                                    if (bar != null) {
                                      bar as BottomNavigationBar;
                                      bar.onTap!(1);
                                    }
                                  },
                                  icon: unreadTopRightBuilder(
                                      client.unreadChatMessageCount)))
                    ],
                  ),
                  client.authorized
                      ? const SizedBox.shrink()
                      : Text(
                          translate("android_new_connection_tip"),
                          style: Theme.of(context).textTheme.bodyMedium,
                        ).marginOnly(bottom: 5),
                  client.authorized
                      ? _buildDisconnectButton(client)
                      : _buildNewConnectionHint(serverModel, client),
                  if (client.incomingVoiceCall && !client.inVoiceCall)
                    ..._buildNewVoiceCallHint(context, serverModel, client),
                ])))
            .toList());
  }

  Widget _buildDisconnectButton(Client client) {
    final disconnectButton = ElevatedButton.icon(
      style: ButtonStyle(backgroundColor: MaterialStatePropertyAll(Colors.red)),
      icon: const Icon(Icons.close),
      onPressed: () {
        bind.cmCloseConnection(connId: client.id);
        gFFI.invokeMethod("cancel_notification", client.id);
      },
      label: Text(translate("Disconnect")),
    );
    final buttons = [disconnectButton];
    if (client.inVoiceCall) {
      buttons.insert(
        0,
        ElevatedButton.icon(
          style: ButtonStyle(
              backgroundColor: MaterialStatePropertyAll(Colors.red)),
          icon: const Icon(Icons.phone),
          label: Text(translate("Stop")),
          onPressed: () {
            bind.cmCloseVoiceCall(id: client.id);
            gFFI.invokeMethod("cancel_notification", client.id);
          },
        ),
      );
    }

    if (buttons.length == 1) {
      return Container(
        alignment: Alignment.centerRight,
        child: disconnectButton,
      );
    } else {
      return Row(
        children: buttons,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
      );
    }
  }

  Widget _buildNewConnectionHint(ServerModel serverModel, Client client) {
    return Row(mainAxisAlignment: MainAxisAlignment.end, children: [
      TextButton(
          child: Text(translate("Dismiss")),
          onPressed: () {
            serverModel.sendLoginResponse(client, false);
          }).marginOnly(right: 15),
      if (serverModel.approveMode != 'password')
        ElevatedButton.icon(
            icon: const Icon(Icons.check),
            label: Text(translate("Accept")),
            onPressed: () {
              serverModel.sendLoginResponse(client, true);
            }),
    ]);
  }

  List<Widget> _buildNewVoiceCallHint(
      BuildContext context, ServerModel serverModel, Client client) {
    return [
      Text(
        translate("android_new_voice_call_tip"),
        style: Theme.of(context).textTheme.bodyMedium,
      ).marginOnly(bottom: 5),
      Row(mainAxisAlignment: MainAxisAlignment.end, children: [
        TextButton(
            child: Text(translate("Dismiss")),
            onPressed: () {
              serverModel.handleVoiceCall(client, false);
            }).marginOnly(right: 15),
        if (serverModel.approveMode != 'password')
          ElevatedButton.icon(
              icon: const Icon(Icons.check),
              label: Text(translate("Accept")),
              onPressed: () {
                serverModel.handleVoiceCall(client, true);
              }),
      ])
    ];
  }
}

class PaddingCard extends StatelessWidget {
  const PaddingCard({Key? key, required this.child, this.title, this.titleIcon})
      : super(key: key);

  final String? title;
  final Icon? titleIcon;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final children = [child];
    if (title != null) {
      children.insert(
          0,
          Padding(
              padding: const EdgeInsets.fromLTRB(0, 5, 0, 8),
              child: Row(
                children: [
                  titleIcon?.marginOnly(right: 10) ?? const SizedBox.shrink(),
                  Expanded(
                    child: Text(title!,
                        style: Theme.of(context)
                            .textTheme
                            .titleLarge
                            ?.merge(TextStyle(fontWeight: FontWeight.bold))),
                  )
                ],
              )));
    }
    return SizedBox(
        width: double.maxFinite,
        child: Card(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(13),
          ),
          margin: const EdgeInsets.fromLTRB(12.0, 10.0, 12.0, 0),
          child: Padding(
            padding:
                const EdgeInsets.symmetric(vertical: 15.0, horizontal: 20.0),
            child: Column(
              children: children,
            ),
          ),
        ));
  }
}

class ClientInfo extends StatelessWidget {
  final Client client;
  ClientInfo(this.client);

  @override
  Widget build(BuildContext context) {
    return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          Row(
            children: [
              Expanded(
                  flex: -1,
                  child: Padding(
                      padding: const EdgeInsets.only(right: 12),
                      child: CircleAvatar(
                          backgroundColor: str2color(
                              client.name,
                              Theme.of(context).brightness == Brightness.light
                                  ? 255
                                  : 150),
                          child: Text(client.name[0])))),
              Expanded(
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                    Text(client.name, style: const TextStyle(fontSize: 18)),
                    const SizedBox(width: 8),
                    Text(client.peerId, style: const TextStyle(fontSize: 10))
                  ]))
            ],
          ),
        ]));
  }
}

void androidChannelInit() {
  gFFI.setMethodCallHandler((method, arguments) {
    debugPrint("flutter got android msg,$method,$arguments");
    try {
      switch (method) {
        case "start_capture":
          {
            gFFI.dialogManager.dismissAll();
            gFFI.serverModel.updateClientState();
            break;
          }
        case "on_state_changed":
          {
            var name = arguments["name"] as String;
            var value = arguments["value"] as String == "true";
            debugPrint("from jvm:on_state_changed,$name:$value");
            gFFI.serverModel.changeStatue(name, value);
            break;
          }
        case "on_android_permission_result":
          {
            var type = arguments["type"] as String;
            var result = arguments["result"] as bool;
            AndroidPermissionManager.complete(type, result);
            break;
          }
        case "on_media_projection_canceled":
          {
            gFFI.serverModel.stopService();
            break;
          }
        case "msgbox":
          {
            var type = arguments["type"] as String;
            var title = arguments["title"] as String;
            var text = arguments["text"] as String;
            var link = (arguments["link"] ?? '') as String;
            msgBox(gFFI.sessionId, type, title, text, link, gFFI.dialogManager);
            break;
          }
        case "stop_service":
          {
            print(
                "stop_service by kotlin, isStart:${gFFI.serverModel.isStart}");
            if (gFFI.serverModel.isStart) {
              gFFI.serverModel.stopService();
            }
            break;
          }
      }
    } catch (e) {
      debugPrintStack(label: "MethodCallHandler err:$e");
    }
    return "";
  });
}

void showScamWarning(BuildContext context, ServerModel serverModel) {
  showDialog(
    context: context,
    builder: (BuildContext context) {
      return ScamWarningDialog(serverModel: serverModel);
    },
  );
}
