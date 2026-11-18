# 交接文档：锁屏后录屏权限自愈修复（无密码锁屏设备）

> 状态：待开发
> 分支：`1.4.4.1`
> 涉及模块：Android 被控端（Kotlin），不涉及 Rust/Flutter
> 编写日期：2026-11-18
> 证据来源：vivo PA2573 手动诊断日志包 `rustdesk_manual_logs_1958618129_..._manual_922250cd`（2026-11-17 晚间会话）

---

## 1. 问题现象

设备锁屏一段时间后，主控连入被控端：

1. 屏幕能被点亮（wakelock 生效），但**画面冻结/黑屏**，录屏会话已被系统收回；
2. 用户确认设备**没有设置锁屏密码**（滑动锁屏），但在主控端**上滑无法滑开锁屏**；
3. 偶尔滑开后依然没有画面，且很快重新上锁；
4. 只有人工在物理设备上打开 App（App 进前台）后，录屏授权才能自动恢复。

## 2. 设备与版本

| 项 | 值 |
|---|---|
| 机型 | vivo PA2573（OriginOS，MTK 平台） |
| 系统 | Android 16（SDK 36） |
| 包 | host 包 `com.starcaretech.a`，versionCode 2062 |
| 构建点 | commit `fd4b5d6`（2026-11-10） |
| 当前 HEAD | 该提交之后 `MainService.kt` / `PermissionRequestTransparentActivity.kt` / `InputService.kt` **无逻辑改动**（仅 NetworkTypeMonitor 退役），本文行号对当前代码有效 |

## 3. 根因分析（两层，缺一不可）

### 3.1 平台硬边界（不可绕过，只能适配）

1. **Android 14+ 起 MediaProjection 授权 Intent 单次有效**。会话 `onStop` 后再用缓存 token 调 `getMediaProjection()` 必抛 `SecurityException`/`IllegalStateException`。
   - 代码：`MainService.applyProjection()`（`MainService.kt` 约 1313-1352 行），失败后 `tryRestoreMediaProjection()` 清 token 返回 false。
   - 日志实证：每次连接都出现 `缓存授权已失效`，`startCapture fail,mediaProjection is null` 反复出现。
2. **系统录屏确认框只能由可见前台 Activity 发起**，且 `setShowWhenLocked` 不会传递给 `startActivityForResult` 拉起的系统确认 Activity（HyperOS/OriginOS Android16 实测，代码注释已记录于 `PermissionRequestTransparentActivity.kt` 128-134 行）。锁屏之上无框可点，无障碍点击器无目标。
3. **无密码（滑动/无）锁屏可以用官方 `KeyguardManager.requestDismissKeyguard()` 免凭据代码解散**；有密码锁屏回调立即取消，这是系统安全边界，不允许绕过。

### 3.2 本项目实现缺口（本次要修的）

1. **前台门控挡死被控场景**：`MainService.promptProjectionRecovery()` 约 1579 行，三条件 `亮屏 && 解锁 && isAppForeground` 全满足才直接 startActivity。被控时 App 恒在后台，前台恒为 false，只发全屏意图通知（FSI）。
   - 铁证日志（21:00:51，设备已亮屏且已解锁，仅因后台被挡）：
     `非可交互前台（亮屏=true 解锁=true 前台=false），仅通知引导，不自动弹录屏框`
2. **FSI 拉起的透明授权页在锁屏上 2 秒内静默死亡**。日志统计：透明页共创建 20 次，其中 12 次成功武装点击闸门（全部发生在 App 前台），**8 次空转（全部在锁屏/后台）——无 `闸门已武装`、无确认框、无 onActivityResult**。`FOCUS_WAIT_FALLBACK_MS`（2s）兜底 Runnable 的日志一次都没出现，说明页在回调执行前已被 onDestroy。
   - 伴随现象：每次透明页 resumed 后 30ms 内 MainActivity 同 task 被拉起并 resumed，疑似同 taskAffinity 抢焦点/被 keyguard 清理连带销毁。
3. **唯一会解散锁屏的 `requestDismissKeyguard` 被锁在开机分支**：`PermissionRequestTransparentActivity.kt` 137-164 行，条件含 `bootAutoConsent`，仅开机 150s 武装窗口可达；FSI/连接触发的入口走的是 170 行 `scheduleCaptureAfterFocus()` 旧路径，锁屏上拿不到窗口焦点必死。
4. **"滑不开"的真相**：日志中 `keyguard locked->unlocked` 仅 2 次（20:50:35、20:55:28），都发生在 MainActivity resumed 后 1-2 秒，是**系统对无密码锁屏的普通 Activity 自动解散**，没有一次是远程手势滑开的。且录屏已死、主控画面冻结，用户是在盲滑，看不到上滑热区；`dispatchGesture` 分段拼接的离散 Path 也难满足 keyguard 手势识别门槛。
5. `WakeScreenActivity` 职责明确为"只点亮不解锁"（类注释），本次不动它。

### 3.3 已验证可行的成功路径（修复目标）

21:04:50 用户手动打开 App 后：
`透明页 created → 闸门已武装 → 扫描#2 命中"继续"自动点击（468ms）→ resultCode=-1 → ACT_INIT → 自动续上屏幕采集`。
说明「解散锁屏 → 真前台 → 系统确认框 → 无障碍自动点击」这条链的后半段完全可用，缺的只是把锁屏解散这一步接到 FSI 入口上。

## 4. 现有代码地图（改之前必读）

| 文件 | 关键点 |
|---|---|
| `flutter/android/app/src/main/kotlin/com/starcaretech/a/PermissionRequestTransparentActivity.kt` | 透明授权页全部逻辑；`onCreate`（84 行）、锁屏解散分支（137-168 行）、`scheduleCaptureAfterFocus`（187 行）、`launchCaptureIntent`（199 行，内调 `InputService.beginConsentWait()`）、`onDestroy` 闸门兜底（261 行） |
| `flutter/android/app/src/main/kotlin/com/starcaretech/a/MainService.kt` | 前台门控 `promptProjectionRecovery`（1536 行起，关键判定 1579 行）；FSI 通知 `postProjectionRecoveryNotification`（1676 行，`setFullScreenIntent`）；安全锁屏判定 `isDeviceSecureLocked`（1645 行，**当前 private 实例方法**）；缓存恢复 `tryRestoreMediaProjection`（1356 行） |
| `flutter/android/app/src/main/kotlin/com/starcaretech/a/InputService.kt` | 自动点击闸门 `beginConsentWait/endConsentWait`（108/136 行），TTL 20s、最多 4 次点击，**本次不要改** |
| `flutter/android/app/src/main/AndroidManifest.xml` | 透明页声明 95-99 行（`singleTask` + `Transparent` 主题，**未设 taskAffinity**）；`USE_FULL_SCREEN_INTENT` 权限在 25 行 |
| `flutter/android/app/src/main/kotlin/com/starcaretech/a/common.kt` | 常量 `ACT_REQUEST_MEDIA_PROJECTION`(31)、`EXT_BOOT_AUTO_CONSENT`(43)、`REQ_REQUEST_MEDIA_PROJECTION`(49) |
| `flutter/android/app/src/main/res/xml/accessibility_service_config.xml` | `canPerformGestures=true` 已声明，无需改 |
| 诊断埋点方式 | 统一用 `com.starcaretech.a.adb.AdbAuthManager.trace(context, msg)`，写入 diagBuffer，随手动/自动诊断包上传（即本次分析的 `remote_diag_*.log`） |

## 5. 改造任务（按顺序做，每完成一个任务自查后再进下一个）

### 任务 1：暴露安全锁屏判定能力

**文件**：`MainService.kt`

`isDeviceSecureLocked()` 现在是 private 实例方法，透明页拿不到 Service 实例。在 `companion object` 中新增一个静态版本并让实例方法委托：

```kotlin
companion object {
    // 新增：无 Context 持有方也可判定；异常按"有安全锁屏"处理（保守，不解散）
    @JvmStatic
    fun isDeviceSecureLocked(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) km.isDeviceSecure
            else @Suppress("DEPRECATION") km.isKeyguardSecure
        } catch (e: Exception) {
            true
        }
    }
}

// 原实例方法改为委托，保持现有调用点不变
private fun isDeviceSecureLocked(): Boolean = isDeviceSecureLocked(this)
```

注意：`MainService` 现有 companion object 里已有 `hasActiveProjection()`/`beginProjectionRequest()` 等方法，追加即可，不要新建第二个 companion。

### 任务 2：把 requestDismissKeyguard 开放给所有入口（核心改动）

**文件**：`PermissionRequestTransparentActivity.kt`，改造 `onCreate` 中 137-168 行的分支。

现状条件：`if (bootAutoConsent && SDK>=O && keyguardManager?.isKeyguardLocked == true)`

改为（伪代码，保持现有回调与 8s fallback 结构不变）：

```kotlin
val secureLocked = MainService.isDeviceSecureLocked(this)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
    keyguardManager?.isKeyguardLocked == true &&
    !secureLocked
) {
    // 无密码锁屏：无论来源（开机/FSI/用户点通知）都先代码解散，
    // onDismissSucceeded 后在真前台再弹系统确认框
    AdbAuthManager.trace(applicationContext,
        "录屏透明页：无密码锁屏，请求 requestDismissKeyguard（bootAuto=$bootAutoConsent）")
    autoFinishHandler.postDelayed(dismissFallback, DISMISS_FALLBACK_MS)
    keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() {
            AdbAuthManager.trace(applicationContext, "锁屏已解散（onDismissSucceeded），真前台发起确认框")
            scheduleCaptureAfterFocus()
        }
        override fun onDismissCancelled() {
            // 理论上无密码锁屏不会走到；走到说明 ROM 行为变化，回退旧路径
            AdbAuthManager.trace(applicationContext, "解散被取消，回退锁屏之上直弹")
            scheduleCaptureAfterFocus()
        }
        override fun onDismissError() {
            AdbAuthManager.trace(applicationContext, "解散出错，回退锁屏之上直弹")
            scheduleCaptureAfterFocus()
        }
    })
    return
}
// 有密码锁屏 / 未上锁 / SDK 低：保持旧路径
scheduleCaptureAfterFocus()
```

硬性要求：
- **有密码锁屏设备行为必须与旧版完全一致**：`secureLocked=true` 时不得调用 requestDismissKeyguard，直接走旧路径（最终被门控拦在通知引导）。
- `bootAutoConsent` 的 30s 自毁逻辑（`autoFinishHandler.postDelayed(bootAutoFinish, ...)`）保持原样，不要动。
- 不要删 `setShowWhenLocked(true)/setTurnScreenOn(true)`（117-120 行），requestDismissKeyguard 要求页面可见。
- `DISMISS_FALLBACK_MS`（8s）无回调兜底已存在，保留；vivo 上重点验证该回调是否正常返回。

### 任务 3：透明页独立 taskAffinity，治"2 秒死亡"

**文件**：`AndroidManifest.xml` 95-99 行

```xml
<activity
    android:name=".PermissionRequestTransparentActivity"
    android:excludeFromRecents="true"
    android:launchMode="singleTask"
    android:taskAffinity=""
    android:theme="@style/Transparent" />
```

`taskAffinity=""` 使该页进入独立 task，不再与 MainActivity 共享默认 affinity，避免 FSI 展开时 MainActivity 被同 task 连带拉起抢焦点。

风险与验证要求：
- 必须真机验证 FSI 在独立 task 下仍能全屏展开（vivo + 一台小米/红米对照）。
- 验证前台手动恢复、开机自动恢复两条旧路径不受影响。
- 若实测空 affinity 在某 ROM 上导致 FSI 退化为通知，改用唯一命名 affinity（如 `com.starcaretech.a.projection`）再测，二选一，以真机结果为准。
- 本次不要顺手改 `WakeScreenActivity` 的 affinity，控制变更面。

### 任务 4：补齐透明页生命周期埋点

**文件**：`PermissionRequestTransparentActivity.kt`

新增 `onResume/onPause/onStop`（`onDestroy` 已有，补 trace），每条带齐状态快照，便于下一份日志直接判定死因：

```kotlin
private fun lifecycleTrace(stage: String) {
    AdbAuthManager.trace(applicationContext,
        "录屏透明页#$stage hasFocus=$hasWindowFocus() finishing=$isFinishing " +
        "captureLaunched=$captureLaunched pendingFocus=$launchPendingFocus " +
        "locked=${(getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked}")
}
override fun onResume() { super.onResume(); lifecycleTrace("onResume") }
override fun onPause() { super.onPause(); lifecycleTrace("onPause") }
override fun onStop() { super.onStop(); lifecycleTrace("onStop") }
// onDestroy 内追加 lifecycleTrace("onDestroy")，注意放在 super.onDestroy() 之前
```

同时在 `onCreate` 抢到闸门后、调用解散前各补一条 trace（任务 2 示例中已含）。
目的：下次日志能区分「FSI 未展开（页根本没 created）」「keyguard 清理（created→destroy 无 pause 之外信息）」「焦点丢失（pause/stop 时序）」。

### 任务 5（建议同批做）：FSI 权限可用性检测

**文件**：`EnvCheckManager.kt`

Android 14+ 非通话类应用的 FSI 权限可能默认不授予，未授予时 `setFullScreenIntent` 退化为普通横幅通知，透明页根本不会被系统自动展开（任务 2/3 都依赖它先展开）。

- API 34+ 用 `NotificationManager.canUseFullScreenIntent()` 检测，结果写入 EnvCheck 诊断项（沿用现有 envCheck 上报格式）。
- 检测为 false 时，在现有录屏恢复通知文案/EnvCheck 报告中提示用户到系统设置开启"全屏通知"权限（`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`）。
- 不要做静默跳转设置页以外的绕过；该权限没有常规 runtime API 可直接申请。

## 6. 不许动的部分（硬约束）

1. 有密码锁屏设备的任何现有行为（门控、通知引导、开机分支）保持不变。
2. `InputService` 自动点击闸门 TTL/点击次数/白名单、看门狗事件门控、录屏熔断器、在途闸门（`beginProjectionRequest`）一律不改。
3. `WakeScreenActivity` 保持"只点亮不解锁"职责，不要给它加 dismissKeyguard。
4. 不准用反射/隐藏 API/ROOT 绕过 keyguard；只允许官方 `requestDismissKeyguard`。
5. Rust 侧、Flutter 侧、ADB 配对模块不改动。
6. 不改线上任何数据；本地只改代码，编译走 GitHub CI，不本地编译、不自行部署。
7. 提交前通读 git diff，并 grep 新增顶层符号查重（本仓库有并行会话混入重复函数导致 CI 失败的先例）。

## 7. 验证方案（CI 出包后真机验证）

埋点到位后，用现有手动诊断上传取日志，按以下矩阵逐项核对关键字。

### 用例 A：无密码锁屏 + 息屏 + 主控连入（主修复路径）
期望日志顺序：
1. `(pre_login) 主动亮屏 wakelock`
2. `录屏透明页：无密码锁屏，请求 requestDismissKeyguard`
3. `锁屏已解散（onDismissSucceeded），真前台发起确认框`
4. `闸门已武装 sdk=36`
5. `录屏确认框结果: resultCode=-1`
6. `自动续上屏幕采集`，随后 `frames recovered`
全程无人工操作，主控端 30s 内出画面。

### 用例 B：有密码锁屏回归
期望：不出现 requestDismissKeyguard 相关 trace；行为同旧版（仅通知，门控日志 `非可交互前台…仅通知引导`）。

### 用例 C：前台手动恢复回归
App 在前台时触发恢复，期望与 21:04:54 旧成功路径一致，不受 taskAffinity 改动影响。

### 用例 D：开机自动授权回归
重启后 150s 窗口内验证 bootAutoConsent 路径：30s 自毁、8s dismiss fallback、补弹逻辑均正常。

### 用例 E：透明页生命周期完整性
任意触发后，日志中 `录屏透明页#onResume/onPause/onStop/onDestroy` 时序完整可对账；失败场景能据时序定位死因。

### 用例 F：FSI 权限
用例 A 设备上 EnvCheck 显示 FSI 可用；故意关闭全屏通知权限后，确认降级为横幅通知且 EnvCheck 有明确提示。

## 8. 关键日志证据摘录（原始包不在手边时用）

```
# 门控铁证：已亮屏已解锁，仅因后台被挡
21:00:51 非可交互前台（亮屏=true 解锁=true 前台=false），仅通知引导，不自动弹录屏框

# 锁屏上透明页空转（8 次之一）：有 created/resumed，无闸门武装、无结果
20:50:49 STAGE created PermissionRequestTransparentActivity
20:50:49 录屏透明页：开始授权流程（等待自动点击闸门 20s）
20:50:49 STAGE resumed PermissionRequestTransparentActivity
（之后无任何该页日志，2 秒内静默销毁）

# 系统自动解散（非手势）：两次解锁翻转都紧跟 MainActivity resumed
20:50:33 STAGE resumed MainActivity
20:50:35 capture: poll flip: keyguard locked->unlocked

# 前台成功路径（修复后期望在后台复现同样后半段）
21:04:53 录屏自动点击：闸门已武装 sdk=36 ttl=20000ms maxClicks=4
21:04:54 录屏自动点击：扫描#2 命中按钮"继续"，执行点击（第 1 次）
21:04:54 录屏确认框结果: resultCode=-1 data=true
21:04:54 自动续上屏幕采集
```

统计：透明页创建 20 次 = 武装成功 12 次（均前台）+ 空转 8 次（均锁屏/后台）；熔断器全程未触发；`WakeScreenActivity` 一次未拉起（wakelock 亮屏有效，走不到兜底）。

## 9. 排错备忘（实施时可能遇到的坑）

- **FSI 没展开**：先查任务 5 的 `canUseFullScreenIntent()`；权限正常但仍不展开，查 BAL（后台 Activity 启动限制），vivo 省电策略可能拦截，生命周期埋点会显示页连 created 都没有。
- **requestDismissKeyguard 无任何回调**：8s `dismissFallback` 会回退旧路径；若 vivo 高频出现，考虑把 fallback 后的策略改为仅刷新通知（避免锁屏之上直弹再死一次刷无效计数），需评审后再改。
- **解散成功但确认框仍不弹**：查 `onWindowFocusChanged` 是否触发、2s focusFallback 是否执行；这正是任务 3 taskAffinity 要解决的焦点争夺。
- **不要**因为一次测试失败就回退整个分支，按用例 A 的 6 个关键字定位断在哪一环，只改断环。
