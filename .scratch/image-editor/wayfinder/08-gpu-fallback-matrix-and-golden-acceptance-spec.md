# GPU 降级矩阵、金标验收测试集与最终规范总装

Type: grilling
Status: resolved
Blocked by: 07

## Question

作为通往目的地（`.scratch/image-editor/spec.md`）的最后一张收官工单，已从迷雾区毕业。需在 6-Pass Shader 与分块渲染规范锁定后，敲定最后的健壮性降级与自动化验收契约，并将全图所有已解决工单总装为 `.scratch/image-editor/spec.md`：
1. **GPU 能力探测与分级回退矩阵**：
   - 无 `GL_EXT_color_buffer_half_float`（或 FBO 完整性检查失败）时回退 `RGBA8` + 输出三角抖动的精确策略；
   - `GL_MAX_TEXTURE_SIZE` 分档（2048 / 4096 / 8192 / 16384）对预览工作纹理与导出源图驻留档位（全幅 vs `half_size=1`）的自动裁剪上限；
   - Shader 编译/链接异常或 MediaPipe GPU Delegate 抛 `MediaPipeException` 时的回退与 `Timber.e` 诊断日志字段。
2. **自动化金标验收测试集（Golden Acceptance Suite）契约**：
   - JVM 单测（`EditValidator`、曲线 Fritsch–Carlson 单调插值、Bradford D65 恒等矩阵、`.cube` 畸形文件拒绝、`CopyMask` 过滤）；
   - Android Instrumented 测试（`MIGRATION_3_4` 数据库迁移 + 级联删除、0 参数恒等断言 $\Delta \le 1/255$、整幅 vs `2048×512` 列带分块导出逐像素差 $\le 1$、8 种 EXIF Orientation 导出方向与拍摄元数据保留断言）。

## Answer

### 1. GPU 运行时探测与 4 级安全降级矩阵

| 等级 | 运行时探测条件 | 渲染与推理行为 | 诊断与可观测性 (`Timber`) |
|---|---|---|---|
| **Tier 1（完整 FP16 管线）** | `EGLContext` 版本 $\ge 3.0$，扩展含 `GL_EXT_color_buffer_half_float` 且创建 `GL_RGBA16F` FBO 后 `glCheckFramebufferStatus == GL_FRAMEBUFFER_COMPLETE` | 源纹理、1D/3D LUT、所有中间 FBO 均使用 `GL_RGBA16F`；仅最终 Pass D 输出 `GL_RGBA8` | `Timber.i("GlCapabilities: Tier 1 RGBA16F active, maxTex=%d, renderer=%s", maxTex, renderer)` |
| **Tier 2（8-bit 中间缓冲 + 抖动回退）** | GLES 3.0 可用，但无 `GL_EXT_color_buffer_half_float` 或 `RGBA16F` FBO 不完整 | 源纹理与 1D/3D LUT 仍用 `GL_RGBA16F` 采样（ES 3.0 核心必支持）；中间 FBO 回退 `GL_RGBA8`，且每个 Pass 输出追加 `1.0 / 255.0` 伪随机三角抖动抑制 8-bit 量化色带 | `Timber.w("GlCapabilities: Tier 2 RGBA8 FBO fallback (no half_float FBO), renderer=%s", renderer)` |
| **Tier 3（最小安全 Shader 兜底）** | 特定驱动上 Pass C（空间细节）或 3D LUT `glCompileShader`/`glLinkProgram` 失败 | 自动旁路 Pass C 或禁用 `.cube` 3D LUT 采样，回退至仅含几何变换 + 基础影调/色彩的 Minimal Shader，保证永不黑屏或崩溃 | `Timber.e("Shader compile/link failed on %s (%s): %s", renderer, glVersion, infoLog)` |
| **Tier 4（MediaPipe 推理回退）** | 默认 `Delegate.CPU`；仅当 `ActivityManager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30001` 且 `prefs.getBoolean("mp_gpu_blacklisted", false) == false` 时尝试 `Delegate.GPU` | 捕获 `MediaPipeException` / `RuntimeException` 后立即将 `mp_gpu_blacklisted = true` 写入 `SharedPreferences`，无缝重建 `Delegate.CPU` 实例继续推理 | `Timber.w(e, "MediaPipe GPU delegate failed on %s, permanently falling back to CPU", Build.MODEL)` |

### 2. 自动化金标验收测试集（Golden Acceptance Suite）

1. **JVM 单元测试（`:editor/src/test/`，零 Android 设备依赖，CI 秒级执行）**：
   - `EditValidatorTest`：验证全部 34 个参数对 `NaN`、`±Infinity`、越界值的 Clamp 行为，以及 `EditGraph.isIdentity()` 在各分支下的准确性；
   - `BradfordWbMatrixTest`：断言当 `temp = 0, tint = 0` 时，生成的 $3 \times 3$ 线性 sRGB 白平衡矩阵与单位阵 $I$ 的最大元素绝对误差 $< 10^{-6}$；断言 `temp > 0` 时红/蓝通道增益比单调递增；
   - `MonotoneCubicCurveTest`：随机生成 500 组控制点（含相邻陡峭阶跃），断言 Fritsch–Carlson 1024 项 1D LUT 无过冲（插值结果严格落在相邻控制点区间内）且在两点 `[(0,0),(1,1)]` 时与线性恒等差 $< 10^{-5}$；
   - `CubeLutParserTest`：验证标准 $33^3$ `.cube` 解析正确性，并断言畸形输入（行数不足、`LUT_3D_SIZE > 65`、`DOMAIN_MIN >= DOMAIN_MAX`、含非法字符）全部返回 `Result.failure` 而不抛未捕获崩溃；
   - `FaceMlsSolverTest`：验证 64×64 网格在全部重塑参数为 0 时位移全为 0，在非零参数下图像四条外边界与人脸外围 $1.8\times$ 锚点处位移幅度 $< 10^{-4}$；
   - `CopyMaskTest`：验证默认 `CopyMask` 复制时精确保留影调/色彩/美肤并剔除 `crop`、`perspective`、`liquify`、`reshape`、`body`。
2. **Android Instrumented 测试（`:app/src/androidTest/` & `:editor/src/androidTest/`）**：
   - `Migration3To4Test`：在 Room v3 库中插入 `PhotoEntity`，执行 `MIGRATION_3_4`，插入关联 `PhotoEditEntity` 与 `UserPresetEntity`，验证删除 `PhotoEntity` 后 `photo_edits` 行被 `ON DELETE CASCADE` 自动级联清除；
   - `PipelineIdentityTest`：对标准测试图在所有参数为 0（JPEG 输入）下跑完整 GLES 离屏渲染，断言输出与原图逐像素最大绝对差 $\le 1/255$（99.9% 像素差为 0）；
   - `ColumnBandTilingConsistencyTest`：开启清晰度（`+40`）、去雾（`+30`）、颗粒（`+25, seed=42`）、晕影（`-35`）与横向色散（`+20`），对比单张整幅 FBO 导出与 `1920 × 512 (halo=64)` 列带分块导出，断言接缝处及全图逐像素最大差 $\le 1/255$；
   - `ExifOrientationAndMetadataRoundtripTest`：构造 8 种 EXIF `Orientation (1..8)` 的测试 JPEG 与测试 RAW，经导出链路写盘后用 `ExifInterface` 读取产物，断言 8 种输入导出后的 `TAG_ORIENTATION` 均为 `1 (ORIENTATION_NORMAL)`、画面方向全部正向一致，且 `DateTimeOriginal`、`Make`、`Model`、`FNumber`、`ISOSpeedRatings` 均完整保留、无 `TAG_MAKER_NOTE` 损坏。

### 3. 最终规范总装

全图 8 张工单的所有决策、数学公式、架构契约与验收标准已完整总装至：
[`.scratch/image-editor/spec.md`](../spec.md)。
