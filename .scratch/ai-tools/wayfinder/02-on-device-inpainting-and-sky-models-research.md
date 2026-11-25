# 端侧智能消除（MI-GAN / LaMa-Dilated）与天空分割模型事实调研

Type: research
Status: resolved
Assignee: subagent/research-inpainting-sky
Blocked by: none
Research branch: `research/inpainting-sky` (`d3013fd`) → `docs/research/2026-11-26-on-device-inpainting-and-sky-models.md`

## Question

围绕 P2 涂抹去杂物（端侧 Inpainting）与 P3 换天空（端侧 Sky Segmentation），在严守 MIT / Apache-2.0 商用合规且统一跑在 ONNX Runtime Android 的前提下，需要通过一手来源查清以下事实：

1. **端侧 Inpainting 候选模型（MI-GAN vs LaMa-Dilated 及其他可商用轻量方案）**：
   - 核实 **MI-GAN（512×512）** 与 **LaMa-Dilated（无 FFT 替换版）** 以及社区较新可商用端侧 inpainting 模型（若有）的精确许可证、ONNX 文件体积（FP32 / FP16 / INT8）、输入张量格式（图像归一化与二值/羽化掩码通道约定）、是否包含 ORT Mobile 不支持算子，以及移动端 CPU/NNAPI 下的推理耗时与峰值内存。
   - 核实 INT8 / FP16 量化对生成式修补边界色差、纹理糊化的已知影响。
2. **高分辨率相机照片的局部 Crop-Inpaint-Blend 工程实践**：
   - 针对 24MP–60MP 照片的局部涂抹区域，业界在裁切上下文扩展倍率（padding factor）、最大单次涂抹面积阈值、以及回贴融合（羽化 alpha、泊松编辑 / 无缝克隆、拉普拉斯金字塔融合或色度对齐）方面有哪些经验证的轻量算法，能在端侧控制接缝可见度？
3. **可商用开源天空分割候选模型**：
   - 调研可商用许可（MIT / Apache-2.0）且易于导出/获取 ONNX 权重的轻量天空分割模型（如 SegFormer-B0 / MobileNetV3 / BiSeNet / 开源 SkySeg 权重）：精确许可证、模型体积、输入输出规格、对树枝/建筑/山脊复杂天际线的边缘表现，以及与 `GuidedFilter` 配合精修的可行性。

## Answer

完整一手调研与复现命令见分支 `research/inpainting-sky`（commit `d3013fd`）下的 `docs/research/2026-11-26-on-device-inpainting-and-sky-models.md`。关键结论：

1. **MI-GAN 是唯一代码+权重双 MIT 的端侧消除候选**：官方 512 pipeline ONNX 为 **28,079,181 B**（5.98M 参数 / 15.69 GFLOPS），输入 `uint8` NCHW 图像 + `uint8` 掩码（**255=保留、0=消除**，与 LaMa 相反）；手机 CPU 实测 539 ms（旗舰）～5.4 s（低端）。
2. **LaMa-Dilated 不适合通用 v1**：Apache-2.0，但实测为 **CelebA-HQ 人脸权重**、45.6M 参数、**183.6 MB**、固定 512 输入，失去原版全卷积优势。
3. **量化红线**：FP16 权重 PSNR 57.3 dB（视觉无损）；INT8 weight-only 31 dB；INT8 静态 QDQ 仅 16.2 dB 且纹理能量 −22%；整图 naive FP16 转换会因 `Less` 算子类型冲突无法加载，**生成器推理与裁切/融合必须拆分**，仅使用 FP32/FP16。
4. **局部 Crop-Inpaint-Blend 实践**：MI-GAN 与 IOPaint 均采用 **128 px 上下文边距**（512 模型下掩码外接框 ≤256 px 才保持 1:1）；IOPaint 对擦除为硬粘贴（无 Poisson/拉普拉斯），MI-GAN 官方做 5 px 高斯羽化；Poisson 成本约 3× alpha。
5. **天空分割踩出重大许可与来源坑**：**SegFormer-B0 ADE20K 使用 NVIDIA 非商用条款（§3.3）直接排除**；SkySeg 官方 MIT 仓库仅发布 **2.26 MB ncnn 小模型**，社区流传的 176 MB ONNX 来源存疑（非版权方上传且作者声明高精度版不公开）。后续决策工单需重新审视可商用天空模型替代源或用明确 Apache/MIT 权重。
6. **GuidedFilter 复用边界**：现有 `GuidedFilter.smooth` 为全分辨率非流式，约需 10×N 缓冲（24MP 约 1.1 GB），全分辨率烘焙必须分块（2048² + 32 px 重叠，约 200 MB）或改为流式窗口实现。
7. **ORT 运行时硬事实**：`onnxruntime-mobile` 最后版本停在 1.18.0（arm64 +4.5 MB，1.19 起停供），完整 `onnxruntime-android` arm64 约 +18.3 MB；MI-GAN 35 个算子均在 1.17 mobile 清单内，需结合调研 01 统筹运行时决策。
