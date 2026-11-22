# 0005 — Pass D 效果与输出量化、`.cube` 3D LUT 预烘焙与 GPU 4 级降级

- 状态：已采纳
- 日期：2026-11-22
- 影响范围：`editor/src/main/java/cn/devcxl/photosync/editor/domain/`（`CubeLut`、`CubeLutParser`、`Lut16fCache`、`PassDEffectsParams`、`PassDEffectsMath`、`FilmNoise`）、`editor/src/main/java/cn/devcxl/photosync/editor/lut/`（`CubeLutStore`、`Lut3dTexture`）、`editor/src/main/java/cn/devcxl/photosync/editor/render/`（`GlCapabilities`、`OffscreenColorTarget`、`PreviewPassPipeline`、`EffectsOutputGlProgram`、`EffectsOutputShaderSources`）、`editor/src/main/java/cn/devcxl/photosync/editor/ui/`（`LutPanelView`、`GrainPanelView`、`VignettePanelView`、`EditorLutImportCoordinator`）

## 背景

工单 07 在 Pass B2 之后交付物理 Pass D：外部 `.cube` 3D LUT、Post-crop 胶片颗粒、Post-crop 晕影以及 `1/255` 三角抖动量化，并补齐 GPU 运行时 4 级降级矩阵。落地时发现规范中的若干处需要明确取舍，否则 CPU 预览与 GLSL 会分叉。

## 决策

### 1. Pass D 消费显示域 sRGB，只负责量化抖动，不二次编码

工单 02–06 已让 Pass B 的 `toneDisplay` / `applyColorGrading` 输出已编码的 sRGB 数值（写入 `RGBA16F` FBO 时不做硬件 sRGB 转换）。因此 Pass D 的 `uTex0` 就是显示域 sRGB：

1. 若在 Pass D 再执行一次 sRGB 编码会双重提亮，破坏与 `PreviewCacheWriter` 的像素一致性；
2. `.cube` LUT 本身也是 display-referred（社区 LUT 均按 sRGB 编码制作）；
3. 规范中“最终 sRGB gamma 编码”由 Pass B 的出口承担，Pass D 只做“输出量化 + 三角抖动”。

结论：Pass D 管线为 `clamp(sRGB) → 3D LUT（按强度 mix）→ 颗粒 → 晕影 → ±1/255 三角抖动 → RGBA8`。

### 2. Pass D 激活时 Pass B 必须先写入离屏 FBO

颗粒与晕影必须使用 Post-crop 全局 UV `[0,1]²`，而直接画到屏幕的 Pass B 带有 letterbox 缩放与画布平移偏移，`vTexCoord` 仍是图像 UV 但屏幕分辨率/偏移会让颗粒在缩放时漂移。因此当 `PassDEffectsParams.isIdentity == false` 时：

1. `EffectsOutputStage` 用 `OffscreenColorTarget` 按 letterbox 后的图像矩形分配中间 FBO（Tier 1 `RGBA16F`，Tier 2 `RGBA8`）；
2. Pass B 以 `scale=(1,1), offset=(0,0)` 全屏写入该 FBO；
3. Pass D 再以真实 letterbox `scale/offset` 画到屏幕，并在 FBO 的 `[0,1]²` UV 上算颗粒/晕影。

效果全为 0 时跳过整个 Pass D，Pass B 直接上屏，省一次全屏 FBO 往返。

### 3. `.cube` 导入策略：严格拒绝 + 固定 33³ 预烘焙

- 仅接受 `TITLE`、`LUT_3D_SIZE ∈ [2, 65]`、`DOMAIN_MIN/MAX` 与 3 元浮点数据行；`LUT_1D_SIZE`、未知行、非有限值、数量不符、`DOMAIN_MIN >= DOMAIN_MAX`、超过 8 MiB 文本全部返回 `Result.failure`，导入器显示本地化错误而不是崩溃。
- 预烘焙固定为 `33³ RGBA16F`：8 字节 ASCII 魔数 `PSLUT16F` + `33³ × 4 × 2 = 287,496` 字节小端 half-float，合计 `287,504` 字节。固定网格让 `EditGraph` 只存 id 与强度，纹理尺寸恒定，`texelFetch` 索引可静态推断。
- 缓存写入先落 `*.lut16f.tmp` 再 `renameTo` 原子发布；`CubeLutStore.installedLuts()` 结果缓存到下一次导入为止，避免每次滑杆刷新都列目录。
- `Lut3dTexture` 使用 `GL_NEAREST` + `GL_TEXTURE_3D`：四面体插值自行取 8 个整数角点，任何硬件过滤都会污染相邻格。

### 4. 四面体插值：基准格严格 Clamp 至 `size - 2`

`PassDEffectsMath.sampleTetrahedral` 与 GLSL 均先 `pos = clamp(c, 0, 1) * (size - 1)`，再 `cell = clamp(floor(pos), 0, size - 2)`，小数部分 `f = pos - cell`。

这样 `c = 1.0` 时 `f = 1.0`，只读 `cell` 与 `cell + 1` 两个合法切片，彻底避免 `texelFetch(i + 1)` 越界；单测覆盖 2³ LUT 的 `(1,1,1)` 角点与仅红通道落在最后切片的混合路径。

### 5. 晕影形状与过渡带公式（CPU/GLSL 同步）

- `rel = (uv - 0.5) * 2`，`phys = (rel.x * aspect, rel.y)`；
- 椭圆半径 `length(phys) / length(aspect, 1)`、矩形半径 `max(|phys.x|, |phys.y|) / max(aspect, 1)`、归一化圆半径 `length(rel) / sqrt(2)`；
- `roundness ∈ [-1, 1]`：负值把椭圆混向矩形，正值混向归一化圆；三种形状在四角均严格等于 `1`；
- 过渡带 `halfFeather = clamp(feather * 0.5, 1e-4, 0.5)`，`t = smoothstep(mid - halfFeather, mid + halfFeather, radius)`，`gain = max(1 + amount * t, 0)`。

`amount < 0` 压暗四角、`amount > 0` 提亮，符合 Lightroom Post-crop Vignetting 的方向约定。

### 6. 颗粒：确定性双八度值噪声 + 中间调包络

- 频率随 `size` 在 `1400`（最细）与 `120`（最粗）之间线性插值，图案只由 `(u, v, seed)` 决定，与分辨率无关；
- 第二八度频率 ×2 并按 `roughness` 混入 `(coarse + 0.5 * octave) / 1.5`，再映射到 `[-1, 1]`；
- 幅度 `amount * 0.06 * sqrt(4Y(1-Y))`，纯黑与纯白自动归零；
- CPU `FilmNoise` 与 GLSL `hash21/valueNoise` 的结构、频率与混合系数逐项对齐；不要求浮点位级一致，但同一 `seed` 在预览与导出上呈现同一统计图案。

### 7. GPU 4 级降级矩阵落地方式

- `GlCapabilities.refresh()` 在 EGL 上下文创建后探测 `GL_EXT_color_buffer_half_float` / `GL_EXT_color_buffer_float` / GLES 3.1+，记录 Tier 1 或 Tier 2，并输出 `maxTex` / `renderer`。
- `OffscreenColorTarget` 是 Pass A 与 Pass D 共用的通用离屏目标：Tier 1 `RGBA16F`，Tier 2 `RGBA8`；FBO 不完整时禁用该 Pass 并回退。
- Tier 2 下每个 Pass 在输出前追加 `1/255` 三角抖动：Pass A 在 sRGB 编码分支抖动、Pass B 通过 `uDitherAmp` 抖动、Pass D 恒定抖动。
- Tier 3：Pass D 着色器编译/链接失败时 `GlCapabilities.markMinimalFallback` 以 `Timber.e` 记录 `renderer` / `glVersion` / `maxTex`，`EffectsOutputStage.isReady == false`，预览退回 Pass A + Pass B（既有 `ExposureGlProgram` 的最小兜底保持原样）。

### 8. UI 归属与撤销分桶

- `EditorTool.LUT` 位于 `COLOR` 页，`LutPanelView` 提供 SAF 导入按钮、LUT chips（含“无”）与 `0..100` 强度滑杆；未绑定 LUT 时强度滑杆禁用。
- `EditorTool.GRAIN` / `EditorTool.VIGNETTE` 位于 `DETAIL` 页，分别用 `GrainPanelView`（数量/大小/粗糙度）与 `VignettePanelView`（数量/中点/羽化/圆度）承载。
- 三个面板各自使用 `EditParameterKey.COLOR_LUT` / `DETAIL_GRAIN` / `DETAIL_VIGNETTE` 合并窗口，双指缩放不会误触面板。
- 子工具 chips 与顶栏从 `EditorPanelController` / `EditorActivity` 抽出为 `SubToolChipRow`、`EditorTopBarBinder`、`EditorCanvasBinder`、`EditorLutImportCoordinator`，使每个类保持在 300 行以内。

## 结果

- 新增 `CubeLutParser`（严格校验 + 33³ 预烘焙）、`Lut16fCache`/`Lut3dSamples`、`PassDEffectsParams`/`PassDEffectsMath`/`FilmNoise`。
- 新增 `CubeLutStore`、`Lut3dTexture`、`EffectsOutputGlProgram`/`EffectsOutputShaderSources`、`EffectsOutputStage`、`GlCapabilities`、`PreviewPassPipeline` 与通用 `OffscreenColorTarget`（由 `PassAFrameBuffer` 更名）。
- CPU 预览缓存（`PreviewCacheWriter` / `LinearRawPreviewRenderer`）与 GLSL 使用同一套 Pass D 数学。
- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 全绿；单测覆盖 `.cube` 正常/畸形解析、`.lut16f` 布局、四面体 `c = 1.0` 边界、先裁切后晕影顺序、确定性颗粒 seed 与中间调包络、目录工具分桶。

## 已知遗留

1. **端到端 GPU 像素对比待真机验证**：`EffectsOutputShaderSources` 与 `PassDEffectsMath` 目前靠逐常量对齐锁定语义；整幅 vs 列带分块导出的 ≤1/255 断言留给工单 09 的 headless GLES 金标套件。
2. **颗粒统计一致性而非位级一致性**：CPU `FilmNoise` 使用整数哈希，GLSL 使用浮点 `hash21`；同一 seed 的图案分布一致，但逐个坐标的噪声值不保证相等（不影响 ≤1/255 金标容差判断的是统计包络与幅度，而非逐像素相同）。
3. **LUT 列表命名**：缓存文件名即 chip 显示名（保留 Unicode 字母/数字/空格/`_`/`-`），未额外持久化 `.cube` 的 `TITLE`，避免在 `.lut16f` 头部超出 8 字节契约。
