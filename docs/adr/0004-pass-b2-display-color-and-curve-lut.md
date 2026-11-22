# 0004 — Pass B2 显示域色彩管线、单调三次曲线 LUT 与 UI 面板

- 状态：已采纳
- 日期：2026-11-22
- 影响范围：`editor/src/main/java/cn/devcxl/photosync/editor/domain/`（`MonotoneCubicCurve`、`CurveLutBuilder`、`OklabColorMath`、`ColorGradingParams`、`PassBColorMath`）、`editor/src/main/java/cn/devcxl/photosync/editor/render/`（`ColorShaderGlsl`、`CurveLutTexture`、`ExposureShaderSources`、`ExposureGlProgram`）、`editor/src/main/java/cn/devcxl/photosync/editor/ui/`（`CurveEditorView`、`HslPanelView`、`SplitToningPanelView`、`CurveKnotMath`）

## 背景

工单 06 在 `ToneShaderMath`（Pass B1）之后接续完整显示域色彩管线：4 通道（RGB/R/G/B）Fritsch–Carlson 单调三次曲线、软色域压缩、OKLCH 饱和度/自然饱和度/8 色带 HSL、笛卡尔 OKLab 色调分离、去紫边（Defringe）与褪色（Fade）。该阶段存在四项与早期调研基线需要明确锁定的设计决策与微调。

## 决策

### 1. Pass B2 精确管线顺序：先光学去紫边，后艺术色调分离

在 `PassBColorMath` 与 `ColorShaderGlsl` 中，Pass B2 按如下顺序执行：
1. **Pass B1 出口已包含线性亮度替换后的软色域压缩**：输出 `[0, 1]` sRGB，保证零 Pass B2 参数时与工单 05 像素严格一致。
2. **1024×1 `GL_RGBA16F` 1D 曲线 LUT**：`R/G/B` 存于 `.rgb`，`RGB` 复合存于 `.a`；采样顺序严格为「先 `.a`（RGB 复合）后 `.r/.g/.b`（单通道）」。GLSL 使用 `u = clamp(v, 0, 1) * (1023/1024) + 0.5/1024` 对齐 texel 中心，与 CPU `CurveLutBuilder.sampleChannel` 的 `x * 1023` 线性插值精确一致。
3. **OKLCH 调整与光学 Defringe**：先执行 Saturation、Vibrance（含 `ToneShaderMath.glowVibranceBoost` 耦合）、8 色带 HSL（$\Delta h$ 与 $\Delta L$ 乘以低色度门控 `smoothstep(0.01, 0.05, C)`），再在高梯度边缘上对紫色/绿色纵向色散带降饱和。
4. **笛卡尔 OKLab `(a, b)` 色调分离 → 后置软色域压缩 → 褪色（Fade）**：将 `defringe` 放在色调分离之前，可防止摄影师在发丝/树枝等高反差边缘刻意添加品红/紫色高光着色时被去紫边误擦除。

### 2. 与 2026-11-21 调研基线公式的参数调校记录

以下 4 处系数与早期调研伪代码略有差异，均为测试验证与物理一致性调整，CPU 与 GLSL 严格同步：
1. **Vibrance 肤色保护**：中心仍为 `45°` OKLCH，深度与宽度定为 `SKIN_PROTECTION_DEPTH = 0.70`、`SKIN_SIGMA = 25°`（调研初稿为 `0.60 / 20°`），在实测人像暖光肤色下对面部过饱和抑制更稳健。
2. **8 色带 OKLCH HSL 归一化与明度计算**：权重按 `1 / max(sumW, 1)` 归一化（保证相邻色带交界处不出现双倍叠加），明度使用受色度门控的 `clamp(L + 0.25 * dL * g_C, 0, 1)`，避免暗部乘性缩放无效。
3. **OKLab 色调分离**：不额外衰减 `L=0/1` 端点的色度向量，使纯灰到暗部均能直接检验目标 `(cos h, sin h)` 色相角；在 `L=0` 时负通道在随后 sRGB `[0, 1]` 裁剪归零。
4. **Fade 降饱和系数**：纯黑抬升保持 `y * (1 - 0.15f) + 0.12f`，向亮度轴退饱和系数定为 `0.18f`（而非固定 `0.25` 乘积），与黑位抬升共同构成自然胶片哑光感。

### 3. `SplitToningPanelView` 独立控件与 `CurveKnotMath` 纯 Kotlin 状态机

在工单 02/04 既有的 `DialSeekBar`、`CurveEditorView`、`HslPanelView`、`LiquifyBrushPanel` 之外，为色调分离的 5 个耦合参数新增 `SplitToningPanelView`（高光/阴影切换 + 实时 OKLCH 色相圆点预览 + 3 条 `LabeledDialRow`），复用 `DialSeekBar` 且横竖屏共用 `layout/editor_color_panels.xml`。曲线控制点增/拖/拖出网格删除逻辑抽入纯 Kotlin `CurveKnotMath` 接受 JVM 单测。

### 4. Tier 3 极简 Fallback Shader 兜底

当严格受限于 16 个 fragment uniform 向量的老旧 GLES 2.0 驱动无法编译包含完整 Pass B2（40+ uniforms）的 `FRAGMENT_SHADER_ES2` 时，`ExposureGlProgram` 记录 `GL_RENDERER` / `GL_VERSION` 后自动降级至 6 个 uniform 的 `FRAGMENT_SHADER_MINIMAL_ES2`（BaseLook + 曝光），确保符合 Spec §8 Tier 3 要求，避免静默黑屏。

## 结果

- 新增 `MonotoneCubicCurve`（500 组随机单调与任意控制点集无过冲验证）、`CurveLutBuilder`、`OklabColorMath`、`ColorGradingParams`、`PassBColorMath` 及对应的 `ColorShaderGlsl` / `CurveLutTexture`。
- `EditorColorSpecs` 从 `EditorToneDetailSpecs` 独立拆分，保持单文件及单函数均处于代码规范限值之内。
- `ChromaticAberrationOps.hasLateralCa()` 与 `EditGraph.requiresPassAGeometry()` 统一控制 Pass A 触发条件，使仅调节显示域 `defringe` 时不再触发 Pass A 离屏重采样。
- 全量 `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 绿灯通过。

## 已知遗留

1. **GPU/真机端尚待实机验证**：`ColorShaderGlsl` 与 `PassBColorMath` 的像素一致性目前通过代码逐常量对齐锁定；端到端 GLES 渲染对比需在真机或 headless GLES 测试套（工单 09/集成测试）上实测。
2. **中性灰壁斑块物理边界**：`g_C = smoothstep(0.01, 0.05, C)` 保证纯灰及微偏色 (`C <= 0.01`) 零斑块，但若环境白平衡偏色致使灰墙实测色度落入 `0.02..0.04`，仍会线性透传部分色相/明度偏移（属规范约定行为）。
