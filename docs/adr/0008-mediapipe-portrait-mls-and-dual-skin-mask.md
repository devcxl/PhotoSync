# 0008 — MediaPipe 混合分发、双模式 `skinMask`、多人脸 MLS 重塑与统一 `uWarpTex`

- 状态：已采纳
- 日期：2026-11-23
- 影响范围：`editor/src/main/java/cn/devcxl/photosync/editor/domain/`（`FaceLandmarks`、`FaceLandmarkIndices`、`FaceReshapeControls`、`MlsSimilarity`、`FaceMlsSolver`、`BodyWarpSolver`、`PersonMaskSampling`、`PortraitWarpComposer`、`WarpDisplacementField`、`SkinProtectionMask`、`SkinMaskMath`、`SkinSmoothingMath`、`SeparableGaussian`、`SharpenMath`、`PortraitAnalysis`、`SpatialDetailParams`、`SpatialDetailPlanner`、`PassCDetailMath`、`PreviewDetailPass`、`LinearRawPreviewRenderer`）、`vision/`（`PortraitModelCatalog`、`ModelDownloadManager`、`MediaPipeDelegatePolicy`、`MediaPipePortraitEngine`、`PortraitAnalyzer`）、`render/`（`SkinMaskTexture`、`WarpTexture`、`DetailShaderSources`、`SpatialDetailGlProgram`、`SpatialDetailGlChain`、`SpatialDetailRenderer`、`PreviewDrawRequests`、`PreviewPassPipeline`、`EditorGlRenderer`、`PreviewCacheWriter`、`GeometryCpuResampler`）、`ui/`（`EditorPortraitSpecs`、`PortraitPanelsBinder`、`SkinPanelView`、`FaceReshapePanelView`、`BodyReshapePanelView`、`BodyGuideOverlayView`、`EditorPortraitCoordinator`、`EditorPortraitHost`、`EditorGestureHost`、`EditorRenderCoordinator`、`EditorActivity`）、`gradle/libs.versions.toml`、`editor/build.gradle`、`app/build.gradle`、`app/proguard-rules.pro`
- 关联调研：[`docs/research/2026-11-22-mediapipe-model-distribution.md`](../research/2026-11-22-mediapipe-model-distribution.md)、[`wayfinder/06`](../../.scratch/image-editor/wayfinder/06-portrait-mls-grid-and-mask-composition-spec.md)

## 背景

工单 12 要在「人像」面板交付端侧美肤、面部重塑与美体。约束彼此冲突：16.37 MB 的 6 类分割模型不能塞进每个用户的 APK；美肤必须在首次联网前就可用；重塑要支持合影中的多张倾斜人脸且不能拉歪邻居；美体不能引入姿态模型；`uWarpTex` 只允许 Pass A 一次采样。实现中锁定了以下非显然决策。

## 决策

### 1. 混合分发 + 三个体积护栏，下载器自带断点续传与内容校验

- APK 内置 `face_landmarker.task`（3.58 MiB）与 `selfie_segmenter.tflite`（250 KB），因此面部重塑与离线美肤从首次安装即可用；`selfie_multiclass_256x256.tflite` 在首次进入人像面板时后台下载。
- release 构建设置 `packaging.jniLibs.excludes += ["lib/x86/**", "lib/x86_64/**"]` 与 `ndk.debugSymbolLevel = "none"`：AAB 不认 `splits.abi`，默认会多打 26.36 MB 的 x86 库与 6.21 MB（双 ABI）调试符号。
- R8 加 `-keep class com.google.mediapipe.** { *; }`：`tasks-vision` AAR 不带 consumer rules，JNI 按类名反查，混淆即崩。
- `ModelDownloadManager` 的契约是「`.part` 续传 → 尺寸 + SHA-256 校验 → 原子 rename」；校验失败删除文件并返回 `null`，绝不把半包交给 MediaPipe。HTTP 走 `ModelHttpSource` 接口（生产实现 `HttpUrlConnectionSource`），使 Range/校验/清理逻辑可用纯 JVM 单测覆盖，不需要网络。

### 2. 人脸定位可用 GPU，置信度分割使用 CPU

人脸定位仅在 `reqGlEsVersion >= 0x30001` 且未列入黑名单时尝试 GPU；初始化失败后记录黑名单并重建 CPU 任务。

使用 MediaPipe Tasks 1.2.3 时，内置与六类分割均使用 CPU。真机上 GPU 任务能成功初始化，但在 JNI 拷贝 `256×256` 置信度掩码时失败：原生要求 `131072` 字节，Java 按 float32 分配 `262144` 字节。此错误早于应用读取掩码，不能仅通过初始化回退或调整应用缓冲区解决。此约束不影响人脸定位或 GLES 预览渲染；升级依赖后，须在受影响设备上验证 GPU 置信度掩码读回再放开。

### 3. MLS 相似变换采用闭式最小二乘，而不是「归一化 A(v−q*)」捷径

常见的 MLS 实现把相似变换写成 `p* + |v−q*| · A(v−q*)/|A(v−q*)|`，其中 `A = Σ w p̂ q̂ᵀ`。该式在控制点构型各向异性时**不是恒等映射**：当 `p = q` 时 `A` 是各向异性的对称正定矩阵，归一化会引入旋转，导致「所有锚点都不动」的情况下图像也被轻微扭转。

本实现改用闭式解：`M = Σ w p̂ q̂ᵀ`，最优旋转 `θ = atan2(M₁₂ − M₂₁, M₁₁ + M₂₂)`，最优尺度 `s = (cosθ·tr(M) + sinθ·skew) / Σ w|q̂|²`，`f(v) = p* + s R (v − q*)`。控制点集合恒等时 `θ = 0, s = 1`，逐位恒等；这正是 32 个图像边界锚点与每张脸 16 个 1.8× 光晕锚点能够「位移 < 1e-4」的数学前提。

### 4. 每张脸独立求解 + 半径窗口，保证合影隔离

相似变换的远场不衰减（它是一次全局最小二乘相似拟合），仅靠 1.8× 光晕锚点无法阻止一张被强烈重塑的脸把邻座人脸整体平移。因此在**每张脸独立求解**（该脸的控制点 + 其 16 个光晕锚点 + 32 个边界锚点）之后，再乘以一个径向窗口：`r ≤ 0.72` 为 1，`r = 1`（光晕环）为 0。所有真实五官都落在 `r ≤ 0.72` 内，因此不影响效果强度，却让「邻居脸之外的贡献恒为 0」成为可验证契约（`FaceMlsSolverTest` 用「单脸 vs 双脸求解在同一探测点逐位相等」来验证）。光晕锚点在求解前吸附到 64 网格顶点，求解后把这些顶点显式置零。

### 5. 双模式 `skinMask` 与保护多边形

- 5 个保护多边形（左右眼眶、左右眉毛、外唇）栅格化到 256×256 后做 3px 高斯羽化。眉毛关键点是**沿眉弓排列**的开放折线，直接闭合只能得到零面积细条，因此展开成上下各偏移 `0.012` 的带状多边形。
- 在线 `skinMask = feather(clamp(bodySkin + faceSkin − hair)) · (1 − holes) · gate`；离线 `skinMask = feather(person) · (1 − holes) · strictGate`。羽化会让孔洞内部回渗约 5%，所以羽化后**再乘一次** `(1 − holes)`，把眼眶/嘴唇内部锁到精确 0。
- 肤色门控在 OKLCH 上做，色度下限用比例膝（`0.5×min → min`）而不是整段带宽的固定比例，否则中性灰（C≈0）会通过 `0.008` 的软边被判为皮肤。离线门控（h 15–75°、C 0.02–0.18、L 0.25–0.92）严格窄于在线门控。

### 6. 美体：掩码乘积 + `C¹` 拉腿，不引入姿态模型

`y_src = y − 0.12 s (y − y_hip)² / (1 − y_hip) · P_person(x, y)`：平方项让值与斜率在髋线同时归零（`C¹` 连续），乘 `P_person` 让背景列完全不动；只在同一画幅内向上采样，因此输出宽高比不变（溢出底边自然裁掉），无需额外的内接裁切计算。瘦腰用余弦钟形在腰线附近横向收拢，中心取该行 `P_person` 的水平质心。

### 7. 统一 `uWarpTex` 的合成点放在渲染器

`PortraitWarpComposer` 只负责向量相加；`EditorGlRenderer.setPortraitWarp(face, body)` 与 `setLiquifyMesh(mesh)` 都调用同一个 `publishWarp()`，把「脸 MLS + 美体 + 液化」合成一张 `UnifiedWarpField` 交给 `WarpTexture`（原 `LiquifyWarpTexture` 更名并改为消费 `WarpDisplacementField`）。CPU 侧 `GeometryCpuResampler`、`PreviewCacheWriter`、`EditorPreviewSession`、`EditorSaveCoordinator` 全部改为接收同一个合成场，因此画布与画廊缩略图不会对形变产生分歧。

### 8. Pass C 的 Stage 0 与原 Stage 4 的蒙版复用

- `SpatialDetailStage` 新增**首位**枚举 `SKIN_SMOOTHING`，shader 的 `uStage` 相应变为 `1 = 皮肤`、`2 = 降噪`、`3 = 去雾`、`4 = 清晰度/纹理`、`5 = 锐化`。
- `uSkinMaskTex`（256×256 `GL_R8`，Tier 2 回退 `GL_LUMINANCE`）同时被 Stage 0（加权频率分离）与 Stage 4（`1 − skinMask` 抑制）采样；`SpatialDetailGlChain.needsSkinMask(stage)` 决定绑定时机。
- `SpatialDetailPlanner.plan(..., skinMaskAvailable)` 在无分析结果时**丢弃** Stage 0（与 Pre-pass 未就绪时丢弃去雾/清晰度同构）。CPU 侧 `SkinSmoothingMath.apply` 对 `skinMask == null` 直接 no-op，保证预览缓存与画布行为一致。

### 9. 分析与网格求解的线程模型

MediaPipe 只在进入「人像」面板时跑一次（512px 代理），`Mutex` 串行化「首次分析」与「模型升级后重分析」，避免重复推理。滑杆变化只重算 MLS/美体网格（`Dispatchers.Default`，单张 64×64 MLS 求解 < 2 ms），`EditorPortraitHost` 用 `portrait.reshape/body` 的签名守卫把色调、色彩、几何拖杆完全排除在求解之外。

### 10. UI 与撤销边界

- `EditorGroup.PORTRAIT` 下三个工具、三个撤销桶：`PORTRAIT_SKIN`、`PORTRAIT_RESHAPE`、`PORTRAIT_BODY`（含腰线/髋线拖动）。
- 腰线/髋线由 `BodyGuideOverlayView` 直接绘制在画布上，用归一化 `v` 保存进 `EditGraph.portrait.body.waistY/hipY`；未拖动时显示分析得到的默认位置，拖动后用户值优先。该覆盖层只在触点靠近引导线时消费事件，其余触摸穿透到下层裁剪/手势覆盖层，因此缩放、平移与长按对比不受影响。
- 进入 `PORTRAIT` 触发下载；下载 + 校验 + 热替换成功后自动重跑分析，`skinMask` 无缝从 1 类人像升级为 6 类精细蒙版。

## 测试可追溯性

| 契约 | 测试 |
|---|---|
| 0 参数全零、32 边界锚点 + 16 光晕锚点位移 < 1e-4、多人脸隔离、倾斜人脸沿本轴 | `FaceMlsSolverTest` |
| 零背景位移、髋线 `C¹`、画幅比例不变、质心初值 | `BodyWarpSolverTest` |
| 三源逐格相加、半精度上传契约 | `PortraitWarpComposerTest` |
| 眉眼唇保护、在线/离线一致性、严格门控、频率分离与 `(1−mask)` 抑制 | `SkinMaskMathTest` |
| SHA-256 不匹配拒绝并清理、Range 续传、服务端忽略 Range、已缓存复用 | `ModelDownloadManagerTest` |
| Stage 0 计划顺序、无蒙版时丢弃、Portrait 工具/撤销桶隔离 | `SpatialDetailPlannerTest`、`PortraitToolCatalogTest` |

## 后果与风险

- **未验证项**：MediaPipe 掩码内存布局（`ByteBufferExtractor` + `VEC32F1`）与真机推理结果未在设备上验证；读取失败会关闭美肤而不是崩溃，属于可接受的降级。`tasks-core` 自带 `RemoteLoggingClient` 遥测，上线前仍需抓包确认（见调研 §3.6）。
- **成本**：首次进入人像面板多一次 16.37 MB 下载；已缓存模型的完整性校验在进程内只做一次（约 50 ms / 16 MB），之后走尺寸快路径。
- **体积**：arm64 APK 预计 3.83 MB → 23.98 MB（调研投影），AAB 通过两条 release 护栏抵消 MediaPipe 的 ABI 与符号膨胀。
- **精度**：CPU `SkinSmoothingMath` 的基底层半径随分辨率变化，GLSL Stage 0 用固定 3 texel 步长的 13 tap 近似；两者视觉一致但非逐位一致，属于预览缓存与画布之间的既有容差。
