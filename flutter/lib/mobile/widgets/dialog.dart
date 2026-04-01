import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_hbb/common/widgets/setting_widgets.dart';
import 'package:flutter_hbb/common/widgets/toolbar.dart';
import 'package:get/get.dart';
import 'package:http/http.dart' as http;

import '../../common.dart';
import '../../models/platform_model.dart';


void _showSuccess() {
  showToast(translate("Successful"));
}

void _showError() {
  showToast(translate("Error"));
}

void setPermanentPasswordDialog(OverlayDialogManager dialogManager) async {
  final pw = await bind.mainGetPermanentPassword();
  final p0 = TextEditingController(text: pw);
  final p1 = TextEditingController(text: pw);
  var validateLength = false;
  var validateSame = false;
  dialogManager.show((setState, close, context) {
    submit() async {
      close();
      dialogManager.showLoading(translate("Waiting"));
      if (await gFFI.serverModel.setPermanentPassword(p0.text)) {
        dialogManager.dismissAll();
        _showSuccess();
      } else {
        dialogManager.dismissAll();
        _showError();
      }
    }

    return CustomAlertDialog(
      title: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.password_rounded, color: MyTheme.accent),
          Text(translate('Set your own password')).paddingOnly(left: 10),
        ],
      ),
      content: Form(
          autovalidateMode: AutovalidateMode.onUserInteraction,
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            TextFormField(
              autofocus: true,
              obscureText: true,
              keyboardType: TextInputType.visiblePassword,
              decoration: InputDecoration(
                labelText: translate('Password'),
              ),
              controller: p0,
              validator: (v) {
                if (v == null) return null;
                final val = v.trim().length > 5;
                if (validateLength != val) {
                  // use delay to make setState success
                  Future.delayed(Duration(microseconds: 1),
                      () => setState(() => validateLength = val));
                }
                return val
                    ? null
                    : translate('Too short, at least 6 characters.');
              },
            ).workaroundFreezeLinuxMint(),
            TextFormField(
              obscureText: true,
              keyboardType: TextInputType.visiblePassword,
              decoration: InputDecoration(
                labelText: translate('Confirmation'),
              ),
              controller: p1,
              validator: (v) {
                if (v == null) return null;
                final val = p0.text == v;
                if (validateSame != val) {
                  Future.delayed(Duration(microseconds: 1),
                      () => setState(() => validateSame = val));
                }
                return val
                    ? null
                    : translate('The confirmation is not identical.');
              },
            ).workaroundFreezeLinuxMint(),
          ])),
      onCancel: close,
      onSubmit: (validateLength && validateSame) ? submit : null,
      actions: [
        dialogButton(
          'Cancel',
          icon: Icon(Icons.close_rounded),
          onPressed: close,
          isOutline: true,
        ),
        dialogButton(
          'OK',
          icon: Icon(Icons.done_rounded),
          onPressed: (validateLength && validateSame) ? submit : null,
        ),
      ],
    );
  });
}

void setTemporaryPasswordLengthDialog(
    OverlayDialogManager dialogManager) async {
  List<String> lengths = ['6', '8', '10'];
  String length = await bind.mainGetOption(key: "temporary-password-length");
  var index = lengths.indexOf(length);
  if (index < 0) index = 0;
  length = lengths[index];
  dialogManager.show((setState, close, context) {
    setLength(newValue) {
      final oldValue = length;
      if (oldValue == newValue) return;
      setState(() {
        length = newValue;
      });
      bind.mainSetOption(key: "temporary-password-length", value: newValue);
      bind.mainUpdateTemporaryPassword();
      Future.delayed(Duration(milliseconds: 200), () {
        close();
        _showSuccess();
      });
    }

    return CustomAlertDialog(
      title: Text(translate("Set one-time password length")),
      content: Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: lengths
              .map(
                (value) => Row(
                  children: [
                    Text(value),
                    Radio(
                        value: value, groupValue: length, onChanged: setLength),
                  ],
                ),
              )
              .toList()),
    );
  }, backDismiss: true, clickMaskDismiss: true);
}

void _showManualAddressDialog(OverlayDialogManager dialogManager, void Function(VoidCallback) setState) {
  // 从本地读取上次手动输入的地址作为默认值
  String lastManualUrl = bind.mainGetOptionSync(key: 'last-manual-server-url');
  final TextEditingController controller = TextEditingController(text: lastManualUrl);

  dialogManager.show((dialogState, close, context) {
    return CustomAlertDialog(
      title: const Text("手动输入节点地址"),
      content: TextField(
        controller: controller,
        autofocus: true,
        decoration: const InputDecoration(
          hintText: "https://example.com/config",
          helperText: "请输入有效的配置订阅地址",
        ),
      ),
      actions: [
        TextButton(onPressed: () => close(), child: Text(translate('Cancel'))),
        TextButton(
          onPressed: () async {
            String url = controller.text.trim();
            if (url.isNotEmpty) {
              // 持久化保存这个地址
              await bind.mainSetOption(key: 'last-manual-server-url', value: url);
              close();
              showServerSettings(dialogManager, setState, manualUrl: url);
            }
          },
          child: Text(translate('OK')),
        ),
      ],
    );
  });
}


Future<void> showServerSettings(OverlayDialogManager dialogManager,
    void Function(VoidCallback) setState) async {
  
  // 1. 先从本地读取上一次输入成功的 URL（如果有）
  String lastUrl = bind.mainGetOptionSync(key: 'last-manual-server-url');
  TextEditingController controller = TextEditingController(text: lastUrl);

  // 2. 直接弹出输入对话框
  dialogManager.show((dialogState, close, context) {
    return CustomAlertDialog(
      title: Text(translate('设置服务器地址')),
      content: TextField(
        controller: controller,
        decoration: InputDecoration(
          hintText: "请输入服务器配置 URL",
          helperText: "例如: https://your-config-url.com",
        ),
      ),
      actions: [
        TextButton(onPressed: () => close(), child: Text(translate('Cancel'))),
        ElevatedButton(
          onPressed: () async {
            String inputUrl = controller.text.trim();
            if (inputUrl.isEmpty) {
              showToast("请输入地址");
              return;
            }

            // 执行核心逻辑：获取并应用配置
            bool success = await _fetchAndApplyConfig(inputUrl, dialogManager, setState);
            
            if (success) {
              // 只有成功获取并解析后，才持久化这个 URL
              await bind.mainSetOption(key: 'last-manual-server-url', value: inputUrl);
              close(); // 成功后关闭弹窗
            }
          },
          child: Text(translate('Confirm')),
        ),
      ],
    );
  });
}

// 提取出来的私有方法：负责网络请求和配置应用
Future<bool> _fetchAndApplyConfig(String url, OverlayDialogManager dialogManager, Function setState) async {
  try {
    showToast("正在获取配置...");
    final response = await http.get(Uri.parse(url)).timeout(const Duration(seconds: 10));

    if (response.statusCode != 200) {
      showToast("获取失败 (Status: ${response.statusCode})");
      return false;
    }

    // 解密与解析逻辑（保留你原有的逻辑）
    String rawBody = utf8.decode(response.bodyBytes);
    String decryptedBody = rawBody.replaceAll('999', '.').replaceAll('333', ':');
    Map<String, dynamic> serverMap = jsonDecode(decryptedBody);

    // 如果只有一个节点，直接应用；如果有多个，可以再弹一次列表（或者默认取第一个）
    if (serverMap.isEmpty) {
      showToast("配置内容为空");
      return false;
    }

    // 这里为了简化，默认取第一个节点配置应用
    String firstKey = serverMap.keys.first;
    String rawValue = serverMap[firstKey];
    List<String> parts = rawValue.split('|');

    final config = ServerConfig(
      idServer: parts[0],
      relayServer: parts[1],
      apiServer: '', // 建议也改成可配置
      key: 'k3lsu+CTLs4OhFpq5Lh38Uvo2m8Cyb1jLz6gTCAnyCw=',
    );

    bool success = await setServerConfig(null, null, config);
    if (success) {
      showToast("配置已生效: $firstKey");
      setState(() {});
      return true;
    }
    return false;
  } catch (e) {
    showToast("网络或解析异常");
    debugPrint("Error: $e");
    return false;
  }
}



/// 新增：弹出线路说明对话框
Future<void> showIntroDialog(OverlayDialogManager dialogManager) async {
  final completer = Completer<void>();
  dialogManager.show((_, close, context) {
    return CustomAlertDialog(
      title: Text('线路说明'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            '普通版无限制，不保证流畅度\n专业版保证流畅度，限制5个设备，不能传100M以上文件',
            style: TextStyle(fontSize: 15),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            close();
            completer.complete();
          },
          child: Text('继续'),
        ),
      ],
    );
  });
  return completer.future;
}


void showServerSettingsWithValue(
    ServerConfig serverConfig,
    OverlayDialogManager dialogManager,
    void Function(VoidCallback)? upSetState) async {
  var isInProgress = false;
  final idCtrl = TextEditingController(text: serverConfig.idServer);
  final relayCtrl = TextEditingController(text: serverConfig.relayServer);
  final apiCtrl = TextEditingController(text: serverConfig.apiServer);
  final keyCtrl = TextEditingController(text: serverConfig.key);

  RxString idServerMsg = ''.obs;
  RxString relayServerMsg = ''.obs;
  RxString apiServerMsg = ''.obs;

  final controllers = [idCtrl, relayCtrl, apiCtrl, keyCtrl];
  final errMsgs = [
    idServerMsg,
    relayServerMsg,
    apiServerMsg,
  ];

  dialogManager.show((setState, close, context) {
    Future<bool> submit() async {
      setState(() {
        isInProgress = true;
      });
      bool ret = await setServerConfig(
          null,
          errMsgs,
          ServerConfig(
              idServer: idCtrl.text.trim(),
              relayServer: relayCtrl.text.trim(),
              apiServer: apiCtrl.text.trim(),
              key: keyCtrl.text.trim()));
      setState(() {
        isInProgress = false;
      });
      return ret;
    }

    Widget buildField(
        String label, TextEditingController controller, String errorMsg,
        {String? Function(String?)? validator, bool autofocus = false}) {
      if (isDesktop || isWeb) {
        return Row(
          children: [
            SizedBox(
              width: 120,
              child: Text(label),
            ),
            SizedBox(width: 8),
            Expanded(
              child: TextFormField(
                controller: controller,
                decoration: InputDecoration(
                  errorText: errorMsg.isEmpty ? null : errorMsg,
                  contentPadding:
                      EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                ),
                validator: validator,
                autofocus: autofocus,
              ).workaroundFreezeLinuxMint(),
            ),
          ],
        );
      }

      return TextFormField(
        controller: controller,
        decoration: InputDecoration(
          labelText: label,
          errorText: errorMsg.isEmpty ? null : errorMsg,
        ),
        validator: validator,
      ).workaroundFreezeLinuxMint();
    }

    return CustomAlertDialog(
      title: Row(
        children: [
          Expanded(child: Text(translate('ID/Relay Server'))),
          ...ServerConfigImportExportWidgets(controllers, errMsgs),
        ],
      ),
      content: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 500),
        child: Form(
          child: Obx(() => Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  buildField(translate('ID Server'), idCtrl, idServerMsg.value,
                      autofocus: true),
                  SizedBox(height: 8),
                  if (!isIOS && !isWeb) ...[
                    buildField(translate('Relay Server'), relayCtrl,
                        relayServerMsg.value),
                    SizedBox(height: 8),
                  ],
                  buildField(
                    translate('API Server'),
                    apiCtrl,
                    apiServerMsg.value,
                    validator: (v) {
                      if (v != null && v.isNotEmpty) {
                        if (!(v.startsWith('http://') ||
                            v.startsWith("https://"))) {
                          return translate("invalid_http");
                        }
                      }
                      return null;
                    },
                  ),
                  SizedBox(height: 8),
                  buildField('Key', keyCtrl, ''),
                  if (isInProgress)
                    Padding(
                      padding: EdgeInsets.only(top: 8),
                      child: LinearProgressIndicator(),
                    ),
                ],
              )),
        ),
      ),
      actions: [
        dialogButton('Cancel', onPressed: () {
          close();
        }, isOutline: true),
        dialogButton(
          'OK',
          onPressed: () async {
            if (await submit()) {
              close();
              showToast(translate('Successful'));
              upSetState?.call(() {});
            } else {
              showToast(translate('Failed'));
            }
          },
        ),
      ],
    );
  });
}

void setPrivacyModeDialog(
  OverlayDialogManager dialogManager,
  List<TToggleMenu> privacyModeList,
  RxString privacyModeState,
) async {
  dialogManager.dismissAll();
  dialogManager.show((setState, close, context) {
    return CustomAlertDialog(
      title: Text(translate('Privacy mode')),
      content: Column(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: privacyModeList
              .map((value) => CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                    title: value.child,
                    value: value.value,
                    onChanged: value.onChanged,
                  ))
              .toList()),
    );
  }, backDismiss: true, clickMaskDismiss: true);
}
