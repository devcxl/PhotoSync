# GLES 3.0 6-Pass Shader 契约与 Pre-pass 列带分块渲染规范

Type: grilling
Status: open
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
