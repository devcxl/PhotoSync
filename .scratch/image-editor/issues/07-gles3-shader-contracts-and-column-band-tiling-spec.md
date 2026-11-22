# GLES 3.0 6-Pass Shader 契约与 Pre-pass 列带分块渲染规范

Type: grilling
Status: resolved
Blocked by: 05, 06

## Question

在 [NDK libjpeg-turbo 流式编码与 ExifInterface FileDescriptor 写回技术调研](02-libjpeg-turbo-streaming-and-exif-writeback.md) 与 [开放参数语义与非破坏性编辑工作流边界](03-parameter-semantics-and-workflow-boundaries.md) 均已解决的基础上，该区块已从迷雾毕业。需结合 `EditGraph` Schema 与人像 MLS 网格规格，锁定 GPU 渲染核心的实施契约：
1. **6-Pass GLSL ES 3.00 Shader Uniform 与纹理绑定表**：
   - 阶段 ①（几何与光学重采样）、阶段 ②（含 `BaseLook` 补偿的线性影调）、阶段 ③（显示域色彩 + 1D 曲线 LUT + OKLCH 门控 + OKLab 分离色调 + defringe）、阶段 ④（美肤频率分离 + 引导滤波降噪 + 去雾 + 清晰度/纹理/锐化）、阶段 ⑤（3D LUT 四面体插值 + 全局颗粒 + post-crop 晕影）、阶段 ⑥（sRGB 编码 + 三角抖动量化）各自的精确输入/输出纹理槽位、Uniform 结构体与旁路短路（Bypass）条件。
2. **1024px 全图 Pre-pass 与金字塔规格**：
   - 去雾全局大气光 $A$（最亮 0.1%）与粗透射率图 $t_{low}$ 的提取算法；
   - 清晰度（2% 短边）与纹理（0.3% 短边）共享的多级降采样模糊金字塔层级与上采样插值方式。
3. **「≤2048 宽列带 × 512 高（halo=64px）」流式分块坐标换算契约**：
   - 源图驻留档位策略（24MP / 50MP / 100MP 下全幅纹理 vs `half_size` 的阈值判定，确保总内存 ≤ 512MB）；
   - 每个列带（Column Band）的含 Halo 输入局部 UV 到整图 Pre-crop / Post-crop 全局 UV 的精确仿射变换公式，以及 `glReadPixels` 配合 `GL_PACK_ROW_LENGTH = W`、`GL_PACK_SKIP_PIXELS`、`GL_PACK_SKIP_ROWS` 裁剪 Halo 并散射进全宽行缓冲喂给 `jpeg_write_scanlines` 的字节偏移契约。

## Answer

### 1. 逻辑 6 阶段 → 物理 4-Group Pass 合并与纹理/Uniform 绑定表

为减少 50% 的全分辨率 `RGBA16F` FBO 读写带宽，逻辑上的 6 个阶段在物理 FBO Draw Call 上合并为 **4 个物理 Pass 组**（且阶段 ④ 支持 0 Draw Call 短路）：

| 物理组 | 涵盖逻辑阶段 | 纹理槽位绑定 (`GL_TEXTURE0..5`) | 核心 Uniform 契约 | 短路条件 (`Bypass`) |
|---|---|---|---|---|
| **Pass A** (`GeometryCaPass`) | ① 几何与光学重采样 | `uTex0`: 源纹理（或档位 2 切片纹理）<br>`uTex1`: `128×128 GL_RG16F` 组合位移场 `uWarpTex`（MLS+美体+液化） | `mat3 uInvCropPerspRotMat;`（Post-crop UV → Pre-crop UV）<br>`vec4 uSrcSliceRect;`（档位 2 源切片偏移与缩放，档位 1/3 为 `0,0,1,1`）<br>`vec2 uCaRedBlue;`（横向色散 $k_R, k_B$）<br>`vec4 uTileUvTransform;`（当前带 Halo 列带到 Post-crop 全局 UV 的仿射系数） | 永不跳过（负责将源纹理重采样至工作/列带 FBO） |
| **Pass B** (`ToneColorPass`) | ② 线性影调 + ③ 显示域色彩 | `uTex0`: Pass A 输出 (`RGBA16F`)<br>`uTex1`: `1024×1 GL_RGBA16F` 曲线 1D LUT（R/G/B/RGB 四通道打包） | `int uBaseLook;`（`0=linear_neutral, 1=camera_default`）<br>`float uAutoBrightGain;`（RAW 基准提亮倍数）<br>`mat3 uBradfordWbMat;`（CPU 预计算的 D65 锚定相对 Bradford 矩阵）<br>`vec4 uTone0;`（`exposure, brightness, highlights, shadows`）<br>`vec4 uTone1;`（`whites, blacks, contrast, glow`）<br>`vec4 uColor0;`（`saturation, vibrance, fade, defringe`）<br>`vec3 uHslBands[8];`（8 色带 $\Delta h, \Delta C, \Delta L$）<br>`vec4 uSplitToning;`（`ab_hi.xy, ab_sh.xy`）<br>`float uSplitBalance;` | 当所有影调/色彩滑杆为 0、曲线恒等且 JPEG（或 RAW 线性基底）时可合入 Pass D |
| **Pass C** (`SpatialDetailChain`) | ④ 空间细节（多级子 Pass） | `uTex0`: Pass B 输出 (`RGBA16F`)<br>`uTex1`: `256×256 GL_R8` 美肤 `skinMask`<br>`uTex2`: Pre-pass 低频引导层（清晰度 `base_2pct`、纹理 `base_03pct`、去雾粗透射率 `t_low` 打包为 `RGBA16F`） | `vec3 uSkinParams;`（`smooth, texture, evenTone`）<br>`vec3 uNrParams;`（`luma, chroma, detail`）<br>`vec3 uDehazeA;` + `float uDehazeOmega;`（全局大气光 $A$ 与强度 $\omega$）<br>`vec2 uClarityTexture;`（`clarity + 0.20*glow, texture`）<br>`vec4 uSharpen;`（`amount, radiusPx, threshold, masking`） | **当 `skin.smooth==0 && nr.luma==0 && nr.chroma==0 && dehaze==0 && clarity==0 && texture==0 && glow==0 && sharpen.amount==0` 时整组 0 Draw Call 短路跳过** |
| **Pass D** (`EffectsOutputPass`) | ⑤ 效果 + ⑥ 输出量化 | `uTex0`: Pass C（或短路时 Pass B）输出<br>`uTex1`: `33×33×33 GL_RGBA16F` 3D LUT (`sampler3D`) | `float uLutStrength;` + `float uLutSize;`（默认 `33.0`）<br>`vec4 uGrain;`（`amount, size, roughness, seed`）<br>`vec4 uVignette;`（`amount, midpoint, feather, roundness`）<br>`float uAspect;`（Post-crop 宽高比）<br>`float uDitherAmp;`（`1.0 / 255.0` 三角抖动幅度） | 永不跳过（负责最终 sRGB gamma 编码、三角抖动与 `RGBA8` 输出） |

### 2. 1024px 全图 Pre-pass 与多尺度金字塔规格

无论是预览（松手定格后）还是分块导出（Tile Loop 启动前），先在 **长边 `1024px` 的全图代理 FBO** 上执行一次 Pre-pass（跑完 Pass A + Pass B 得到全图显示域基准图 $I_{1024}$）：
1. **去雾全局大气光 $A$ 与粗透射率 $t_{low}$**：
   - 若 `dehaze != 0`：在 $256 \times 256$ 降采样图上求 $7 \times 7$ 最小值滤波暗通道 $I_{\text{dark}}(x) = \min_{c \in \{R,G,B\}} \min_{y \in \Omega(x)} I_c(y)$；
   - CPU 读取 $256 \times 256$ 暗通道（仅 `256KB`），取 $I_{\text{dark}}$ 前 $0.1\%$（65 个最亮暗通道像素）在 $I_{1024}$ 中对应的最大亮度 RGB Vector 作为**全局唯一大气光 $A$**；
   - 在 $256 \times 256$ 上算出粗透射率 $t_{low}(x) = 1 - \omega \min_c (I_c(x) / A_c)$ 并经半径 $r=8$ 的快速引导滤波平滑，存入全局预分析纹理 `uPrepassTex.b`。
2. **清晰度（2% 短边）与纹理（0.3% 短边）低频金字塔**：
   - 对 $I_{1024}$ 的亮度 $Y$ 构建 3 级高斯/引导滤波降采样金字塔：$L_1 (512\text{px}, \text{等效 } r \approx 0.3\%\text{ 短边})$ 存入 `uPrepassTex.g`（供 **纹理 Texture** 高通 $Y - L_1$ 使用）；$L_3 (128\text{px}, \text{等效 } r \approx 2.0\%\text{ 短边})$ 存入 `uPrepassTex.r`（供 **清晰度 Clarity** 高通 $Y - L_3$ 使用）。
   - 分块导出时，Pass C 直接按当前像素的 **Post-crop 全局 UV** 双线性采样 `uPrepassTex.rg`，无需在全分辨率 Tile 上做百像素级超大核卷积，从而把分块 Halo 严格锁定在 `64px`。

### 3. 导出源图纹理三档驻留策略（突破 `GL_MAX_TEXTURE_SIZE` 并严守峰值 ≤ 512MB）

设解码并方向归一化后的源图尺寸为 $W_{src} \times H_{src}$，像素总数 $N_{mp} = W_{src} \times H_{src}$，设备 `GL_MAX_TEXTURE_SIZE` 为 $T_{max}$，设备 `ActivityManager.MemoryInfo.totalMem` 为 $M_{ram}$：

| 档位 | 触发条件 | 源图驻留与上传方式 | 峰值内存（源图 + FBO + 行缓冲） |
|---|---|---|---|
| **档位 1（全幅直驻 GPU）** | $N_{mp} \le 26\text{MP}$ 且 $\max(W_{src}, H_{src}) \le \min(T_{max}, 6144)$ 且 $M_{ram} \ge 4\text{GB}$ | Native `DirectByteBuffer` 解码为 `RGBA16F` 后一次 `glTexImage2D` 整图上传为 `uSrcTex`，随后立即释放 `DirectByteBuffer` | 24MP 下：**183.1 MiB**（GPU 纹理）+ **31.9 MiB**（列带 FBO + 行缓冲）= **215.0 MiB** |
| **档位 2（Native 驻留 + 按列带包围盒动态切片上传）** | $26\text{MP} < N_{mp} \le 65\text{MP}$ 或 $\max(W_{src}, H_{src}) > \min(T_{max}, 6144)$（如 45MP / 50MP / 61MP 全画幅机身），且 $M_{ram} \ge 6\text{GB}$ | 源图保留在 Native `DirectByteBuffer`（紧凑 3 通道 `RGB16` 或 `RGBA16F`，61MP `RGB16` 仅 **349 MiB**）；在渲染每个含 Halo 列带前，CPU 用逆映射 $\Phi^{-1}$ 对该列带边界采样 16 个点求源图最小轴对齐包围盒 $[x_0..x_1, y_0..y_1]$（外扩 4px 双线性余量），仅将该子矩形转为 `RGBA16F` 上传到一张 $\le 2560 \times 2560$ 的 GPU 切片纹理，并将归一化框写入 `uSrcSliceRect` | 50MP 下：**286 MiB**（Native `RGB16`）+ **18 MiB**（GPU 切片纹理）+ **36.1 MiB**（列带 FBO + 行缓冲）= **340.1 MiB**；**100% 保留 50MP/61MP 原生全分辨率且完全不受 `GL_MAX_TEXTURE_SIZE` 限制** |
| **档位 3（硬件 2×2 Bayer 合一降采样）** | $N_{mp} > 65\text{MP}$（如 100MP 中画幅）或 $M_{ram} < 6\text{GB}$ | 解码时开启 LibRaw `params.half_size = 1`（100MP 直接输出 25MP `5824 × 4368` 16-bit 线性 RGB），随后自动落入档位 1 整图驻留 GPU | 100MP → 25MP 下：**194.2 MiB** + **32.5 MiB** = **226.7 MiB** |

### 4. 「≤2048 宽列带 × 512 高（Halo=64px）」全局 UV 换算与 `glReadPixels` 散射契约

设裁切后的目标导出成片总尺寸为 $W_{crop} \times H_{crop}$，固定条带有效高 $S = 512$，最大列带有效宽 $B_{max} = 1920$，Halo $h = 64$（因此 FBO 物理上限固定为 $(1920 + 128) \times (512 + 128) = 2048 \times 640 \le 2048$，兼容一切 GLES 3.0 设备）：

1. **外层行条带循环（Row Strip $r = 0, 1, \dots, \lceil H_{crop}/512 \rceil - 1$）**：
   - 当前条带在成片中的有效行区间：$y_{dst} \in [y_0, y_0 + H_{valid})$，其中 $y_0 = r \cdot 512$，$H_{valid} = \min(512, H_{crop} - y_0)$。
2. **内层列带循环（Column Band $c = 0, 1, \dots, \lceil W_{crop}/1920 \rceil - 1$）**：
   - 当前列带在成片中的有效列区间：$x_{dst} \in [x_0, x_0 + W_{valid})$，其中 $x_0 = c \cdot 1920$，$W_{valid} = \min(1920, W_{crop} - x_0)$；
   - 带 Halo 的 FBO 视口尺寸：$W_{fbo} = W_{valid} + 2h$，$H_{fbo} = H_{valid} + 2h$；
   - **FBO 局部纹理坐标 $uv_{fbo} \in [0, 1]^2$ 到 Post-crop 成片全局坐标 $uv_{global} \in [0, 1]^2$ 的精确仿射变换**（通过 `uTileUvTransform = vec4(scaleX, scaleY, offsetX, offsetY)` 传入所有 Pass）：
     $$uv_{global, x} = \frac{(x_0 - h) + uv_{fbo, x} \cdot W_{fbo}}{W_{crop}}, \quad uv_{global, y} = \frac{(y_0 - h) + uv_{fbo, y} \cdot H_{fbo}}{H_{crop}}$$
     （Pass A 再对 $uv_{global}$ 应用 $H^{-1}$ 与 `uWarpTex` 得到源图 `uvPreCrop`；Pass D 的颗粒、post-crop 晕影均直接使用 $uv_{global}$，确保跨列带与跨行条带 100% 零拼缝。）
3. **`glReadPixels` 裁剪 Halo 并散射至全宽行缓冲（`rowBuf`，容量 $W_{crop} \times 512 \times 4\text{B}$）**：
   ```cpp
   glBindFramebuffer(GL_FRAMEBUFFER, passDFbo);
   glPixelStorei(GL_PACK_ALIGNMENT, 4);
   glPixelStorei(GL_PACK_ROW_LENGTH, W_crop);   // ES 3.0 跨距散射：每行写完 W_valid 像素后自动跳过整图剩余列
   glPixelStorei(GL_PACK_SKIP_PIXELS, x0);      // 水平偏移到当前列带起点 x0
   glPixelStorei(GL_PACK_SKIP_ROWS, 0);
   // 仅从 FBO 的 (h, h) 偏移处读取内部有效区 W_valid × H_valid，直接剥离四周 64px Halo！
   glReadPixels(h, h, W_valid, H_valid, GL_RGBA, GL_UNSIGNED_BYTE, rowBuf.data());
   glPixelStorei(GL_PACK_ROW_LENGTH, 0);
   glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
   ```
4. **整条带喂入 `libjpeg-turbo`**：
   - 当内层列带循环结束，全宽 `rowBuf` 的前 $H_{valid}$ 行已拼合完整；
   - 用 `JCS_EXT_RGBA` 色彩空间将 $H_{valid}$ 行指针数组一次性传给 `jpeg_write_scanlines(&cinfo, rowPtrs.data(), H_valid)`（零 RGB24 重打包拷贝）。
