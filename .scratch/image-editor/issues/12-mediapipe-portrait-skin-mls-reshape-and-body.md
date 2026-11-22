# 12: MediaPipe 混合分发、双模式人像美肤、478 点多人脸 MLS 重塑与美体切片

**What to build:** 在编辑器「人像」面板交付端侧人像精修三件套（美肤、面部重塑、美体）：集成 `com.google.mediapipe:tasks-vision:1.2.3`（配齐 ABI 排除、release 剥离调试符号、R8 keep 规则及 GPU→CPU 自动黑名单回退），随 APK 内置 `face_landmarker.task`（3.33MB）与 `selfie_segmenter.tflite`（250KB），并在首次打开人像面板时按需 HTTPS Range 下载 16.37MB `selfie_multiclass_256x256.tflite`（SHA-256 校验）；在 16.37MB 模型就绪前提供离线即用的美肤频率分离（内置 478 点凸包扣除眼眉唇 + 250KB 前景 + OKLCH 肤色门控），下载完成后无缝升级为 6 类精细 Mask（精准抠除发丝与衣物）并联动抑制皮肤区锐化；基于 478 点旋转不变局部人脸坐标系与 Schaefer MLS 相似变换支持最多 5 张脸合影同步重塑（瘦脸/大眼/缩鼻/下巴/额头/微笑）；基于人脸框+人体 Mask 质心初值与画布双可拖拽引导横线实现美体（瘦腰/固定画幅比例内接裁切拉腿），并将面部 MLS、美体与液化位移合并进同一张 `128×128 GL_RG16F` `uWarpTex` 在 Pass A 一次采样。

**Blocked by:** 08: 1024px 全图 Pre-pass 金字塔、清晰度/纹理/锐化/降噪与暗通道去雾切片（Pass C）, 11: 液化画笔与 O(1) 128×128 位移网格持久化切片

**Status:** resolved

- [x] 接入 `com.google.mediapipe:tasks-vision:1.2.3`：配置 Gradle `packaging.jniLibs.excludes` 过滤 `x86/x86_64`、release `ndk.debugSymbolLevel = "none"`、ProGuard `-keep class com.google.mediapipe.**`，以及 Tier 4 `Delegate.GPU` 抛 `MediaPipeException` 时自动记入黑名单并永久回退 `Delegate.CPU`。
- [x] 内置 `face_landmarker.task` 与 `selfie_segmenter.tflite`；实现 `ModelDownloadManager` 支持后台 HTTPS Range 断点续传与 SHA-256 校验下载 `selfie_multiclass_256x256.tflite`。
- [x] 实现离线+在线双模式 `skinMask` 合成（扣除左/右眼眶、左/右眉、外唇 5 个羽化多边形）及 Pass C 开头的美肤引导滤波频率分离（平滑/纹理保留/CbCr 匀肤），并将 `1 - skinMask` 传入 Pass C 锐化算子抑制皮肤毛孔锐化。
- [x] 实现 `FaceMlsSolver`（`numFaces = 5`，旋转不变局部人脸坐标系，瘦脸/大眼/缩鼻/下巴/额头/微笑 6 滑杆，图像四边 32 锚点 + 每张脸外围 $1.8\times$ 处 16 锚点，64×64 求解后上采样至 128×128）与美体位移（头身比+分割质心初值 + 画布腰线/髋线双可拖拽引导线 + 拉腿底部内接裁切），与液化网格向量相加合成为单张 `128×128 GL_RG16F` `uWarpTex`。
- [x] 单测验证 `FaceMlsSolver` 在 0 参数下全零位移、非零参数下图像四边与 $1.8\times$ 锚点处位移 $< 10^{-4}$，以及 `ModelDownloadManager` 对 SHA-256 不匹配文件的拒绝清理逻辑。

## 落地记录

- 构建护栏与 `MediaPipeDelegatePolicy`：`gradle/libs.versions.toml`、`editor/build.gradle`、`app/build.gradle`、`app/proguard-rules.pro`、`editor/…/vision/MediaPipeDelegatePolicy.kt`。
- 按需下载：`editor/…/vision/ModelDownloadManager.kt`（`.part` 续传、尺寸 + SHA-256 校验、原子 rename、失败清理），单测 `ModelDownloadManagerTest`。
- 双模式 `skinMask`：`SkinProtectionMask`、`SkinMaskMath`、`SkinSmoothingMath`、`PassCDetailMath` 的 Stage 0 调用；单测 `SkinMaskMathTest`、`SpatialDetailPlannerTest`。
- 多人脸 MLS：`FaceLandmarks`、`FaceLandmarkIndices`、`FaceReshapeControls`、`MlsSimilarity`、`FaceMlsSolver`；单测 `FaceMlsSolverTest`。
- 美体与统一 `uWarpTex`：`BodyWarpSolver`、`PersonMaskSampling`、`PortraitWarpComposer`、`UnifiedWarpField`、`WarpTexture`、`EditorGlRenderer`、`PreviewPassPipeline`、`PreviewCacheWriter`；单测 `BodyWarpSolverTest`、`PortraitWarpComposerTest`。
- 视觉引擎：`PortraitModelCatalog`、`MediaPipePortraitEngine`、`PortraitAnalyzer`、`EditorPortraitCoordinator`（进入面板触发分析 + 后台下载 + 升级重分析）。
- UI：`EditorPortraitSpecs`、`PortraitPanelsBinder`、`SkinPanelView`、`FaceReshapePanelView`、`BodyReshapePanelView`、`BodyGuideOverlayView`，新增撤销桶 `PORTRAIT_SKIN/RESHAPE/BODY`；单测 `PortraitToolCatalogTest`。
- 决策与风险记录：`docs/adr/0008-mediapipe-portrait-mls-and-dual-skin-mask.md`。
- 验收：`./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 全绿。
