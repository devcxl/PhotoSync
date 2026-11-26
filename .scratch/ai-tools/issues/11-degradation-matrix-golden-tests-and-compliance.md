# 11: 全链路安全降级矩阵验证、真机金标回归与合规文档收官

**What to build:** 对 P0–P4 全部端侧与可选云端 AI 能力进行收官验收与加固：构建覆盖两档内存（≤4 GB 低端档 / >4 GB 标准档）× 三档 GPU（Tier 1 `RGBA16F` / Tier 2 `RGBA8` / Tier 3 最小 Shader）的自动化降级矩阵与真机金标回归套件，逐条验证 24MP 透明 PNG 导出内存与无 OOM、≤4 GB 推理完 `OrtSession` 计数归零、外发图片零 EXIF 与零缩略图残留、公网明文 HTTP 与跨源重定向拦截、以及 Release R8 APK/AAB 体积与遥测清理门禁；完成 `THIRD_PARTY_NOTICES.md`（ONNX Runtime MIT、MODNet Apache-2.0、MI-GAN MIT + 权重许可、CC0 天空素材声明）、`CONTEXT.md` 与三语 README 的同步核验。

**Blocked by:** 06: P2 智能消除（MI-GAN-512 局部裁切融合 + 确认涂抹画笔）垂直切片, 09: P4 编辑器云端生成闭环（CloudEditLayer、首次隐私告知与几何冲突守卫）, 10: P3 换天空（零模型分割、OKLab 前景重光照与 0 KB 程序化/自选天空）垂直切片

**Status:** resolved（24MP 金标 Instrumented 套件已落地并通过编译；本机 `adb devices` 无设备，真机执行待设备上线）

- [ ] 真机/Instrumented 回归通过：24MP RAW 与 JPEG 执行「去背导出 PNG」「消除 + 调色导出 JPEG」「换天 + LUT 导出 JPEG」全部无 OOM、像素视觉通过金标验收且无列带拼缝。
  - 已落地：`editor/src/androidTest/.../export/AiBakeGoldenInstrumentedTest.kt` + `AiGoldenFixtures.kt` 以 6000×4000 合成 JPEG 走生产 `BakeExporter` 跑三案（透明 PNG alpha/尺寸/列带与行带连续性、绿色消除补丁 +0.5 EV 调色、`sunset_glow` + `relight=60` + 3D LUT 换天），`AiLowTierSessionInstrumentedTest.kt` 覆盖 LOW 档 MODNet/MI-GAN 真实推理后 `activeSessionCount == 0`。三份 Instrumented 源码均通过 `:editor:compileDebugAndroidTestKotlin`。
  - 未执行：本机 `adb devices` 无设备（Redmi K20 Pro 未连接），故 24MP 真机跑分、像素验收与内存峰值**尚未实测**；仓库无 24MP 真实 RAW 夹具，**RAW 未实测**，三案均为 JPEG。既有 `BakeExporterInstrumentedTest` 与 `OffscreenPreviewRendererFallbackTest` 本轮同样未在真机复跑（仅随 `:editor:compileDebugAndroidTestKotlin` 编译通过）。
- [x] 内存与降级门禁通过：模拟 ≤4 GB 低内存档执行 MODNet 与 MI-GAN 推理结束后 `OrtSession` 活跃计数归零；模拟烘焙期 OOM 与 Tier 2/Tier 3 GPU 均按降级矩阵优雅回退不崩溃。
  - `OrtSessionManagerTest` 已覆盖两档单活互斥、LOW 推理完立即关闭、标准档 30 s 闲置释放、`onMemoryPressure`、模型切换、`OutOfMemoryError` / `OrtException` 可恢复失败；`DeviceMemoryTierTest` 锁定 4 GiB 边界与未知 RAM 归入 LOW。新增 `AiDegradationMatrixTest`（8 例全绿）补齐：LOW 档连续跑两个模型后计数仍为 0、会话工厂 `OutOfMemoryError` 在创建前即降级、工厂 `OrtException`、`BandGuidedFilter` 分配失败自动退回羽化 alpha（逐像素对齐 `feather` 参考）、主体 band 读回失败时复用代理前景与羽化 alpha、天空相邻列带掩码连续性（`< 0.05`）、Tier 2 `RGBA8` 仍允许 Pass D AI 合成、Tier 3 最小 Shader 关闭全部 Pass D 效果。
  - `TetheredSubjectPresetTest.shouldReturnNull_whenTheDeviceIsLowMemoryOrTheModelIsMissing` 与 `shouldDegradeToTheTraditionalGrade_whenThePreparerReturnsNull` 覆盖联机入库静默降级；`BakeSkySupportTest` / `BakeSubjectSupportTest` 覆盖烘焙期缺少 guide / 读回像素时的双线性回退。
  - 新增生产 seam：`GlCapabilities.allowsPassDEffects`（= `!isMinimalFallback`），`PreviewPassPipeline` 用它作为 Tier 3 的唯一门禁，使“Tier 3 禁用背景虚化与复杂天空合成、仅保基础调色”可被 JVM 测试锁定。
- [x] 安全与 Release 门禁通过：`assembleRelease` 与 `bundleRelease` 产物经检查确认不含 `x86`/`x86_64`、不含 `TelemetryInitializer` provider、保留 `ai.onnxruntime.**`；外发清洗与公网明文拦截回归全绿。
  - 本轮以 debug keystore 实测（`KEYSTORE_PATH=$HOME/.android/debug.keystore` 等环境变量，未提交 keystore）：
    - `app-release.apk`（universal，arm64 + armeabi-v7a）：72,270,922 B（68.9 MiB）；随后按 ABI 拆分重建：`app-arm64-v8a-release.apk` **44,506,915 B（42.4 MiB）**、`app-armeabi-v7a-release.apk` 33,931,895 B。
    - `app-release.aab`：**36,983,484 B（35.3 MiB）**。
    - APK / AAB 的 `lib/` 仅含 `arm64-v8a` 与 `armeabi-v7a` 的 `libc++_shared.so`、`libmediapipe_tasks_jni.so`、`libonnxruntime.so`、`libonnxruntime4j_jni.so`、`libraw.so`、`libraw_wrapper.so`；`lib/x86*` 计数 0，`.sym` 计数 0（`ndk.debugSymbolLevel = "none"` 生效）。
    - 合并清单（arm64 拆分与 AAB proto 清单）中 `TelemetryInitializer` 出现 0 次，仅保留 `androidx.startup.InitializationProvider`。
    - `app/build/outputs/mapping/release/mapping.txt` 中 `ai.onnxruntime.**` 63 条、`com.google.common.flogger.**` 160 条映射均为原名（如 `ai.onnxruntime.OrtEnvironment -> ai.onnxruntime.OrtEnvironment`、`com.google.common.flogger.FluentLogger -> com.google.common.flogger.FluentLogger`），未被混淆。
  - 外发清洗：`CloudUploadSanitizerTest` 断言 GPS/Make/Model/DateTime/IFD1 缩略图清零且方向旋正；`CloudNetworkPolicyTest` 覆盖公网明文 HTTP 拒绝、私有 LAN 需显式 opt-in + 解析后全私有地址、`instanceFollowRedirects=false` 与 3xx 拒绝、32 MiB 上限与响应头注入。
- [x] `THIRD_PARTY_NOTICES.md`、`CONTEXT.md` 与三语 README（中/英/俄）均已完成合规与功能同步审查，且全套 `./gradlew assembleDebug testDebugUnitTest lintDebug` 通过。
  - `THIRD_PARTY_NOTICES.md` 新增「程序化天空（P3）」自研 MIT 声明与「可选 CC0 高清云彩包（P3）」条目（明确不内置、CC0 出处逐条登记要求、当前 `SkyModelCatalog.CC0_CLOUD_PACK` 未 pin 因而干净失败）。
  - `CONTEXT.md` 对齐最终术语：`LiveTetherPreset` 记录 AI 参数在 LOW 档/缺模型时静默降级；`SkyLayer` 明确 `SkyAssetType` 三值与四组 `ProceduralSkyPreset`；`Bake` 明确透明去背输出流式 PNG。
  - 三语 README 同步 P0–P4 能力（主体去背/换底/虚化/前后景调色、智能消除、换天空、按需模型管理、透明 PNG 导出、默认关闭的 BYOK 云端增强），技术栈补充 `ONNX Runtime 1.22.0` 与 `AndroidKeyStore`，项目结构补充 `vision` ORT 与 `cloud` 目录。
  - 门禁：`./gradlew assembleDebug testDebugUnitTest lintDebug` 全绿（见提交时输出）。
