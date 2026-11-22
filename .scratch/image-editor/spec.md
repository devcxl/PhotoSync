# PhotoSync 手机端图片编辑器（30 项能力 + 联机暗房）完整技术规范

> **状态**：已锁定（Wayfinder 全图 8 张决策/调研/原型工单全部解决，零待决事项）
> **范围**：Android `minSdk 24` / `compileSdk 36`；模块 `:app` + `:editor`（新增）+ `:libraw`（扩展）
> **关联地图与一手材料**：
> - 寻路地图：[`map.md`](map.md)
> - 算法基线：[`docs/research/2026-11-21-image-editor-pipeline.md`](../../docs/research/2026-11-21-image-editor-pipeline.md)
> - MediaPipe 打包与分发实测：[`docs/research/2026-11-22-mediapipe-model-distribution.md`](../../docs/research/2026-11-22-mediapipe-model-distribution.md)（工单 [01](issues/01-mediapipe-model-packaging-and-distribution.md)）
> - `libjpeg-turbo` 流式编码与 EXIF 写回实测：[`docs/research/2026-11-22-libjpeg-turbo-streaming-exif.md`](../../docs/research/2026-11-22-libjpeg-turbo-streaming-exif.md)（工单 [02](issues/02-libjpeg-turbo-streaming-and-exif-writeback.md)）
> - 交互与状态机原型：[`prototype-editor-ui-and-state.html`](prototype-editor-ui-and-state.html)（工单 [04](issues/04-editor-ui-layout-and-gesture-prototype.md)）

---

## 1. 模块架构与边界契约（无循环依赖）

```
:app ──► :editor ──► :libraw        (:editor 只依赖 RawWrapper 与自身 contract)
  │         │
  │         └──► MediaPipe Tasks Vision (1.2.3) / ExifInterface (1.4.2)
  └──► :libraw (含 LibRaw + lcms2 + libjpeg-turbo 3.2.2)
```

### 1.1 目录与职责划分（遵守单文件 ≤ 500 行、函数 ≤ 50 行）

- **`:editor` 模块（新增 Android Library）**：
  - `domain/`（纯 Kotlin，无 Android 依赖，供 JVM 单测直接覆盖）：
    - `EditGraph.kt`、`EditValidator.kt`、`CopyMask.kt`、`Preset.kt`
    - `BradfordWbSolver.kt`、`MonotoneCubicCurve.kt`、`HslBands.kt`、`FaceMlsSolver.kt`
  - `contract/`（宿主解耦接口，由 `:app` 实现并注入，消除 `:editor → :app` 反向依赖）：
    - `EditorLaunchArgs.kt`（含 `photoId: Long, sourcePath: String, displayName: String, isRaw: Boolean`）
    - `EditStore.kt`（读写 `EditGraph` JSON、预览缓存路径与 `UserPreset` 列表）
    - `GallerySink.kt`（分配相册输出流与 `"rw"` `ParcelFileDescriptor`）
  - `lut/`：`CubeLutParser.kt`（解析 `.cube` 并预烘焙为 `33³ RGBA16F` `.lut16f` 二进制）、`LutTextureCache.kt`
  - `vision/`：`PortraitAnalyzer.kt`（MediaPipe `FaceLandmarker` + 双模式 `ImageSegmenter` + GPU→CPU 降级黑名单）、`ModelDownloadManager.kt`（HTTPS Range 断点续传 + SHA-256 校验）
  - `render/`：`GlEngine.kt`、`EglCore.kt`、`TexturePool.kt`、`PrepassAnalyzer.kt`、`ColumnBandExporter.kt`
  - `render/pass/`：`GeometryCaPass.kt`（Pass A）、`ToneColorPass.kt`（Pass B）、`SpatialDetailChain.kt`（Pass C）、`EffectsOutputPass.kt`（Pass D）
  - `export/`：`BakeExporter.kt`、`ExifCopier.kt`
  - `ui/`：`EditorActivity.kt`、`EditorViewModel.kt`、`GestureRouter.kt`、`widgets/DialSeekBar.kt`、`widgets/CurveEditorView.kt`、`widgets/HslPanelView.kt`、`widgets/CropOverlayView.kt`、`widgets/LiquifyBrushOverlayView.kt`
- **`:libraw` 模块（扩展 Native C++17）**：
  - `RawWrapper.kt` & `libraw_jni.cpp`：新增 `decodeToLinearRgba16F(path, maxLongEdge): LinearRawBuffer?` 与 `extractRawExifMetadata(path): RawExifMetadata?`
  - `jpeg_stream_writer.cpp`：封装 `libjpeg-turbo` 3.2.2 的 `JCS_EXT_RGBA` 按条带流式编码 JNI 接口（接收 `detachFd()` 或文件路径）
- **`:app` 模块（增量改动）**：
  - `data/entity/PhotoEditEntity.kt`、`data/entity/UserPresetEntity.kt`、`data/PhotoEditDao.kt`
  - `data/AppDatabase.kt`：升级 `version = 4`，注册 `MIGRATION_3_4`
  - `editor/RoomEditStore.kt`、`editor/MediaStoreGallerySink.kt`、`editor/BatchBakeWorkManager.kt`
  - `activity/MainActivity.kt`：集成顶栏 `editButton`、`photoEditedBadge`、大图长按原图对比、底部快捷预设与 `LiveTetherPreset` 栏、复制/粘贴参数及 `deleteLocalCopy` 级联清理 `cacheDir/edits/<photoId>`。

---

## 2. 联机暗房（Tethered Darkroom）工作流与 UI 交互规范

详见工单 [开放参数语义与非破坏性编辑工作流边界](issues/03-parameter-semantics-and-workflow-boundaries.md) 与 [单屏 XML/ViewBinding 编辑器交互与手势冲突原型](issues/04-editor-ui-layout-and-gesture-prototype.md)。

1. **主界面 × 编辑器闭环**：
   - 编辑器点击 **「✓ 完成」**：仅将 `EditGraph` 存入 `photo_edits` 表，并将当前屏幕渲染帧存为 `cacheDir/edits/<photoId>/<hash>.jpg`（外加 512px 缩略图），秒退回主界面；若 `EditGraph.isIdentity() == true`，则删除该照片的 `photo_edits` 记录与缓存。
   - 编辑器点击 **「导出」**：保存 `EditGraph` 并立即触发全分辨率列带流式烘焙写入系统相册（`Pictures/PhotoSync/<name>_edit.jpg`）。
   - 主界面浏览：存在 `EditGraph` 时，`ViewPager2` 与 `FilmstripAdapter` 优先加载 `preview_cache_path` 并点亮 **「已编辑」** 胶囊；**在主界面按住大图不放（`> 250ms`）实时切回未编辑原图（Before），松手恢复（After）**。
   - 主界面导出：对已编辑照片短按 `exportButton` 直接后台烘焙成片；长按弹出「导出编辑后成片 / 导出未编辑原图」。
2. **边拍边套 Look（`LiveTetherPreset`）**：
   - 用户可将当前 `EditGraph` 保存为 `UserPreset`，并在主界面快捷预设栏开启 **「⚡ 联机自动套用」**（持久化于 `SharedPreferences` 的 `pref_live_tether_preset_id`）。
   - 开启后，`CameraSyncEngine` 每同步入库一张新照片，自动为其插入绑定该预设的 `PhotoEditEntity` 并后台渲染预览缓存；`AutoFollow` 切过去时直接展示套好 Look 的成片效果。
3. **跨照片复制/粘贴（`CopyMask`）与批量烘焙队列**：
   - 点击「复制参数」默认按 `CopyMask` 复制 `{baseLook, tone, color, detail, effects, portrait.skin}`，自动排除单图构图项 `{geometry.*, portrait.reshape, portrait.body}`（长按可自定义勾选）；
   - 多选照片后可一键「粘贴参数」或加入 `WorkManager` 后台批量烘焙导出队列（RAW 并发固定为 1），复用主界面右上角进度卡片展示 `正在导出 k/N…`。
4. **编辑态联机免打扰**：
   - 用户处于 `EditorActivity` 时，后台 USB 同步静默入库（并静默应用 `LiveTetherPreset`），绝不抢焦点，仅在编辑器顶栏显示 `+N 新照片` 胶囊。
5. **画布手势路由与节流**：
   - **双指（`pointerCount >= 2`）**：在一切工具下强制优先路由给画布缩放/平移（若液化画笔刚按下且移动 `< 8dp`，立即取消该笔防误涂）。
   - **单指（`pointerCount == 1`）**：普通调色态=平移画布（长按对比原图）；裁切/拉直/透视态=拖拽控制框与刻度盘；曲线态=网格内拖拽控制点（拖出下边界删除）；液化态=画布涂抹更新 `128×128` 位移网格。
   - **节流与撤销**：拖动期绑定 `1024px` 代理纹理跑点运算（`≤ 8ms`），并以 `300ms` 窗口合并连续滑动为 1 步撤销事务（上限 50 步）；松手 `150ms` 后触发全质量 `RGBA16F` 渲染（`≤ 33ms`）。

---

## 3. 数据模型：`EditGraph` Schema v1 与 Room v4 契约

详见工单 [EditGraph JSON Schema v1 与预置滤镜格式规范](issues/05-editgraph-json-schema-and-preset-spec.md)。

- **JSON 结构、34 参数范围与归一化映射**：严格遵循工单 05 `## Answer` §1 定义。所有反序列化入口经过 `EditValidator.sanitize()`，将 `NaN`/`Inf` 重置为默认值并将数值 Clamp 至合法区间。
- **液化 O(1) 外置网格**：固定 `128 × 128` `half_float` `(dx, dy)` 网格（`65,536 B`），会话内 50 步环形缓冲占 `3.125 MiB`，保存时 Deflate 写入 `filesDir/edits/<photoId>/liquify_128x128.bin`，JSON 内仅存相对路径与 SHA-256。
- **外部 `.cube` 预编译二进制**：SAF 导入时由 `CubeLutParser` 转换为 `filesDir/luts/<lutId>.lut16f`（8B 魔数头 `"LUT16F\x01\x21"` + `33³ × 8B = 287,496 B` `RGBA16F`），渲染时直接 mmap 上传 `sampler3D`。
- **Room v4 `MIGRATION_3_4`**：新增 `photo_edits`（带 `FOREIGN KEY(photo_id) REFERENCES photos(id) ON DELETE CASCADE` 与 `photo_id` 唯一索引）与 `user_presets` 表（DDL 见工单 05 `## Answer` §3）。

---

## 4. 算法数学与 GLES 3.0 物理 4-Group Pass 渲染规范

详见 [`docs/research/2026-11-21-image-editor-pipeline.md`](../../docs/research/2026-11-21-image-editor-pipeline.md) §4 及工单 [GLES 3.0 6-Pass Shader 契约与 Pre-pass 列带分块渲染规范](issues/07-gles3-shader-contracts-and-column-band-tiling-spec.md)。

### 4.1 物理 4-Group Pass 管线

1. **Pass A（`GeometryCaPass`，逻辑阶段 ①）**：
   - 输入：已按 EXIF/LibRaw 一次性转正（`Orientation=1`）的源纹理 `uSrcTex` + `128×128 GL_RG16F` 组合位移纹理 `uWarpTex`（$\Delta uv_{\text{face}} + \Delta uv_{\text{body}} + \Delta uv_{\text{liquify}}$）。
   - 执行：从列带/画布局部坐标经 `uTileUvTransform` 得 Post-crop 全局坐标 $uv_{\text{global}}$ → 乘旋转/拉直/透视逆单应矩阵 $H^{-1}$（自动内接最大无黑边矩形）得 Pre-crop 原图坐标 $uv_{\text{pre}}$ → 叠加 `texture(uWarpTex, uv_pre).xy` → 绕原图光心 $(0.5, 0.5)$ 按 $1 + k_{R/B}\cdot r^2$ 分离采样 R/G/B 消除横向色散。
2. **Pass B（`ToneColorPass`，逻辑阶段 ② + ③ 合并）**：
   - **RAW 基准 Look**：当 `isRaw && baseLook == "camera_default"` 时，先乘 `uAutoBrightGain`（最高 +2EV），并在显示域叠加默认基准曲线（$\gamma = 0.78, k_S = 0.12, \text{vibrance}_0 = 0.18$），确保 0 参数与浏览页观感一致。
   - **线性影调**：乘 CPU 预计算的 **D65 锚定相对 Bradford 白平衡矩阵**（`temp=0, tint=0` 时严格为单位阵 $I$）→ 曝光 `exp2(uExposure)` → 亮度中灰高斯增益 → 高光/阴影/白色色阶在线性域（未截断前）缩放 → `softShoulder` 软肩部滚降收敛高光余量。
   - **显示域色彩**：sRGB 编码 → 黑色色阶暗部趾部伸缩（锚定 0 端点，不抬死黑）→ 正负分段对比度（`uContrast >= 0` 用 `smoothstep`，`< 0` 用线性中灰收敛）→ 光感前两段（$y^{1/(1+0.20g)} + 0.10g\cdot y(1-y)^2$）→ 1024 项 Fritsch–Carlson 单调三次 1D 曲线 LUT（先 RGB 复合再单通道）→ 线性域比例带回 RGB + **软色域压缩（防单通道 `> 1` 截断色相漂移）** → 转 OKLCH 做饱和度、自然饱和度（含 `clamp(1-C/0.32,0,1)` + 暗部抑制 + 肤色保护）、8 色带 HSL（**色相与明度必乘 `smoothstep(0.01, 0.05, C)` 低色度门控**）→ 转笛卡尔 OKLab `(L, a, b)` 做色调分离向量叠加 → 纵向色散高梯度紫/绿边去饱和（`defringe`）→ 褪色（`Fade`，抬高纯黑至 `0.12f` 并降饱和）。
3. **Pass C（`SpatialDetailChain`，逻辑阶段 ④，全 0 时 0 Draw Call 短路）**：
   - 开头先执行 **人像美肤频率分离**：引导滤波（$r=8..16$）分离 `base` 与 `det = I - base`，按 `skinMask` 压制高频 `out = base + det * (1 - s * M_skin)` 并单独大半径平滑 CbCr 色度；
   - **降噪 NR**：亮度 $3\times 3$ 引导滤波 + 色度 CbCr $5\times 5$ 可分离边缘引导滤波；
   - **去雾 Dehaze**：双线性采样 1024px Pre-pass 算出的全局大气光 $A$ 与粗透射率 `uPrepassTex.b`（以当前全清亮度做快速引导上采样），计算 $J = (I - A)/\max(t, 0.1) + A$；
   - **清晰度 Clarity & 纹理 Texture**：从 `uPrepassTex.rg` 采样 2% 短边与 0.3% 短边低频引导层，高通差值限幅 `clamp(Y - base, -0.08, 0.08)` 后按中间调权重叠加（其中 `clarity_eff = clarity + 0.20 * glow`）；
   - **锐化 Sharpen**：仅作用于 Y 通道的反锐化掩模 + 梯度门控 + **乘 `(1 - M_skin)` 抑制皮肤区锐化**。
4. **Pass D（`EffectsOutputPass`，逻辑阶段 ⑤ + ⑥ 合并）**：
   - `.cube` 3D LUT **四面体插值**（基准网格索引 `ivec3 i = clamp(ivec3(floor(p)), ivec3(0), ivec3(int(size) - 2))`，严防 `c = 1.0` 时 `texelFetch` 越界）；
   - **全局坐标颗粒 Grain**：基于 Post-crop 全局坐标 $uv_{\text{global}} \cdot \text{cropSize} / \text{size} + \text{seed}$ 生成噪声并乘 $\sqrt{4Y(1-Y)}$ 中间调包络；
   - **Post-crop 晕影 Vignette**：按 `roundness` 修正宽高比后的径向距离 $r$，在 `[mid - 0.5*feather, mid + 0.5*feather]` 上做 `smoothstep`；
   - **输出量化**：sRGB 编码 + `1/255` 三角抖动 + 量化为 `RGBA8`。

---

## 5. 人像视觉管线与模型混合分发规范

详见 [`docs/research/2026-11-22-mediapipe-model-distribution.md`](../../docs/research/2026-11-22-mediapipe-model-distribution.md) 及工单 [MediaPipe 人像模型体积、许可证与 GitHub APK/AAB 双渠道分发事实调研](issues/01-mediapipe-model-packaging-and-distribution.md)、[人像关键点 MLS 网格与美肤/美体遮罩合成规范](issues/06-portrait-mls-grid-and-mask-composition-spec.md)。

1. **混合分发方案（兼顾 GitHub Release 直发 APK 与 AAB）**：
   - **随 APK 内置（`assets/models/`）**：`face_landmarker.task`（3,758,596 B，deflate 后 3.33 MB，Apache-2.0）+ `selfie_segmenter.tflite`（249,537 B，FP16，Apache-2.0）。
   - **按需 HTTPS 下载（`filesDir/models/`）**：首次进入人像面板时后台下载 `selfie_multiclass_256x256.tflite`（16,371,837 B，FP32，Apache-2.0，支持 HTTP Range 断点续传，强制校验 SHA-256 `c6748b121820473ff290c3eab6766d34aad76fd246baa1bf6bef92fb73eb838c` 或官方最新固定哈希后原子重命名）。**不引入** `deeplab_v3.tflite` 与 `pose_landmarker_full.task`。
   - **Gradle 构建护栏**：
     - `packaging.jniLibs.excludes += ["**/x86/**", "**/x86_64/**"]`（防止 AAB 混入模拟器架构膨胀 26.36 MB）；
     - `buildTypes.release.ndk.debugSymbolLevel = "none"`（防止 AAB 混入调试符号膨胀 6.21 MB）；
     - `proguard-rules.pro` 显式添加 `-keep class com.google.mediapipe.** { *; }`。
2. **双模式美肤、478 点多人脸 MLS 与美体双引导线**：
   - 16.37 MB 模型未就绪前，直接用内置 `face_landmarker` + `selfie_segmenter` + OKLCH 肤色门控合成离线 `skinMask`；下载完成后无缝切至 6 类精细 Mask（`body-skin ∪ face-skin - hair - eyeLipBrow`）。
   - `FaceLandmarker` 设 `numFaces = 5`，按旋转不变局部人脸坐标系与工单 06 索引表求解 `64×64` MLS 相似变换网格（人脸外围 $1.8\times$ 与图像四边布设不动锚点）。
   - 美体由人脸框+人体 Mask 质心自动定腰线 `yWaist` 与髋线 `yHip` 初值，并在画布提供两条可拖拽水平引导线微调；拉腿保持输出画幅比例不变并自动裁切底边外溢。

---

## 6. 全分辨率流式分块烘焙导出与 EXIF 写回规范

详见 [`docs/research/2026-11-22-libjpeg-turbo-streaming-exif.md`](../../docs/research/2026-11-22-libjpeg-turbo-streaming-exif.md) 及工单 [NDK libjpeg-turbo 流式编码与 ExifInterface FileDescriptor 写回技术调研](issues/02-libjpeg-turbo-streaming-and-exif-writeback.md)、[GLES 3.0 6-Pass Shader 契约与 Pre-pass 列带分块渲染规范](issues/07-gles3-shader-contracts-and-column-band-tiling-spec.md)。

1. **`libjpeg-turbo 3.2.2` CMake 集成**：
   - 作为 `libraw/src/main/cpp/third_party/libjpeg-turbo` submodule 引入，在 `:libraw` 的 `CMakeLists.txt` 中通过 `ExternalProject_Add` 编译 `libjpeg.a`（开启 `WITH_SIMD=ON`，关闭 `WITH_TURBOJPEG/ENABLE_SHARED`）并以 `IMPORTED` 静态库链接，配合 `-Wl,--exclude-libs,ALL`（`arm64-v8a` stripped `.so` 仅增 330 KiB）。
   - **编码器铁律**：必须设置 `cinfo.optimize_coding = FALSE`、禁用 `jpeg_simple_progression()`、输入色空间用 `JCS_EXT_RGBA`、质量 `95`，确保编码器自身内存恒为 $O(1)$（`< 300 KiB`），杜绝 291 MiB 全图 DCT 系数分配。
2. **三档源图驻留 + 「`1920×512 (halo=64)` 列带 + `GL_PACK_ROW_LENGTH` 散射」**：
   - 先跑 1 次 `1024px` 全图 Pre-pass 生成 `uPrepassTex`（去雾全局大气光 $A$、粗透射率 $t_{low}$、清晰度/纹理低频层）；
   - 按工单 07 §3 的三档策略驻留源图（≤26MP 整图驻留 GPU；26–65MP Native `RGB16` 驻留+按列带逆映射包围盒动态切片上传 GPU；>65MP 启用 LibRaw `half_size=1`）；
   - 按行条带（512 高）× 列带（1920 宽，外扩 64px Halo → FBO 物理尺寸 $\le 2048 \times 640$）渲染 Pass A→D，用 `glReadPixels(64, 64, W_valid, H_valid)` + `GL_PACK_ROW_LENGTH = W_crop` + `GL_PACK_SKIP_PIXELS = x0` 剥离 Halo 并直接散射进全宽行缓冲，满一条带调一次 `jpeg_write_scanlines`。
3. **EXIF 写回契约（API 24–28 vs API 29+）**：
   - **API 29+**：`insert(IS_PENDING=1)` → `openFileDescriptor(uri, "w")` 交 native 写完 JPEG 并关闭 → **重新以 `openFileDescriptor(uri, "rw")`（可 seek）打开**交 `ExifInterface(pfd.fileDescriptor)` → 按显式标签白名单（排除 `TAG_MAKER_NOTE`；RAW 源从 LibRaw `idata/other/lens` 提取，时间戳用 `localtime()` 反解）写入拍摄属性 → **强制设置 `TAG_ORIENTATION = "1"` 及裁切后 `TAG_IMAGE_WIDTH/LENGTH`** → `saveAttributes()` → 关闭 FD → `update(IS_PENDING=0)`（异常时 `delete(uri)`）。
   - **API 24–28**：先写 `<name>.jpg.tmp` → `ExifInterface(tmpFile)` 写回属性并 `saveAttributes()` → `renameTo(finalFile)` → `MediaStore.insert`。

---

## 7. GPU 4 级降级矩阵、金标验收测试与分期实施路径

详见工单 [GPU 降级矩阵、金标验收测试集与最终规范总装](issues/08-gpu-fallback-matrix-and-golden-acceptance-spec.md)。

- **4 级安全降级矩阵**：Tier 1（`GL_RGBA16F` FBO）→ Tier 2（无 `half_float` FBO 时回退 `GL_RGBA8` FBO + 每 Pass 三角抖动）→ Tier 3（Shader 编译失败回退最小几何+影调管线并 `Timber.e` 记录 GPU 签名）→ Tier 4（MediaPipe GPU 异常自动写入 `SharedPreferences` 黑名单并永久回退 `Delegate.CPU`）。
- **金标验收套件**：执行工单 08 §2 定义的 6 项 JVM 单测与 4 项 Android Instrumented 测试，通过门禁为 `./gradlew testDebugUnitTest lintDebug connectedAndroidTest`。
- **分期实施路径（P0 → P3）**：
  1. **P0（基础管线 + 联机预设闭环 + 无损 EXIF 导出）**：`:editor` 骨架与契约接口、`:libraw` `decodeToLinearRgba16F` + `libjpeg-turbo` `ExternalProject_Add`、Room v4（`photo_edits` + `user_presets`）、Pass A（旋转/翻转/裁切/拉直）+ Pass B（`BaseLook`、Bradford 色温/色调、曝光/亮度/对比度/高光/阴影/白黑点/光感、饱和度/自然饱和度）+ Pass D（输出抖动）、列带流式导出 + EXIF 写回、主界面 `editButton` / `LiveTetherPreset` / 复制粘贴 / 长按对比原图。
  2. **P1（专业调色 + 空间细节 + 滤镜与光学）**：1024px Pre-pass 金字塔、曲线（Fritsch–Carlson 1D LUT）、8 色带 OKLCH HSL（带色度门控）、OKLab 色调分离、褪色、Pass C（清晰度/纹理/锐化/降噪）、透视校正、横向色散（Pass A）+ 纵向去紫边（Pass B）、晕影、全局颗粒、`.cube` 3D LUT 导入与四面体采样、后台批量烘焙队列。
  3. **P2（端侧人像 + 液化）**：MediaPipe `tasks-vision:1.2.3` 集成（含 ABI 过滤与 R8 规则）、内置 `face_landmarker` + `selfie_segmenter` 与按需下载 `selfie_multiclass_256x256`、双模式美肤频率分离（联动抑制皮肤锐化）、478 点 5 人脸 64×64 MLS 面部重塑、美体（头身比+质心初值+双引导线）、128×128 液化画笔网格与 O(1) 外置存储。
  4. **P3（去雾 + 进阶增强）**：Pre-pass 暗通道全局大气光 $A$ 去雾、自动横向色散估计、宽色域 Display P3 预览适配与 XMP `crs:` 侧车导出。
