# 05: 完整线性影调与 D65 锚定色温/色调切片（Pass B 上半段）

**What to build:** 在编辑器「影调」与「色彩」面板交付色温（Temperature）、色调（Tint）、亮度（Brightness）、高光（Highlights）、阴影（Shadows）、白色色阶（Whites）、黑色色阶（Blacks）、对比度（Contrast）与氛围型光感（Glow）。其中色温/色调采用以 sRGB D65 标准白点为 `(0, 0)` 严格恒等锚点的相对 Bradford 色适应矩阵；高光、阴影与白色色阶在 `[0, 1]` 截断前的线性域工作并配合 `softShoulder` 软肩部滚降以真正救回 16-bit RAW 过曝细节；黑色色阶做锚定 0 端点的暗部趾部伸缩；对比度采用正负分段公式防止负值端点斜率畸变。

**Blocked by:** 03: LibRaw 16-bit 线性 RGBA16F 解码与 RAW 基准观感切换

**Status:** resolved

- [x] 实现 CPU 侧 `BradfordWbSolver`：基于 CIE 1960 `uv` 相对偏移 `uv_target = uv_D65 + (uv_t - uv_0)` 计算 $3\times 3$ 线性 sRGB 色适应矩阵；单元测试断言 `temp = 0, tint = 0` 时与单位阵 $I$ 最大误差 $< 10^{-6}$，且 `temp > 0` 画面变暖、`temp < 0` 画面变冷。
- [x] 在 Pass B Shader 线性段实现亮度（中灰 `Y=0.18` 对数高斯加权）、高光/阴影、白色色阶（线性亮部伸缩 + `softShoulder` 渐近收敛，验证负值可拉回 `Y > 1.0` 的线性高光且不会将正常图片纯白压成死灰）。
- [x] 在 Pass B Shader 显示域段实现黑色色阶（锚定 0 端点的暗部趾部伸缩，不抬高纯黑死点）、正负分段对比度（`c >= 0` 用 `smoothstep`，`c < 0` 用中灰线性压缩）以及氛围型光感前两段（$y^{1/(1+0.20g)} + 0.10g\cdot y(1-y)^2$）。
- [x] 影调面板全部 8 个滑杆及色温/色调滑杆接入 UI，双击子工具 Chip 可单独重置该滑杆为 0；所有参数为 0 时输出逐像素恒等（差 $\le 1/255$）。
