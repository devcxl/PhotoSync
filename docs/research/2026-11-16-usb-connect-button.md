# USB 连接流程缺陷分析与「连接相机」按钮改造方案

**日期**: 2026-11-16
**状态**: 调研完成（待确认后实施）
**涉及文件**: `AndroidManifest.xml`、`MainActivity.kt`、`UsbPtpConnectionController.kt`、`App.kt`、`receiver/*`、`res/layout/activity_main.xml`

---

## 1. 结论摘要

用户反馈的两个症状背后是**三个独立缺陷叠加**，其中第 3 条是隐性的、且决定改造顺序：

| # | 缺陷 | 直接后果 |
|---|------|----------|
| D1 | `USB_DEVICE_ATTACHED` 与 `MAIN/LAUNCHER` 写在同一个 `<intent-filter>`，并声明了 `device_filter.xml` | 相机插入/重新枚举时**由系统**发起拉起 `MainActivity`，每次匹配都可能是一个新实例 |
| D2 | `onCreate()` 无条件自动连接 + `onDestroy()` 无条件 `disconnect("activity_destroyed")` | 进程级单例控制器被 Activity 生命周期反复拆除，连接与自己的上一个/下一个实例互相 `close()` |
| D3 | 权限结果的 `PendingIntent` 用 `FLAG_IMMUTABLE`，而系统是在 `send()` 时才注入 extras | 授权广播到达时 `EXTRA_DEVICE=null`、`EXTRA_PERMISSION_GRANTED` 缺省 `false` → 应用**把「用户已允许」判成拒绝**，静默放弃连接 |

因此:

- 只要还保留 manifest 的 USB 过滤器，D1+D2 就无法根治；
- **但如果只删过滤器而不修 D3，应用会从「有时候能连」直接退化成「永远连不上」**——因为目前唯一稳定可用的授权路径恰好是 manifest 过滤器带来的「系统弹窗 → 自动授权」，删掉它之后就只能走 `requestPermission()`，而那条链路当前是坏的。

改造方案（用户提出的按钮模型）本身正确，顺序必须是：**先修 D3 → 再删 D1 → 再拆 D2 → 最后加按钮**。

---

## 2. 现状代码证据

### 2.1 系统在插入设备时代为启动 Activity（D1）

`app/src/main/AndroidManifest.xml`：

```xml
<activity android:name=".activity.MainActivity"
          android:exported="true"
          android:launchMode="singleTask" ...>
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"/>
    </intent-filter>
    <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
               android:resource="@xml/device_filter"/>
</activity>
```

`res/xml/device_filter.xml`：`<usb-device class="6" />`（Still Image）。

`class` 匹配的是 `UsbDevice.getDeviceClass()`。PTP 相机常见形态是 `bDeviceClass = 0` + **接口**类 6，此时过滤器不匹配 → 系统不弹窗、不授权；匹配时（设备类直接是 6）系统弹「用 PhotoSync 打开？」并**顺带授予 USB 权限**。这就是「有时候」。

### 2.2 应用侧还有第二条自动连接通路（D1 的叠加）

`App.kt` 进程启动即注册动态接收器；`UsbReceiver` 收到 attach 后**直接连接**：

```kotlin
UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
    Toast.makeText(...).show()
    controller?.onUsbAttached(device)     // → tryStartConnection()
}
```

一次插拔因此有两条入口（系统拉起 Activity + 广播触发连接），时序不确定；去重键是 `UsbDevice.deviceId`，相机重新枚举后该值变化，`shouldSkipConnect` 失效。

### 2.3 连接被 Activity 生命周期撕扯（D2）

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    ...
    configureConnectionController()
    connectMTPDevice()          // 无条件自动连接
}

override fun onDestroy() {
    connectionController.setFileTransferListener(null)
    connectionController.setFileDownloadedListener(null)
    connectionController.disconnect("activity_destroyed")   // ← 强制断开
    super.onDestroy()
}
```

而控制器是进程级单例（`App.usbPtpConnectionController`）。典型坏时序：

```
Activity#1 onCreate → 申请权限 → 相机握手（数百 ms ~ 数秒）
Activity#2 被拉起 → #1.onDestroy → disconnect()        // 关掉 #2 正要用的 initiator
                   #2.onCreate  → connect()            // 重新 openSession()
```

`connectInternal()` 自身还会先 `disconnectInternal("reconnect_before_connect")`，多路并发时互相 `close()` 对方的 `BaselineInitiator`。历史提交 `89645ba`（去掉重复的静态 `UsbPermissionReceiver`）修的是同一类问题，只是从广播侧转移到了生命周期侧。

### 2.4 连接目标没有过滤

```kotlin
fun scanAndConnectIfPossible() {
    val devices = usbManager.deviceList.values.toList()   // 全部 USB 设备
    devices.forEach { device -> if (tryStartConnection(device)) return@launch }
}
```

`device_filter.xml` 只约束系统拉起，管不到应用自己的扫描：任何插着的 USB 设备都会进入 `openDevice()` + PTP 握手。

### 2.5 权限结果链路被 `FLAG_IMMUTABLE` 截断（D3，关键）

```kotlin
val permissionIntent = PendingIntent.getBroadcast(
    appContext,
    device.deviceId,
    Intent(App.ACTION_USB_PERMISSION),                       // 构造时不带任何 extras
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)
usbManager.requestPermission(device, permissionIntent)
```

系统侧的授权结果是这样回来的（SystemUI `UsbDialogHelper.sendPermissionDialogResponse()`）：

```java
intent.putExtra(UsbManager.EXTRA_DEVICE, mDevice);
intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, permissionGranted);
mPendingIntent.send(mContext, 0, intent);      // 授权结果靠 send 时才注入
```

而 `PendingIntentRecord.sendInner()` 只有**非 immutable** 才会合并发送方 Intent：

```java
final boolean immutable = (key.flags & PendingIntent.FLAG_IMMUTABLE) != 0;
if (!immutable) {
    int changes = finalIntent.fillIn(intent, key.flags);   // ← extras 在这一步进来
    ...
} else {
    resolvedType = key.requestResolvedType;                // ← 发送方 Intent 被整份丢弃
}
```

于是 `UsbPermissionReceiver` 拿到的是**裸 Intent**：

```kotlin
val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)  // null
val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)        // false
controller.onUsbPermissionResult(null, false)
```

`onUsbPermissionResult(null, false)` → `Error(null, "permission_denied")`；`MainActivity.renderUsbConnectionState()` 对 `permission_denied` 直接 `return`（不打日志、不提示），接收器还会弹一条「未授权访问USB设备」——用户明明点了「允许」。

> 为什么不能简单改成 `FLAG_MUTABLE`：targetSdk ≥ 34 时，**没有 component/package 的隐式 Intent 不允许配 mutable PendingIntent**，会直接抛异常（见 §6 S5）。所以正确做法是**不再依赖 extras**，用 `UsbManager.hasPermission()` 作为唯一事实来源。

### 2.6 权限结果接收器注册为 `EXPORTED`

```kotlin
registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_EXPORTED)
```

`cn.devcxl.photosync.USB_PERMISSION` 是自定义 action，`EXPORTED` 意味着任意第三方应用都能伪造「授权成功/失败」广播来干扰连接状态（无法真正提权）。在 §5.2 改成「以 `hasPermission()` 为准」之后，伪造广播的破坏力降级为「多触发一次真实校验」。

---

## 3. 根因分析：为什么是「有时候」

| 相机枚举形态 | 系统是否弹窗授权 | 用户看到的 |
|--------------|------------------|------------|
| `bDeviceClass = 6`（匹配 `device_filter`） | 弹窗 → 授权 → `hasPermission=true` → 直接握手 | 应用自己弹出来（D1），**有时**叠出第二个实例并互杀（D2） |
| `bDeviceClass = 0` + 接口类 6（不匹配） | 不弹窗 → 走 `requestPermission()` → 结果被 IMMUTABLE 吃掉（D3） | 弹了「允许」却提示「未授权」，连不上 |

两种形态在真机上可能同时存在（同一台相机切换 USB 模式、或换一根线/换一台相机），于是表现为「有时候能连、有时候一直弹 Activity、有时候怎么点都连不上」。

---

## 4. 目标交互模型

```
┌─────────────────────────────────────────────┐
│ [连接相机]                     [导出]        │  ← 常驻按钮，状态驱动
│                                             │
│             照片预览区                       │
│                                             │
│                                [删除]        │
└─────────────────────────────────────────────┘
```

状态矩阵（`UsbPtpConnectionState` × `cameraPresent`）：

| 连接状态 | 检测到相机 | 按钮文案 | 可点击 | 点击动作 |
|----------|-----------|----------|--------|----------|
| `Idle` / `Disconnected` / `Error` | 是 | 连接相机 | ✅ | `connectToAvailableCamera()` |
| `Idle` / `Disconnected` / `Error` | 否 | 未检测到相机 | ❌ 置灰 | — |
| `PermissionRequested` | 是 | 等待 USB 授权… | ❌ | — |
| `Connecting` | 是 | 正在连接… | ❌ | — |
| `Connected` | 是 | 断开相机 | ✅ | `disconnect("user_request")` |
| `Disconnecting` | 任意 | 正在断开… | ❌ | — |

要点：

- **插入设备 ≠ 连接**：attach/detach 只刷新「设备是否存在」，不触发握手。
- 前台靠 `App` 已注册的动态广播即时刷新；`onResume()` 主动扫一次 `deviceList` 兜底（尽管 attach/detach 属于后台隐式广播豁免项，见 §6 S4）。
- 连接所有权在 `App`，`Activity` 重建不影响已建立会话。

---

## 5. 实施计划（按顺序执行）

### 5.0 顺序

```
5.2 修权限结果（D3）  →  5.1 删 manifest 过滤器（D1）  →  5.3 拆生命周期耦合（D2）  →  5.4 加按钮
```

任何一步都不能跳序：先删过滤器会让 D3 从「偶发」变成「必现」。

### 5.1 删除 manifest 的 USB 过滤器（D1）

```diff
 <activity android:name=".activity.MainActivity" ...>
     <intent-filter>
         <action android:name="android.intent.action.MAIN"/>
         <category android:name="android.intent.category.LAUNCHER"/>
-        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"/>
     </intent-filter>
-    <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
-               android:resource="@xml/device_filter"/>
 </activity>
```

同时删除不再被引用的 `app/src/main/res/xml/device_filter.xml`（Clean Break，不留死资源）。`launchMode="singleTask"` **保留**——但注意它并不保证单实例（§6 S3），它只是「一个 task 内可复用」，不能替代去掉过滤器。

### 5.2 权限结果以 `hasPermission()` 为准（D3）

`UsbPtpConnectionController`：

```kotlin
/**
 * 处理 USB 授权广播的回执。
 *
 * 不读取广播里的 EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED：PendingIntent 为
 * FLAG_IMMUTABLE（targetSdk ≥ 34 也不允许隐式 Intent 配 mutable），系统在 send()
 * 时注入的 extras 会被丢弃，读到的永远是 null/false。这里改用请求时记下的设备
 * 标识回查 + hasPermission() 作为唯一事实来源。
 */
fun onUsbPermissionResult() {
    scope.launch {
        val pending = stateMutex.withLock { pendingPermissionDeviceId } ?: return@launch
        val device = usbManager.deviceList.values.firstOrNull { it.toUsbPtpDeviceId() == pending }
        if (device == null) {
            stateMutex.withLock { pendingPermissionDeviceId = null }
            _connectionState.value = UsbPtpConnectionState.Error(pending, "permission_missing_device")
            return@launch
        }
        if (usbManager.hasPermission(device)) {
            connectInternal(device)
        } else {
            stateMutex.withLock { pendingPermissionDeviceId = null }
            _connectionState.value = UsbPtpConnectionState.Error(pending, "permission_denied")
        }
    }
}
```

`UsbPermissionReceiver` 改为只做「唤醒」，Toast 文案交给状态流决定（避免再出现「点了允许却说未授权」）：

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != App.ACTION_USB_PERMISSION) return
    Timber.d("USB permission broadcast received; re-checking hasPermission")
    (context.applicationContext as? App)?.usbPtpConnectionController?.onUsbPermissionResult()
}
```

`MainActivity.renderUsbConnectionState()` 里给 `permission_denied` 补上提示（现在是被 `return` 吞掉的）：

```kotlin
if (state.reason == "permission_denied") {
    binding.progressBar.visibility = View.GONE
    Toast.makeText(this, getString(R.string.usb_permission_denied), Toast.LENGTH_LONG).show()
    return
}
```

### 5.3 拆分「设备存在」与「发起连接」（D2）

```kotlin
private val _availableCameras = MutableStateFlow<List<UsbPtpDeviceId>>(emptyList())
/** 当前插着且形似相机的设备；不含任何连接语义。 */
val availableCameras: StateFlow<List<UsbPtpDeviceId>> = _availableCameras.asStateFlow()

/** 只刷新设备列表，绝不发起连接。由 attach/detach 与 onResume 调用。 */
fun refreshAvailableCameras() { ... }

/** 用户显式点击「连接相机」时才调用。 */
fun connectToAvailableCamera() { ... }
```

- 删除 `scanAndConnectIfPossible()`（不再有「扫描即连接」）。
- `onUsbAttached(device)` → 只 `refreshAvailableCameras()`。
- `onUsbDetached(device)` → `refreshAvailableCameras()` + 现有「若为当前设备则 `disconnect("usb_detached")`」逻辑保留。
- 设备过滤收窄为「设备类 6 **或** 任一类 6 接口 **或** Canon/Nikon/Sony 厂商 ID」，与 `CameraDetector` 的厂商表对齐；抽成纯函数便于单测：

```kotlin
internal fun isSupportedCamera(
    deviceClass: Int,
    interfaceClasses: List<Int>,
    vendorId: Int
): Boolean
```

`MainActivity`：

- `onCreate()` 删掉 `connectMTPDevice()`，改为 `refreshAvailableCameras()`；
- 新增 `onResume()` → `refreshAvailableCameras()`；
- `onDestroy()` **删除** `disconnect("activity_destroyed")`，只保留摘监听（连接所有权归 `App`）。

### 5.4 按钮 UI

`activity_main.xml`：`previewContainer` 左上角加 `Button`（`@+id/connectButton`），初始 `enabled=false`，外边距与现有 `exportButton` 对齐。

`strings.xml`（禁止硬编码，AGENTS.md §13）：

```xml
<string name="connect_camera">连接相机</string>
<string name="disconnect_camera">断开相机</string>
<string name="camera_not_detected">未检测到相机</string>
<string name="camera_connecting">正在连接…</string>
<string name="camera_waiting_permission">等待 USB 授权…</string>
<string name="camera_disconnecting">正在断开…</string>
```

渲染逻辑抽成可测纯函数：

```kotlin
internal data class ConnectButtonUi(@StringRes val label: Int, val enabled: Boolean)

internal fun connectButtonUi(
    state: UsbPtpConnectionState,
    cameraPresent: Boolean
): ConnectButtonUi
```

### 5.5 测试（JVM 单测）

新增 `app/src/test/java/cn/devcxl/photosync/ptp/manager/`：

| 用例 | 覆盖 |
|------|------|
| `shouldReportCamera_whenDeviceClassIsStillImage` | `isSupportedCamera` |
| `shouldReportCamera_whenOnlyInterfaceIsStillImage` | 复合设备（PTP 类在接口上） |
| `shouldReportCamera_whenVendorIsKnownEvenIfClassIsVendorSpecific` | 厂商兜底 |
| `shouldDisableConnectButton_whenNoCameraDetected` | `connectButtonUi` |
| `shouldDisableConnectButton_whenConnecting` | 过渡态防重复点击 |
| `shouldLabelDisconnect_whenConnected` | 文案状态映射 |

控制器本身依赖 `Context`/`UsbManager`，本轮不引入 gateway 抽象（YAGNI）；若后续要覆盖 `onUsbPermissionResult` 分支，再抽 `UsbDeviceGateway` 接口。

---

## 6. 一手来源核实

| # | 结论 | 来源 | 置信度 | 对本方案的含义 |
|---|------|------|--------|----------------|
| S1 | `<intent-filter>` + `device_filter` 的语义是「插入时问用户是否启动应用；用户接受则顺带授权」，而非「自动启动」 | [USB host overview](https://developer.android.com/develop/connectivity/usb/host)："When users connect a device that matches your device filter, the system presents them with a dialog that asks if they want to start your application. If users accept, your application automatically has permission to access the device until the device is disconnected." | 高 | 过滤器只是授权捷径，删掉不影响能否通信 |
| S2 | `getDeviceList()` 返回全部已插入设备，与 manifest 过滤器无关 | [UsbManager#getDeviceList()](https://developer.android.com/reference/android/hardware/usb/UsbManager#getDeviceList())："Returns a HashMap containing all USB devices currently attached…" | 高 | 可以安全删除 `device_filter.xml`，靠枚举 + `requestPermission` 工作 |
| S3 | `singleTask` **不保证单实例** | [activity-element#lmode](https://developer.android.com/guide/topics/manifest/activity-element#lmode)："An activity with the 'singleTask' launch mode combines the behaviors of 'singleInstance' and 'singleInstancePerTask': the activity can be instantiated multiple times and can be located anywhere in a task of the same taskAffinity." | 高 | 现有 `singleTask` 无法阻止「又打开一个 Activity」，必须去掉系统拉起入口 |
| S4 | `ACTION_USB_DEVICE_ATTACHED/DETACHED` 属于 Android 8 隐式广播限制的**豁免项** | [broadcast-exceptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)：列表中含上述 action，并注明 "there is no good alternative to registering for the broadcast" | 高 | 动态接收器方案可行；`onResume` 扫描只是兜底 |
| S5 | targetSdk ≥ 34 时，**无 component/package 的隐式 Intent 不允许创建 mutable PendingIntent**（抛异常） | [Android 14 行为变更](https://developer.android.com/about/versions/14/behavior-changes-14#implicit-pending-intents)："If an app creates a mutable pending intent with an intent that doesn't specify a component or package, the system throws an exception." | 高 | 不能靠「改 FLAG_MUTABLE」修 D3，必须改用 `hasPermission()` |
| S6 | `FLAG_IMMUTABLE` 会丢弃 `send()` 时传入的 Intent（含 extras） | AOSP [`PendingIntentRecord.sendInner`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/am/PendingIntentRecord.java)：`if (!immutable) { finalIntent.fillIn(intent, key.flags); } else { resolvedType = key.requestResolvedType; }`；官方文档同义："the additional intent argument passed to the send methods to fill in unpopulated properties of this intent will be ignored" | 高 | 解释 D3 的机制；接收器读 extras 永远为空 |
| S7 | 授权结果由 SystemUI 的 `UsbPermissionActivity` 通过你提供的 `PendingIntent` 在 `send()` 时注入 | AOSP [`UsbDialogHelper.sendPermissionDialogResponse()`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/usb/UsbDialogHelper.java) | 高 | 与 S6 组合即为 D3 |
| S8 | 零 category 的 Intent 可以匹配任何 filter（含只有 `LAUNCHER` 的 filter） | [intents-filters#CategoryTest](https://developer.android.com/guide/components/intents-filters)："an intent with no categories always passes this test, regardless of what categories are declared in the filter"；AOSP `IntentFilter.matchCategories()` 对 `categories == null` 直接返回 `null`（= 匹配） | 高 | 现有 manifest 写法**确实**会被系统的 attach 请求命中，D1 成立 |
| S9 | 系统构造 attach Intent 时不加 `CATEGORY_DEFAULT`，匹配也可能 >1 个应用 | AOSP [`UsbProfileGroupSettingsManager`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/usb/java/com/android/server/usb/UsbProfileGroupSettingsManager.java)：`createDeviceAttachedIntent()` + `FLAG_ACTIVITY_NEW_TASK`，随后 `confirmUsbHandler` / `selectUsbHandler` | 高 | 装了多个相机 App 时会弹选择器；相机重新枚举即再弹一次 |
| S10 | `RECEIVER_NOT_EXPORTED` 只挡「其它应用」，系统（含系统应用）仍可达；但 PendingIntent 广播的调用方判定细节未完全核实 | [Context#RECEIVER_NOT_EXPORTED](https://developer.android.com/reference/android/content/Context#RECEIVER_NOT_EXPORTED)；AOSP `PendingIntentRecord` 以 `key.packageName` + owner uid 广播 | 中 | 本次不收紧注册标志（留作后续加固），因为 D3 修好后伪造广播已无实质影响 |

---

## 7. 风险与开放问题

| 项 | 说明 | 处理 |
|----|------|------|
| R1 | 删掉 manifest 过滤器后，部分 ROM 上插入设备会弹系统「USB 设备已连接」通知，而不再自动进应用 | 这正是目标行为；文案上可在 `usb_inserted` 提示中引导用户打开应用 |
| R2 | 相机在会话中途重新枚举 → 落到 `Disconnected`，需用户再点一次 | 显式语义，可接受；如需自动重连另开需求（同 `vendorId:productId:serial` + 时间窗） |
| R3 | 过渡态仍有窄竞态：`tryStartConnection` 在「检查」与「连接」之间释放锁 | 按钮置灰已把 UI 侧并发收敛为 1 次；彻底消除需单飞（in-flight）门闩，另开任务 |
| R4 | `RECEIVER_EXPORTED` + 自定义 action 可被伪造 | D3 修复后破坏力降级；加固路径：把 PendingIntent 改为显式 component + manifest `exported=false` 接收器 → 需真机验证（S10 置信度中） |
| R5 | 真机验证缺口：D3 机制虽由源码确认，但「当前机器上确实走了 `requestPermission` 分支」需 logcat 佐证 | 实施前后各抓一次 `adb logcat | grep -i "USB permission"` 对比 |

---

## 8. 引用清单

1. USB host overview — https://developer.android.com/develop/connectivity/usb/host
2. `UsbManager` 参考（`getDeviceList`、`requestPermission`、extras）— https://developer.android.com/reference/android/hardware/usb/UsbManager
3. 隐式广播豁免清单 — https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
4. `<activity>`（`launchMode` 语义表）— https://developer.android.com/guide/topics/manifest/activity-element#lmode
5. Intent 与 Intent Filter（category 测试规则）— https://developer.android.com/guide/components/intents-filters
6. `FLAG_ACTIVITY_NEW_TASK` — https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_NEW_TASK
7. `Activity#onDestroy` 不可依赖 — https://developer.android.com/reference/android/app/Activity#onDestroy()
8. `PendingIntent#FLAG_IMMUTABLE` — https://developer.android.com/reference/android/app/PendingIntent#FLAG_IMMUTABLE
9. Android 14 隐式/mutable PendingIntent 限制 — https://developer.android.com/about/versions/14/behavior-changes-14#implicit-pending-intents
10. `Context#RECEIVER_NOT_EXPORTED` — https://developer.android.com/reference/android/content/Context#RECEIVER_NOT_EXPORTED
11. AOSP `PendingIntentRecord.sendInner` — https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/am/PendingIntentRecord.java
12. AOSP `UsbDialogHelper.sendPermissionDialogResponse` — https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/usb/UsbDialogHelper.java
13. AOSP `IntentFilter.matchCategories` — https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/content/IntentFilter.java
14. AOSP `UsbProfileGroupSettingsManager` — https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/usb/java/com/android/server/usb/UsbProfileGroupSettingsManager.java
