# 06: 显示域色彩、单调三次曲线、8 色带 OKLCH HSL 与色调分离切片（Pass B 下半段）

**What to build:** 完成物理 Pass B 的下半段显示域色彩管线与对应 UI 面板：交付防止暗部高饱和提亮偏色的软色域压缩、OKLCH 饱和度（Saturation）与自然饱和度（Vibrance，含高饱和截断保护、暗部彩噪抑制与肤色保护）、4 通道（RGB 复合、R、G、B）Fritsch–Carlson 单调三次 Hermite 曲线交互编辑器、带低色度门控的 8 色带 OKLCH HSL 混色器、笛卡尔 OKLab 色调分离（Split Toning）、纵向色散高梯度紫/绿边去饱和（Defringe）以及胶片褪色（Fade）。

**Blocked by:** 05: 完整线性影调与 D65 锚定色温/色调切片（Pass B 上半段）

**Status:** resolved

- [x] 实现 `MonotoneCubicCurve` 与 `CurveEditorView`：支持在 RGB/R/G/B 4 个通道上添加、拖拽及向下拖出网格删除控制点（2–16 点，最小间距 $\Delta x \ge 1/256$），生成 `1024×1 GL_RGBA16F` 1D LUT 纹理并按「先 RGB 复合、再单通道」顺序采样；单测验证 500 组随机控制点严格单调无过冲。
- [x] 实现线性亮度替换后的软色域压缩（`maxCh > 1.0` 时向亮度轴软收敛）及线性 sRGB ↔ OKLab/OKLCH 变换。
- [x] 实现自然饱和度（`w_sat = clamp(1 - C/0.32, 0, 1)`、`w_shadow = smoothstep(0.05, 0.20, L)` 与 45° 肤色保护）及 `HslPanelView` 8 色带混色器（色相与明度项必须乘 `g_C = smoothstep(0.01, 0.05, C)` 低色度门控，确保调节蓝色明度时中性灰墙面无斑块）。
- [x] 在笛卡尔坐标 OKLab `(a, b)` 上实现色调分离（高光/阴影目标色度向量按 `balance` 权重叠加，确保 `C = 0` 的黑白/中性灰像素也能准确染上目标色相）。
- [x] 实现纵向色散 `defringe`（高梯度边缘紫/绿相带去饱和）与 `fade`（纯黑端点抬升 `y(1-0.15f)+0.12f` + 降饱和）。
