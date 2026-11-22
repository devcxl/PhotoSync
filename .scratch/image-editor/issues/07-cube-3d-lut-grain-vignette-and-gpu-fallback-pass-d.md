# 07: 外部 .cube 3D LUT 导入、全局颗粒、晕影与 GPU 4 级降级切片（Pass D）

**What to build:** 交付物理 Pass D（效果与输出量化合并 Pass）及 GPU 4 级安全降级矩阵：支持用户通过系统文件选择器（SAF）导入外部 `.cube` 3D LUT 文件（导入时一次性校验并预烘焙为 `33×33×33 RGBA16F` 二进制缓存，渲染时在 `sampler3D` 上做四面体插值并与原图按强度混合）、基于裁切后整图全局坐标的胶片颗粒（Grain，带确定性 `seed`）、Post-crop 晕影（Vignette，含数量、中点、羽化带宽与圆度）、输出 `1/255` 三角抖动量化，以及针对无 `GL_EXT_color_buffer_half_float` 设备的 `RGBA8` 抖动 FBO 回退与 Shader 编译异常最小管线兜底。

**Blocked by:** 04: 几何重采样与光学横向色散校正切片（Pass A）, 06: 显示域色彩、单调三次曲线、8 色带 OKLCH HSL 与色调分离切片（Pass B 下半段）

**Status:** resolved

- [x] 实现 `CubeLutParser` 与 SAF 导入流程：支持 `LUT_3D_SIZE` $\in [2, 65]$ 与 `DOMAIN_MIN/MAX` 解析，拒绝畸形 `.cube` 文件而不崩溃，并将合法 LUT 重采样预烘焙为 `33³ RGBA16F`（`287,496 B` + 8B 魔数头）二进制缓存文件。
- [x] 在 Pass D Shader 中实现四面体 3D LUT 插值，将基准网格索引 `ivec3 i` 上界严格 Clamp 至 `int(size) - 2`，消除输入颜色为 `1.0` 时 `texelFetch(lut, i + 1, 0)` 的越界未定义行为。
- [x] 在 Pass D Shader 中基于 Post-crop 全局坐标 $uv_{\text{global}}$ 实现胶片颗粒（数量/大小/粗糙度 + 中间调包络 $\sqrt{4Y(1-Y)}$ + 持久化 `seed`）与 Post-crop 晕影（过渡区间 `[mid - 0.5*feather, mid + 0.5*feather]`）。
- [x] 实现 GPU 运行时探测与 Tier 1（`RGBA16F` FBO）→ Tier 2（`RGBA8` FBO + 每 Pass 三角抖动）→ Tier 3（Shader 编译失败记录 `Timber.e` 并回退最小几何+影调管线）降级机制。
- [x] 包含 `.cube` 正常/畸形解析单测及先裁切后晕影的顺序验证测试。
