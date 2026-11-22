# 0003 — Pass B 影调/白平衡：单一数学源、D65 严格恒等锚点与零参数短路

- 状态：已采纳
- 日期：2026-11-22
- 影响范围：`editor/src/main/java/cn/devcxl/photosync/editor/domain/`（`BradfordWbSolver`、`ToneShaderParams`、`ToneShaderMath`）、`editor/src/main/java/cn/devcxl/photosync/editor/render/`（`ExposureShaderSources`、`ExposureGlProgram`、`EditorGlRenderer`、`PreviewCacheWriter`）

## 背景

影调面板要一次交付 8 个滑杆（曝光、亮度、对比度、高光、阴影、白色色阶、黑色色阶、光感），色彩面板交付色温与色调。这带来三个必须先定下来的问题：

1. **两处实现必须逐像素一致**：画布上的 GLSL 预览与「完成」时写盘的 JPEG 预览缓存是两条独立代码路径。摄影师的预期是缩略图与刚才看到的画面一致，因此数学不能各写一份然后靠肉眼对齐。
2. **零参数必须严格恒等**：`docs/research/2026-11-21-image-editor-pipeline.md` 要求所有滑杆为 0 时逐像素恒等（差 ≤ 1/255）。而高光软肩部与软色域压缩在 `Y > 0.85` 的像素上即使参数为 0 也会改变输出，必须显式短路。
3. **研究文档的 mired 符号与它自己声明的冷暖方向矛盾**：文档写 `mired = mired_ref − 60·t`，同时要求 `t > 0` 变暖；但 mired 变小意味着色温更高、目标白点更蓝，把 D65 白点推向蓝端只会让画面变冷。

## 决策

### 1. `ToneShaderMath` 是唯一数学源，GLSL 逐条镜像

CPU 参考实现与 GLSL 共用同一组常量（中灰 0.18、亮度对数权重 σ = 1.1、软肩 t0 = 0.85、黑阶 0.18/0.04、光感 0.20/0.10）与同一段流水线顺序。`ToneShaderMathLinearStageTest` / `ToneShaderMathDisplayStageTest` 用从规范手算出来的 8-bit 字面量锁定行为，`LinearRawPreviewRendererToneParityTest` 再锁定「缓存渲染 == 参考数学」；GLSL 侧用 `glslangValidator` 在 GLSL ES 1.00 与 3.00 下分别编译并链接通过。

### 2. 白平衡以 sRGB D65 为严格恒等锚点，暖色 = 更大的 mired

矩阵按 `uv_target = uv_D65 + (uv_t − uv_0)` 的相对偏移求解，`uv_0` 取 6500 K 普朗克轨迹点，锚点仍是 D65 本身（D65 距轨迹约 `+0.0032 Δuv`，直接拿 `planckianLocus(6500)` 当锚点会让 0 参数偏品红）。

**与文档的偏差**：mired 采用 `mired_ref + 60·t`，即 `t > 0` 增大 mired（约 4680 K）使目标白点偏红。这样 `LMS_dst / LMS_D65` 放大长波通道，画面变暖，与研究文档「`t > 0` 画面变暖、放大长波通道」的验收条款一致；文档里那一行减号视为笔误。tint 沿轨迹法线 `(dv, −du)` 偏移（+v 是绿侧，故取反向），全量程 0.02，正值为品红。

Bradford 锥响应矩阵采用 Lam(1985) × `colour-science` 版本 `[0.0389, −0.0685, 1.0296]`（部分资料误写作 `[0.0389, 0.0685, 0.0297]`，那组不是可用的锥响应）。整条链路以 Lindbloom 公布的 D65→D50 XYZ Bradford 矩阵作外部基准，实测最大偏差 4.4e-8。

### 3. 零参数短路放在数学函数与 shader 两个入口

`ToneShaderMath.apply` 与 `uToneActive == 0` 分支都在做任何 shoulder / 色域压缩之前直接返回 `encodeSrgb(linear)`。软肩部与软色域压缩只在至少一个滑杆非零时生效——它们是「救回高光」的工具，不应该改写一张没被编辑的照片。

### 4. 曝光也走懒亮度软肩部，不再保留旧的逐通道 PATH

旧实现只有曝光非零时逐通道 `softShoulder`。Ticket 05 之后曝光属于同一条影调链，肩部改为作用在亮度上再按比例带回 RGB，避免高饱和高光单通道先撞顶而偏色。缓存渲染器为曝光单独维护的 4096 项 LUT 随之删除。

### 5. 光感对清晰度/自然饱和度的耦合以常量暴露

`GLOW_CLARITY_COUPLING = 0.20`、`GLOW_VIBRANCE_COUPLING = 0.10` 与 `glowClarityBoost()/glowVibranceBoost()` 供 Ticket 08（Pass C 清晰度）与 Ticket 06（Pass B2 自然饱和度）直接读取，避免两处各自再写一遍 `0.20 * glow`。

## 结果

- 新增 `BradfordWbSolver`（温度/色调 → 线性 sRGB 3×3，从头到尾 double 运算，输出列主序 `mat3` 给 GL）
- 新增 `ToneShaderParams`（不可变 uniform 快照，`from(EditGraph)` 一次性解析滑杆与白平衡矩阵）与 `ToneShaderMath`（Pass B 上半段 CPU 参考）
- `ExposureShaderSources` 的 ES 3.00 与 ES 1.00 两套 shader 都接上新 uniform；`glslangValidator` 编译 + 链接通过
- 新增 42 个 JVM 单测（Bradford 8、线性段 11、显示段 12、缓存对齐 4、面板目录 9），`assembleDebug`、`testDebugUnitTest`、`lintDebug` 全绿

## 已知遗留

1. **GPU 端只有编译期验证**：真实设备上的 0 参数恒等与冷暖方向仍需真机回归（当前仓库没有 headless GLES 测试环境）。
2. **缓存渲染变慢**：滑杆非零时预览缓存改为逐像素解析求值（约 4 次 `pow`/`log`），未做 LUT 化。若「完成」时延明显，可在 Ticket 09 的列带烘焙里统一优化。
3. **软色域压缩按附录 B 原式实现**：`c = Y + (c − Y)/maxCh` 只在 `Y` 较小（暗部高饱和）时才把通道压回 1 以内；这是文档给定的公式，未替换成按 `(1 − Y)/(maxCh − Y)` 归一化的版本。若后续发现亮部高饱和像素仍有色相漂移，需要重新评估。
4. **`camera_default` 下无法救回高光余量**：浏览页曲线本身在显示域把 `Y > 1` 截到 1，负载观感优先于 headroom。想靠负高光/白色色阶找回过曝细节时，用户需要先切到 `linear_neutral`。
