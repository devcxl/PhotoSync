# 0006 — Pass C 空间细节链、1024px 全图 Pre-pass 与 0 Draw Call 短路

- 状态：已采纳
- 日期：2026-11-22
- 影响范围：`editor/src/main/java/cn/devcxl/photosync/editor/domain/`（`SpatialDetailParams`、`SpatialDetailPlanner`、`PrepassMath`、`LowFrequencyPyramid`、`GuidedFilter`、`ImageFilters`、`ChromaNoiseReducer`、`PassCDetailMath`、`PreviewDetailPass`）、`render/`（`PrepassAnalyzer`、`PrepassTexture`、`SpatialDetailGlChain`、`SpatialDetailGlProgram`、`DetailShaderSources`、`SpatialDetailRenderer`、`PreviewPassPipeline`、`PresentGlProgram`、`CanvasLetterbox`）、`ui/`（`EditorSpatialDetailSpecs`、`DetailPanelsBinder`、`SharpenPanelView`、`NoiseReductionPanelView`、`SubToolChipRow`）

## 背景

工单 08 在 Pass A（几何/色散）与 Pass B（线性影调 + 显示域色彩）之后接续物理 Pass C：引导滤波降噪、暗通道去雾、清晰度/纹理高通与 Y 通道锐化。该阶段必须同时满足三项硬约束：① 去雾与清晰度依赖全局量，不能按 tile 各算一份，否则分块导出出现拼缝；② CPU 画廊预览缓存必须与 GL 画布一致；③ 细节组与光感全为 0 时必须 0 次 FBO Draw Call。实现过程中锁定了以下非显然决策。

## 决策

### 1. Pre-pass 在 CPU 侧分析，GL 只负责提供 1024px 帧

`PrepassAnalyzer` 在 GL 线程把 Pass A + Pass B 渲染进一张 `GL_RGBA8` 的 1024px 目标，`glReadPixels` 回读后交给纯 Kotlin 的 `PrepassMath`：
- 暗通道前 `0.1%`（`ceil(65536 * 0.001) = 66` 格）的**精确**百分位选择与逐通道最大值在 CPU 上是 `O(n log n)` 的确定性操作，用直方图着色器近似反而会引入平台相关的抖动；
- 回读代价为 4 MiB / 每次图修订（松手定格或导出启动时一次），不属于逐帧开销；
- 同一份 `PrepassMath` 直接服务 `PreviewDetailPass`，天然保证预览缓存与画布的大气光/透射率一致。

回读缓冲按 GL 的自下而上行序解码后**垂直翻转**成 top-down，使编辑器中所有图像缓冲的行序统一（`row 0 = 图像顶边`）。

### 2. 亮度降噪采用「单窗口」引导滤波，与 GLSL 逐位对齐

完整 guided filter 需要对 `a`、`b` 系数再做一次窗口均值，在 GLSL 里等价于 81 次纹理采样。Pass C 的亮度 NR 因此定义为单窗口形式 `a * (y - mean) + mean`，`a = var / (var + ε)`：平坦噪声 `a → 0` 等同局部均值，真实边缘 `a → 1` 保留原值。CPU 与 GLSL 用同一表达式，避免「画布与缓存不一致」这一类只能靠肉眼发现的问题。完整半径 8 guided filter 只出现在 Pre-pass 的透射率平滑（仅 CPU，一次性）。

### 3. 降噪的亮度与色度从同一份源采样解析

若按「先写亮度 NR 结果、再对结果做色度 5×5」实现，GLSL 需在每个色度 tap 上重算 3×3 亮度引导（25 × 9 次采样）。因此亮度与色度均从**输入帧**解析，在一次循环中同时混合后重建 RGB：`ChromaNoiseReducer.split/smooth/merge` 与 GLSL `noiseReduction` 一一对应。`detail = 100` 时亮度支路整体跳过（而不是「平滑后再原样加回」），保证该档位逐位恒等。

### 4. Pass C 先离屏渲染 Pass B，再由 `PresentGlProgram` 上屏

Pass C 需要采样帧边界外的邻居，直接画到默认 framebuffer 会把视口清屏色当成邻居。因此 `SpatialDetailRenderer` 把 Pass B 渲染进离屏目标（尺寸 = 画布 letterbox 后的图像矩形，长边 ≤ 2048），链末端再由一个只做纹理拷贝的 `PresentGlProgram` 按同一 letterbox 变换上屏。`uTileUvTransform` 已预留给工单 09 的列带全局 UV 偏移。

### 5. 短路、代理跳过与可观测计数

- `SpatialDetailParams.isZeroDrawCallShortCircuit` 严格等价于 `skin.smooth == 0 && nr.luma == 0 && nr.chroma == 0 && dehaze == 0 && clarity == 0 && texture == 0 && glow == 0 && sharpen.amount == 0`（其中 `glow` 通过 `0.20 * glow` 耦合进 `clarity`）。
- `SpatialDetailPlanner` 把「计划」与「执行」分离，并显式报告因 Pre-pass 未就绪而跳过的阶段；`EditorGlRenderer.lastPassCDrawCalls` 暴露实际 draw call 数，`isPassCActive` 暴露参数级激活状态，二者共同构成可单测的 0 Draw Call 契约。
- 拖杆期间（`useFastProxy`）完全跳过 Pass C，沿用规范中「point-operation ≤ 8ms」的代理策略；松手 150ms 后的全清渲染才跑空间链。

### 6. 数值契约

- 清晰度/纹理高通统一限幅 `clamp(Y - base, -0.08, 0.08)`，并乘以中间调钟形权重 `4y(1-y)`，避免端点溢出与光晕。
- 透射率下界 `max(t, 0.1)`，`ω` 上限 `0.95`；`A` 逐通道下限 `0.10`，防止深色画面 `I/A` 数值爆炸。
- 锐化半径按「全清像素」定义：CPU 与 GPU（`SpatialDetailGlChain`）均用 `workingShortEdge / fullResolutionShortEdge` 按比例缩放后夹到 `0.5..3.0` 再折算核步长，两侧共用 `[0.25, 0.5, 0.25]` 可分离核；阈值膝点 `threshold * 0.10`、边缘蒙版参考梯度 `0.12`。
- Y 通道锐化乘 `(1 - skinMask)`，`skinMask` 缺省为 `null`（等效 0，不抑制），供工单 12 直接接入。

## 结果

- 新增纯 Kotlin `PrepassMath` / `LowFrequencyPyramid` / `GuidedFilter` / `ImageFilters` / `ChromaNoiseReducer` / `PassCDetailMath` / `PreviewDetailPass` / `SpatialDetailParams` / `SpatialDetailPlanner`，全部无 `android.*` 导入。
- GL 侧新增 `PrepassAnalyzer`、`PrepassTexture`、`SpatialDetailGlChain`、`SpatialDetailRenderer`、`PresentGlProgram`；`PreviewPassPipeline` 与 `CanvasLetterbox` 统一调度 Pass A→B→C→D，保持 `EditorGlRenderer` 在 200 行以内。
- 画廊预览缓存（`PreviewCacheWriter` 的 Bitmap 与 RAW 两条路径）接入 `PreviewDetailPass`，与画布共用同一套 Pass C。
- 单测覆盖 Pre-pass 网格/大气光/ω 上限/金字塔平滑度、NR 平滑与边缘保留、透射率下界、`±0.08` 限幅与 `0.20 * glow` 耦合、`1 - skinMask` 抑制、0 Draw Call 判定与 draw plan、工具目录与撤销桶。
