# 端侧智能消除与天空分割：模型许可证、ONNX 体积、推理规格与工程实践事实调研

- 日期：2026-11-26
- 关联工单：Wayfinder「端侧智能消除（MI-GAN / LaMa-Dilated）与天空分割模型事实调研」
- 上游基线：`docs/research/2026-11-21-image-editor-pipeline.md`
- 被测对象：`andraniksargsyan/migan`（MI-GAN-512 ONNX Pipeline v2）、`qualcomm/LaMa-Dilated`（v0.67.0 ONNX float）、`advimman/lama`（big-lama 参照）、`xiongzhu666/Sky-Segmentation-and-Post-processing`（SkySeg）、`nvidia/segformer-b0-finetuned-ade-512-512`（对照）
- 实测环境：Linux x86_64、Python 3.14.10、onnx 1.23.2、onnxruntime 1.32.0（CPU EP）、onnxconverter-common、opencv-python-headless、ncnn 1.0
- **未实测**：任何 Android 真机/模拟器。所有「移动端耗时」均来自论文、Qualcomm AI Hub 或厂商文档，正文逐条标注来源；所有「桌面耗时」只用于比较相对开销，不代表手机。

> 本文所有字节数均为**实测下载后 `stat`/`sha256sum` 的结果**。凡属推算（FP16/INT8 未导出的体积、手机端外推）均标注「投影」。

---

## 0. 结论摘要

1. **MI-GAN 是当前唯一同时满足「MIT 许可（代码 + 权重）」「< 10M 参数」「有官方 ONNX Pipeline」的端侧 inpainting 模型**。官方 512 Pipeline `migan_pipeline_v2.onnx` = **28,079,181 B**，输入 `uint8` NCHW 图像 + `uint8` 掩码（**255 = 保留，0 = 待修复**，与 LaMa 相反），输出 `uint8`，内部自带裁切/缩放/羽化/回贴。论文口径 **5.98M 参数、15.69 GFLOPS、FID 10.00**（Places2-512）。
2. **许可证证据链完整**：`Picsart-AI-Research/MI-GAN` 的 `LICENSE` 与 **`LICENSE-WEIGHTS` 均为 MIT**（后者是权重专门许可，很多二手文章会漏掉）；HF 模型卡 `license: mit`。可商用、可再分发、可修改，保留版权与许可文本即可。
3. **LaMa-Dilated 与本仓库设想不同**：它不是「LaMa 去掉 FFT」的通用场景模型，而是 Qualcomm 用 **Dilated CelebA-HQ** 权重导出的固定 512 模型（**45.6M 参数、182,568,704 B 权重**），许可证 Apache-2.0（HF 元数据 + 上游 `advimman/lama` LICENSE）。人脸/肖像修补优先，通用场景不如 big-lama。**183.6 MB 无法进 APK**，只能按需下载，且固定 512 丢失了 LaMa 全卷积的分辨率鲁棒性。
4. **MI-GAN 的移动端实测速度（论文 Table S.1，512×512，ONNX CPU）**：iPhone 14 Pro Max **539 ms**、iPad mini 5 **1018 ms**、Galaxy Tab S7+ **1211 ms**、Galaxy S8 **2527 ms**、vivo Y12 **5386 ms**。现代 Android 中端机可预期 **1–3 s**（外推，未实测）。
5. **ORT Mobile 的现状是本次调研的硬约束**：预编译 `onnxruntime-mobile` 最后版本 **1.18.0（AAR 7,241,017 B，arm64 两个 .so 合计 4,504,888 B）**，官方文档明确「**1.19 起不再提供 reduced-operator 预编译包**，请用完整包或自行 custom build」。MI-GAN Pipeline 用到的 35 种算子（含 `NonZero`/`ScatterND`/`GatherND`/`Range`）都在 1.17 mobile 包清单里，但**必须转 ORT format**；而 `onnxruntime-android` 完整包 arm64 增量是 **18,314,824 B**（AAR 28,515,295 B）。
6. **量化实测（MI-GAN-512，同一张 380×512 样图）**：
   - FP16 权重量化（模拟）：PSNR **57.27 dB**、最大误差 **4/255**、Laplacian 方差几乎不变 → **视觉无损**。
   - INT8 weight-only（per-channel 对称）：PSNR **31.03 dB**、p99 26、最大 **149/255** → 渐变区域可见色带。
   - INT8 QDQ 静态量化（仅 Conv，3 张校准图）：**PSNR 16.24 dB、最大 231、Laplacian 方差 −22%** → **生成式修补被破坏**，不能直接上线。
   - 直接用 `convert_float_to_float16` 转整个 Pipeline（**14,133,691 B**）会**加载失败**：`Less` 同时绑定 `float` 与 `float16`。生产上必须只导出生成器或在 `op_block_list` 中排除裁切/融合数学。
7. **裁切实践有明确数值**：MI-GAN 官方 Pipeline 用 **固定 128 px 上下文边距**、最小裁切 **512×512**、`max(w,h)+256` 后缩放到 512；IOPaint 的 `hd_strategy_crop_margin` 默认也是 **128 px**，触发阈值 **800 px**。因此**掩码外接框 ≤ 256 px 时才能 1:1 进入 512 模型**；更大的涂抹会被降采样，纹理必然变糊——这与 MI-GAN README「大区域请分多笔小面积涂抹」一致。
8. **回贴融合的行业事实与直觉相反**：IOPaint 对 LaMa/MI-GAN 这类擦除模型是**硬粘贴**（`mask < 127` 的像素原样恢复，掩码内直接覆盖，无羽化、无泊松），只有 diffusion 模型才有 `sd_mask_blur=11` 与 `sd_match_histograms`。MI-GAN 官方 Pipeline 则在**原裁切分辨率**上做 `maxpool(3×3)` + `Gaussian(5, σ=1.0)` 羽化后 alpha 合成。**IOPaint 全仓库没有 `seamlessClone`/拉普拉斯金字塔融合**。
9. **天空分割的许可证地雷**：`nvidia/segformer-b0-finetuned-ade-512-512`（15,036,944 B，150 类，sky=2）使用 **NVIDIA Source Code License**，§3.3 明确「**only may be used … non-commercially**」——**不能商用**，与工单预设相反。BiSeNet 代码 MIT，但官方权重训练于 Cityscapes 且未声明权重许可，需法务复核。
10. **SkySeg 是唯一许可证清晰（MIT）且质量经过验证的天空模型，但体积失控**：官方仓库只发布 **2.26 MB 的 ncnn 小模型**（u2netp 级），并明确「高精度模型用于我们产品，不能公开」；社区 HF 上的 **175,997,079 B ONNX** 来源存疑（上传者声明 MIT 但非版权方）。实测该 176 MB 模型在仓库自带 6 张样例上 **IoU 0.934–0.997**，320×320 输入、ImageNet 归一化、7 个输出；INT8 QDQ 后 **44,433,514 B**，IoU 仅掉 0.000–0.031。**没有既小（< 5 MB）又高质、许可清晰的现成 ONNX 天空模型。**
11. **与本仓库 `GuidedFilter.kt` 的结合可行，但现有实现是全分辨率、非流式的**：`GuidedFilter.smooth(guide, input, w, h, radius, eps)` 正是「低分辨率概率图 + 全分辨率引导图」的标准精修工具；`ImageFilters.boxMean` 是 O(N) 滑动窗口。但 `smooth` 同时持有 **约 10 个 N 大小 FloatArray**（`boxMean` 内部另有 2 个临时缓冲），24MP 全图约 **1 GB**，必须分块（建议 2048² + 32 px 重叠，约 200 MB）或改用单窗口 `partialWindow`（约 3–4 个缓冲）。成本量级与几次 3×3 盒滤波同级，远低于一次 MI-GAN 推理。

---

## 1. 测量环境与复现方法

| 项 | 值 | 证据 |
|---|---|---|
| Python | 3.14.10 | `python3 -V` |
| onnx / onnxruntime | 1.23.2 / 1.32.0（CPU EP） | `pip show`，`ort.get_available_providers()` 只返回 CPU/Azure |
| 推理机器 | Linux x86_64（非手机） | 本机 |
| MI-GAN 样图 | `MI-GAN/examples/places2_512_object/{images,masks}/10.png`（380×512，掩码 95% 为孔） | 官方仓库 |
| 24MP 合成图 | 4000×6000，孔 300×400 | 脚本拼接官方样图 |
| SkySeg 评测集 | 仓库 `eval/*.jpg` + 同名 `*.png` 掩码（6 张，4032×3024 ~ 4624×3472） | 官方仓库 |
| 计时 | `time.perf_counter()`，取 2 次最小值；RSS 用 `resource.getrusage` | 附录 A |

复现命令见附录 A。

---

## 2. 端侧 Inpainting 候选模型

### 2.1 汇总表（字节与哈希均为实测）

| 模型 | 许可证（证据） | ONNX / 权重字节 | 参数量 | 输入 | 输出 | 备注 |
|---|---|---|---:|---|---|---|
| **MI-GAN-512 Pipeline v2** | **MIT**（仓库 `LICENSE` + `LICENSE-WEIGHTS`；HF `license: mit`） | **28,079,181**（`sha256 6f1f3530…c48c40b`） | 5.98M（论文） | `image` uint8 `[B,3,H,W]` RGB；`mask` uint8 `[B,1,H,W]`，**255=保留** | `result` uint8 `[B,3,H,W]` | 图内含裁切/缩放/羽化/回贴；动态 H/W |
| MI-GAN-512 `migan.onnx`（同仓库另一份导出，README 未引用） | MIT | 29,546,882（`sha256 593eba0b…80fc5ae`） | 同上 | 同上 | 同上 | 两者 I/O 相同，未逐节点比对 |
| MI-GAN 生成器（仅权重子图） | MIT | 27,896,910（图内 initializer，含重参数化重复副本） | 5.98M | float `[1,4,512,512]` | float `[1,3,512,512]` | 需自行导出；I/O 为 concat(mask−0.5, image·mask) |
| **LaMa-Dilated**（Qualcomm） | **Apache-2.0**（HF `license: apache-2.0`；上游 `advimman/lama` LICENSE） | **183,614,539**（图 1,044,115 + 外部权重 182,568,704；zip 169,631,704） | **45.6M** | float32 `[1,3,512,512]` RGB **[0,1]**；`mask` float32 `[1,1,512,512]` **{0,1}，1=孔** | float32 `[1,3,512,512]` | **固定 512**；无 FFT；输出已内部合成（孔外保持原图） |
| big-lama（IOPaint 实际使用） | Apache-2.0（`smartywu/big-lama` HF `license: apache-2.0`） | TorchScript `.pt`（非 ONNX） | 27.05M（论文） | float `[0,1]` + mask 1=孔 | 同尺寸 | 全卷积，可跑 >512 裁切；IOPaint `pad_mod=8` |

补充事实：

- MI-GAN 论文 Table 2（Places2-512）同场对比：LaMa 27.05M / 128.06 GFLOPS、MAT 61.56M / 288.78 GFLOPS、ZITS 78.49M / 666.97 GFLOPS、LDM 387.25M / 27,581.56 GFLOPS、HiFill 2.72M / 72.12 GFLOPS（FID 64.07，质量不可用）。**MI-GAN 的 15.69 GFLOPS 是唯一落在手机 CPU 可行区间的**。<https://openaccess.thecvf.com/content/ICCV2023/papers/Sargsyan_MI-GAN_A_Simple_Baseline_for_Image_Inpainting_on_Mobile_Devices_ICCV_2023_paper.pdf>
- IOPaint 的模型清单（`iopaint/model/`）里，anytext / brushnet / fcf / kandinsky / mat / power_paint / sd / sdxl / zits 全部是 diffusion 或 60M+ 参数级；真正「端侧量级」的只有 `lama.py`、`mi_gan.py`、`opencv2.py`。**在本次检索范围内（IOPaint 模型清单 + MI-GAN 论文对比表 + HF/GitHub 官方仓库），未发现 2024–2026 年新增的 <10M 参数、MIT/Apache-2.0、且可直接导出 ONNX 的通用 inpainting 权重。**

### 2.2 MI-GAN-512：许可证、I/O 与内部行为

**许可证证据链**（三条独立证据）：

| 证据 | 内容 |
|---|---|
| `Picsart-AI-Research/MI-GAN/LICENSE` | MIT，Copyright (c) 2024 Picsart AI Research (PAIR) |
| `Picsart-AI-Research/MI-GAN/LICENSE-WEIGHTS` | **单独的 MIT 文本**，README 下载权重处显式指向该文件 |
| HF `andraniksargsyan/migan` 元数据 | `"license": "mit"`，仓库含 `LICENSE` 与 `migan.onnx` / `migan_pipeline_v2.onnx` |

> 这一点值得写进合规归档：很多资料只看到代码 MIT，忽略了权重另有 `LICENSE-WEIGHTS`；MI-GAN 的作者把权重也明确成了 MIT，才使它成为本表里唯一「代码 + 权重都 MIT」的候选。

**I/O 语义（实测，不是转述）**：

```
inputs : image tensor(uint8) [batch_size, 3, height, width]
         mask  tensor(uint8) [batch_size, 1, height, width]
outputs: result tensor(uint8) [batch, 3, height, width]
```

- 掩码 **255 = 已知/保留，0 = 待修补**（README「255 denotes known region values, 0 denotes masked region」）。
- 传入 380×512 图 + 官方掩码，输出仍是 380×512；掩码外平均绝对误差 **0.07/255**（重采样带来的少量舍入，最大 8/255），掩码内平均差 **53.6/255**。说明模型确实只改孔内。
- 输入是 **NCHW**，不是 NHWC；直接喂 HWC 会报 `index: 1 Got: 380 Expected: 1`。

**Pipeline 内部的裁切/融合常量**（`scripts/create_onnx_pipeline.py`，行号为该文件）：

| 行为 | 值 | 行 |
|---|---|---|
| 上下文边距 | `padding = 128` | L120 |
| 裁切边长 | `crop_size = max(masked_w, masked_h) + 2×128`，且 `≥ 512` | L180 附近 |
| 掩码缩放 | `NEAREST` 到 512；图像 `BILINEAR` | L234–235 |
| 生成器输入 | `cat(mask − 0.5, image × mask)` → 4 通道，image ∈ [−1,1] | L238 |
| 回贴羽化 | `max_pool2d(mask, 3, stride=1, pad=1)` 先膨胀 1 px，再 `Gaussian(k=5, σ=1.0)` | L246–247 |
| 合成 | `image × mask + model_output × (1 − mask)`，**在原裁切分辨率上做** | L249 |

> 关键细节：羽化是在**原图裁切分辨率**上做的（mask 是裁切后的原尺寸），不是 512 上做的再放大，因此 5 px 羽化不会随缩放变宽。

**实测开销（桌面，仅用于相对比较）**：

| 场景 | 耗时 | 进程峰值 RSS |
|---|---:|---:|
| 380×512（官方样图） | 604–823 ms | 462 MB（session 加载后 121 MB） |
| 24 MP 全图 + 300×400 孔 | 881 ms | 856 MB |
| 600×800 预裁切 + 孔 | ~830 ms | 516 MB |
| 2048×1536 预裁切 | 547 ms | — |

结论：**耗时几乎全在 512 生成器**（约 600 ms 桌面），裁切数学只占几十毫秒；但把 24 MP 直接喂进 Pipeline 会多占 **约 340 MB**（72 MB 图像 + 24 MB 掩码 + 72 MB 输出 + 中间张量）。手机上必须在 Kotlin 侧先裁到 ≤1024²，再进模型。

**移动端参考（论文实测，ONNX CPU，512×512）**：

| 设备 | MI-GAN-512 | Co-Mod-GAN-512 |
|---|---:|---:|
| iPhone 14 Pro Max | **539.10 ms** | 1880.00 ms |
| iPad mini (5th) | 1017.67 ms | 3767.71 ms |
| iPhone X | 1131.00 ms | 3943.33 ms |
| Galaxy Tab S7+ | 1210.50 ms | OOM |
| Samsung Galaxy S8 | 2527.47 ms | OOM |
| vivo Y12 | 5385.67 ms | OOM |

256×256 模型：iPhone 14 Pro Max **296 ms**、Galaxy S8 **1476 ms**。来源：论文正文 Table 3 与补充材料 Table S.1。

### 2.3 LaMa-Dilated：它到底是什么

- Qualcomm AI Hub 的 `LamaDilated` 从 `advimman/lama` 的 **`lama-dilated_celeba-hq`** 检查点加载，`model.py` 注释写明输入 `image` 为 **[0,1] RGB**、`mask` 为 **{0,1}**，前向是 `masked = image*(1-mask)`，`concat[masked, mask]`，输出 `mask*pred + (1-mask)*image`。来源：<https://github.com/qualcomm/ai-hub-models/blob/v0.67.0/src/qai_hub_models/models/lama_dilated/model.py>
- ONNX 图只有 10 种算子：`Add/Conv/Pad/Mul/Split/Relu/ConvTranspose/Sub/Concat/Sigmoid`，共 294 节点，**无 FFT/DFT**——这正是「Dilated 版」对 NPU 友好的原因。
- 权重 **182,568,704 B = 174.1 MiB**，45.6M 参数；FP16 投影 ≈ 91.3 MB，INT8 投影 ≈ 45.6 MB（**未导出，属投影**）。
- 官方性能（Qualcomm AI Hub，**QNN/NPU**，非 ORT CPU）：Snapdragon 8 Gen 3 **54.47 ms**、8 Gen 1 **110.31 ms**、8 Elite **43.06 ms**，峰值内存 **1–434 MB**。
- 重要 caveat：权重是 **CelebA-HQ 人脸中心**，通用风景/物体消除不如 Places2 的 `big-lama`；且固定 512，裁切必须自己缩放到 512，**丢掉了 LaMa 全卷积「原分辨率推理」的优势**。

> 如果目标是"人像照片去痘/去杂物"，LaMa-Dilated 的面部先验是加分项；如果目标是"风景照去路人/去电线"，应当用 big-lama（Apache-2.0，27M 参数，但无官方 ONNX，需自己导出，FFC 的 FFT 在 ONNX/ORT 上可导出但算子重）。

### 2.4 ORT Mobile 兼容性与运行时体积

**算子兼容性**：MI-GAN Pipeline v2 共 35 种算子。逐项核对 ORT 1.17 Mobile Package 清单（最后一版清单）：

`Constant, Conv, Mul, Clip, LeakyRelu, Reshape, Cast, Concat, Add, Slice, Unsqueeze, Transpose, Resize, Pad, Shape, ConstantOfShape, Sub, ReduceMax, Gather, ReduceMin, Range, Div, Expand, Equal, Where, Squeeze, ReduceSum, ReduceMean, Less, NonZero, GatherND, Round, MaxPool, ScatterND, Identity`

—— **按算子名核对，全部在清单内**（含最容易被判死刑的 `NonZero` / `ScatterND` / `GatherND`）。来源：<https://onnxruntime.ai/docs/reference/operators/mobile_package_op_type_support_1.17.html>

但有两个前置条件：

1. **必须转 ORT format**。1.19 之前 mobile 包只加载 `.ort`；官方文档：「IMPORTANT: The ORT Mobile pre-built packages with reduced operator support are no longer available starting from 1.19. Please use the versions with full operator support or do a custom build.」<https://onnxruntime.ai/docs/reference/operators/MobileOps.html>
2. **1.18.0 是最后一个 mobile 包**（Maven `com.microsoft.onnxruntime:onnxruntime-mobile:1.18.0`，2024-05）。

**运行时体积（AAR 实测）**：

| 包 | AAR | arm64 `.so` 合计 | armeabi-v7a | 说明 |
|---|---:|---:|---:|---|
| `onnxruntime-android:1.22.0` | **28,515,295** | **18,314,824**（18,214,224 + 100,600） | 13,316,012 | 完整算子，可直接加载 `.onnx` |
| `onnxruntime-mobile:1.18.0` | **7,241,017** | **4,504,888**（3,735,912 + 768,976） | 3,320,040 | 仅 reduced ops + ORT format，1.18 封版 |

> 工程含义：APK 增量上，完整 ORT ≈ **+18.3 MB**（arm64），mobile ≈ **+4.5 MB**，但 mobile 需要额外做 `.ort` 转换与算子白名单校验，而且 1.18 之后不再维护。
> 另一个选项是 `onnxruntime-android-qnn`（1.22.0），只在骁龙 NPU 上加速；MI-GAN 的 `NonZero`/动态 shape 是否会拆回 CPU 需要真机 profile。

**FP16 在 CPU 上的事实**：ORT 文档明确写「**the CPU version of ONNX Runtime doesn't support float16 ops**」，所以 FP16 模型在 Android CPU EP 上只会得到「体积减半 + 前后插入 Cast」，不会更快；要加速得走 NNAPI（`NNAPI_FLAG_USE_FP16`）或 QNN。来源：<https://onnxruntime.ai/docs/performance/model-optimizations/float16.html>、<https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html>

### 2.5 FP16 / INT8 量化对生成式修补的实测影响

**方法**：固定同一张 380×512 样图与官方掩码；以 FP32 Pipeline 输出为基准，分别做

- **FP16 权重舍入**：所有 float initializer 经 `float16 → float32` 往返（不改图类型，隔离权重精度影响）；
- **INT8 weight-only**：Conv 权重按输出通道对称量化到 [−127,127] 再反量化；
- **INT8 QDQ 静态**：`quantize_static(op_types_to_quantize=["Conv"], per_channel=True, QDQ, QUInt8 激活 / QInt8 权重)`，3 张官方样图校准。

| 变体 | 文件字节 | PSNR vs FP32 | 平均误差 | p99 | 最大误差 | 孔内 Laplacian 方差 |
|---|---:|---:|---:|---:|---:|---:|
| FP32（基准） | 28,079,181 | — | — | — | — | 2821.2 |
| FP16 权重 | （同尺寸，仅数值模拟） | **57.27 dB** | 0.12 | 1 | **4** | 2819.9 |
| INT8 weight-only | （同尺寸，仅数值模拟） | **31.03 dB** | 4.30 | 26 | **149** | 2896.3 |
| INT8 QDQ 静态 | **10,858,458** | **16.24 dB** | 29.46 | 116 | **231** | **2211.7（−22%）** |
| FP16 整图 naive 转换 | 14,133,691 | 无法加载 | — | — | — | — |

关键坑点：

1. **FP16 权重几乎无损**（最大 4/255），但**整图 naive 转换会坏**：`Type Error: Type parameter (T) of Optype (Less) bound to different types (tensor(float) and tensor(float16))`——Pipeline 的裁切数学里有 float 比较/`Where`/`NonZero`。正确做法是只导出生成器（纯 Conv/LeakyRelu/Add/Mul/Clip/Resize），裁切与融合留在 Kotlin。
2. **INT8 weight-only 已经有可见色带**：渐变天空/皮肤上 p99 误差 26/255，PSNR 31 dB 对生成图是不够的（一般 >35 dB 才安心）。
3. **INT8 静态量化是灾难**：PSNR 16 dB、最大 231、孔内高频能量掉 22%，即「纹理被抹平 + 边界色差」。原因：MI-GAN 生成器没有 BatchNorm，且 `LeakyReLU + 乘性 gain + Clip` 的激活动态范围极大，3 张图的校准 min/max 完全覆盖不了；即使增加校准集，GAN 的逐像素精度对量化噪声也非常敏感。
4. **判别式模型（天空分割）对 INT8 友好得多**（见 §4.2），这印证了「生成式 vs 判别式」的量化难度差异，不能把分割模型的量化经验搬到 inpainting。

> 结论：**MI-GAN 只应上 FP32 或 FP16（且 FP16 只在 GPU/NPU EP 上有收益）**；INT8 需要 QAT 或逐层敏感度分析（MI-GAN 论文全文未讨论量化，未提供 QAT 权重），不建议作为 v1 路径。

---

## 3. 高分辨率相机照片的局部 Crop-Inpaint-Blend

### 3.1 上下文裁切：业界实际用的数字

| 实现 | 上下文边距 | 裁切/缩放策略 | 源代码 |
|---|---|---|---|
| **MI-GAN 官方 Pipeline** | **固定 128 px** | `crop = bbox + 2×128`，`max(crop, 512)` 后 **resize 到 512** | `scripts/create_onnx_pipeline.py` L120/L180/L234 |
| **IOPaint（MI-GAN 分支）** | **128 px**（`config.hd_strategy_crop_margin = 128`） | 按连通域 `boxes_from_mask` 逐个裁切，`resize_max_size(..., 512)`，**只回贴掩码内** | `iopaint/model/mi_gan.py` L54–69 |
| **IOPaint（LaMa 分支）** | 128 px（`hd_strategy_crop_margin` 默认） | `pad_mod=8`、不缩放（LaMa 全卷积），触发阈值 `> 800 px` | `iopaint/schema.py` L297–303、`lama.py` L31 |
| **LaMa-Dilated（Qualcomm）** | 无内建裁切 | 固定 512×512，调用方自己 resize | `model.py` |

要点：

- 边距是**像素常数**，不是倍率。128 px 对 24MP 图只有 2% 宽度，语义上下文其实很窄；IOPaint 用「连通域 bbox + 128」而不是整图缩放，是为了让模型看到**局部**而不是被 24MP 平均掉。
- 多连通域会**逐个裁切、逐个推理**再拼回（IOPaint `boxes_from_mask` + `cv2.findContours`）。这意味着一次涂抹如果断成 10 段，就会跑 10 次 512 推理——手机端必须限制连通域数量或合并 bbox。
- MI-GAN 官方 Pipeline 的 `crop_size = max(w,h) + 256`，且**最小 512**：小于 256 的孔会被放大到 512 再缩回，反而损失真实细节；大于 256 的孔会被缩小，纹理变糊。

### 3.2 单次涂抹面积建议

由官方常量可推导（512 模型）：

| 掩码外接框（单边） | 裁切边长 | 送模缩放 | 结果 |
|---:|---:|---:|---|
| 64 px | 512（最小值） | **放大 8×** | 抠图边缘会「过度生成」，不建议小于 ~128 |
| 256 px | 512 | **1:1** | 最佳：细节与上下文平衡 |
| 384 px | 640 | 缩小 1.25× | 轻微变糊 |
| 512 px | 768 | 缩小 1.5× | 明显变糊 |
| 1024 px | 1280 | 缩小 2.5× | 只剩语义，纹理丢失 |

**建议写进 spec 的阈值**：

- 单笔涂抹外接框 **≤ 256×256 px**（相对 24MP 原图）；
- 单次涂抹面积 **≤ 65,536 px²**（256²），约占 24MP 的 **0.27%**；
- 超过时 UI 提示「分多次小面积涂抹」，或后台按 256 px 网格切块、逐块推理（块间留 128 px 重叠）。
- 这与 MI-GAN README 的原文一致：「when inpainting high resolution images the best results can be achieved with **small, incremental brush strokes**」<https://github.com/Picsart-AI-Research/MI-GAN#for-developers>

### 3.3 回贴融合：三种做法的真实分布

| 实现 | 融合方式 | 参数 | 是否有泊松/拉普拉斯 |
|---|---|---|---|
| MI-GAN 官方 Pipeline | 掩码先 `maxpool 3×3` 膨胀 1 px，再 `Gaussian(5, σ=1.0)`，然后 `image*mask + out*(1-mask)` | 5 px 羽化，固定 | 否 |
| IOPaint（MI-GAN/LaMa） | **硬粘贴**：`crop_mask < 127` 的像素恢复原图，其余直接覆盖 | 无羽化 | **否**（全仓库无 `seamlessClone`/`pyrUp`） |
| IOPaint（diffusion） | 可选 `sd_mask_blur=11` 高斯羽化；可选 `sd_match_histograms` 做 CDF 直方图匹配 | k=11、逐通道 CDF | 否 |
| 本仓库可加 | 3–9 px 羽化 + 可选均值/直方图匹配 + 仅在明显色差时 Poisson | — | 可选 |

事实核查：

- `grep -rn "seamlessClone\|poisson\|Laplacian\|pyrUp\|pyrDown" iopaint --include=*.py` → **0 命中**。IOPaint 的「无缝」靠的是模型本身的边界连续性 + 掩码内硬覆盖，不是后处理融合。
- 直方图匹配只在 `DiffusionInpaintModel.forward_post_process` 里调用（`base.py` L398–400），且默认 `sd_match_histograms=False`。其实现是「只用掩码外像素统计 CDF，再 LUT 映射整张结果」（`base.py` L210–244）——这正是「边缘色差匹配」的轻量替代，**O(256)**，手机上可忽略成本。
- MI-GAN 的 5 px 羽化是**唯一**默认开启的融合；它足够盖住 1–2 px 的接缝，但盖不住整块色偏。

**工程建议（按成本递增）**：

1. **默认**：掩码形态学膨胀 1–2 px + 3–5 px 高斯羽化（等价 MI-GAN 官方做法），O(N)，无额外依赖。
2. **色差兜底**：在掩码外 8–16 px 环带内统计 R/G/B 均值差，给生成结果加一个常数偏移（或做 256-bin CDF 匹配）。成本 O(N)，可消除「补丁偏绿/偏亮」。
3. **仅在用户手动触发"深度融合"时**用 Poisson（`cv2.seamlessClone`）：桌面实测 512² 31 ms、1024² 105 ms、2048² 583 ms；同尺寸 alpha 混合是 11/31/208 ms，**Poisson 约 3× 成本**。手机上 1024² 预计 200–400 ms，且需要 OpenCV photo 模块（额外 ~2–4 MB `.so`），不建议默认开启。
4. **不要**做拉普拉斯金字塔融合：它解决的是「不同曝光/不同来源图像」的频带拼接，对 inpainting 的「同源、局部、已对齐」场景收益低于羽化 + 色差匹配，代码量与内存却不小。

### 3.4 移动端开销小结

| 环节 | 24MP 全图 | 256 px 孔（预裁切 512²） |
|---|---|---|
| 解码 + 裁切 | 72 MB RGBA 位图（不可避） | 1 MB |
| 模型推理 | 600 ms（桌面）/ 1–3 s（手机外推） | 同左（模型固定 512） |
| 羽化 + 合成 | O(24M) ≈ 20–60 ms（手机） | O(0.26M) ≈ < 5 ms |
| Poisson（可选） | 不建议全图 | 1024² ≈ 200–400 ms |
| 峰值内存 | ~340 MB 额外（若整图进 ONNX） | ~150 MB（模型激活） |

---

## 4. 可商用开源天空分割候选

### 4.1 候选汇总（许可证是硬门槛）

| 模型 | 许可证 | 体积 | 输入/输出 | 是否可商用 | 结论 |
|---|---|---|---:|---|---|
| **SkySeg（`xiongzhu666`）** | **MIT**（仓库 LICENSE，Copyright 2021 xiongzhu666） | 官方小模型 **2,257,156 B**（`.bin`）+ **29,420 B**（`.param`，ncnn fp16）；社区 ONNX **175,997,079 B** | 320×320、ImageNet 归一化、7 个输出 | ✅（官方部分） | 唯一许可清晰；质量证据只对存疑的大模型成立 |
| **SegFormer-B0 ADE20K** | **NVIDIA Source Code License**（§3.3 仅限非商业） | 15,036,944 B（safetensors） | 512×512、150 类、sky=2、logits 1/4 分辨率 | ❌ | **不可商用**，直接排除 |
| BiSeNet（`CoinCheung/BiSeNet`） | 代码 MIT；**权重许可未声明** | 未测 | 512×512、Cityscapes 19 类（含 sky） | ⚠️ 需法务 | 权重训练于 Cityscapes，仓库未给权重许可 |
| MobileNetV3/DeepLabV3（TF Model Zoo） | Apache-2.0 | 未测 | PASCAL VOC 21 类 | ✅ | **VOC 没有 sky 类**，不可用 |
| Wolosky-Net（HF） | **无 LICENSE** | 未测 | — | ❌ | 仓库无许可证 = 保留所有权利 |
| SkyAR（`jiupinjia/SkyAR`） | **无 LICENSE** | — | — | ❌ | 同上 |

> 结论：**可商用 + 现成 + 有质量证据的只有 SkySeg**；SegFormer-B0 是本次调研最重要的「预期反转」。

### 4.2 SkySeg 事实核查

**官方仓库（MIT）事实**：

- README 原文：「I trained the sky-segmentation model by **U-2-Net**」「Upload a small sky-seg model of **2Mb** (trained by u2netp) for demo（**We couldn't public the high-precision model because it used in our product**）」。
- 仓库根目录确实只有 `skysegsmall_sim-opt-fp16.bin`（2,257,156 B）+ `.param`（29,420 B）+ ncnn C++ demo；**没有官方高精度 ONNX**。
- README 自述缺陷：「in the scene of building, some detail of building will be considered as sky by mistake」「For some special textured clouds, the algorithm has some flaws」。
- `mask_refine.cpp` 是论文 arXiv:2006.10172 的天空掩码后处理（置信度 matting + 抗锯齿下采样），**专门为树枝细节设计**，作者称「retain much more details」。

**社区 176 MB ONNX（HF `JianyuanWang/skyseg`）事实**：

- 上传者声明 MIT，但明确写「I do not hold the copyright to the model… This hosting is intended solely for accessibility」——**不是版权方授权**。其体积（176 MB）与官方 README 所说「167M 高精度模型（不公开）」吻合，且原作者写的是「couldn't public the high-precision model because it used in our product」，两者相互冲突。**合规上不能直接采用**。
- 输入 `input.1` `[1,3,320,320]` float32，ImageNet mean/std；输出 **7 个** `[1,1,320,320]`（U2-Net 的 d0…d6 侧输出），官方脚本取第一个并做**逐图 min-max 归一化**（对量化噪声敏感）。
- 实测（仓库自带 6 张样例，IoU vs 自带掩码）：

| 样例 | 分辨率 | FP32 IoU | INT8 IoU | FP32 耗时（桌面） |
|---|---|---:|---:|---:|
| 233129.jpg | 4032×3024 | 0.9648 | 0.9616 | 492 ms |
| 57.jpg | 4624×3472 | 0.9974 | 0.9974 | 533 ms |
| IMG_20210309_211233.jpg | 4624×3472 | 0.9914 | 0.9916 | 612 ms |
| IMG_20210504_193136.jpg | 3472×4624 | 0.9933 | 0.9933 | 309 ms |
| e630201f…jpg | 1200×1600 | **0.9342** | **0.9028** | 407 ms |
| mmexport1623561100792.jpg | 1440×1080 | 0.9866 | 0.9822 | 382 ms |

- 体积：FP32 **175,997,079 B**；社区 FP16 **88,084,505 B**；本次 INT8 QDQ（Conv，8 张校准）**44,433,514 B**，各样例 IoU 变化在 **−0.0002 ~ +0.031** 之间（最差 0.9342 → 0.9028）。**判别式模型对 INT8 的容忍度显著高于 inpainting**（对比 §2.5 的 16 dB）。
- 一个工程细节：该模型是 **opset 11**，而 per-channel QDQ 的 `DequantizeLinear` 需要 opset 13；直接量化后加载会报 `Unrecognized attribute: axis`，必须先 `version_converter.convert_version(model, 13)`（实测 44,472,559 B，可正常推理）。
- 但 44 MB 仍然「不能进 APK」，且 320×320 上采样到 24MP 后，树枝/电线级别的 1–2 px 边缘会糊；这正是 `mask_refine.cpp` 存在的理由。

**结论**：SkySeg 的高精度权重实际上**不可获得（合规层面）**；可获得的小模型只有 2.26 MB ncnn，质量未实测（本次尝试用 ncnn Python 绑定失败：模型期望的归一化与 `Mat.from_pixels` 不匹配，输出为常量/NaN，见 §6）。若产品需要「树枝级」天空边缘，现实路径是**自训一个 2–5 MB 的蒸馏模型**（U2Netp/MobileNetV3 骨干 + SkySeg 大模型伪标签），而不是直接找现成 ONNX。

### 4.3 与 `GuidedFilter.kt` 结合精修的可行性

现有 API（`editor/src/main/java/cn/devcxl/photosync/editor/domain/GuidedFilter.kt`）：

```kotlin
fun smooth(guide: FloatArray, input: FloatArray, width: Int, height: Int, radius: Int, epsilon: Float): FloatArray
fun partialWindow(values: FloatArray, width: Int, height: Int, radius: Int, epsilon: Float): FloatArray
```

`ImageFilters.boxMean` 是 O(N) 滑动窗口（`ImageFilters.kt` L101–126）。`smooth` 需要 **6 次 boxMean**，中间同时持有 `meanGuide / meanInput / guideSquares / guideInput / meanGuideSquares / meanGuideInput / coefficients / intercepts / meanCoefficients / meanIntercepts / 返回值` 共 **约 10 个 `FloatArray(N)`**；`boxMean` 内部还会各分配 `horizontal` 与 `out` 两个临时数组。`partialWindow` 只需 `mean / squares / 输出` 约 3–4 个缓冲，是全分辨率场景的省内存替代。

**可行方案（低分辨率概率图 → 全分辨率掩码）**：

1. 模型在 320×320 输出 sky 概率 `p_low`（float）。
2. 双线性上采样到工作分辨率（建议长边 ≤ 2048，或分块 2048²）。
3. 以**亮度**（或 RGB→Y 的 `Y`）为 guide，`p_low_up` 为 input，跑 `GuidedFilter.smooth(guide, p, w, h, radius=8, eps=1e-3)`。
4. 阈值 0.5 得到 alpha，再 `smoothstep` 1–2 px 得到最终 mask。

为什么有效：guided filter 的线性模型 `q = a·I + b` 在 `var(I) >> ε` 的边缘处 `a→1`，即**保留引导图的高频边缘**；在平坦天空 `a→0`，即**平滑模型噪声**。这正好补上 320×320 上采样丢失的树枝/建筑边缘。

**成本（估算，需真机验证）**：

- 一次 `smooth` 在 N 像素上 ≈ 6 次 O(N) 扫描 + 约 10 个 N 大小缓冲（峰值含临时数组约 12N）。2048² = 4.2M 像素 → 约 25M 次浮点运算 + 约 **200 MB** 峰值；手机上预计 **30–80 ms**（与几次 3×3 盒滤波同量级），但内存需按 200 MB 预算。
- 24MP 全图一次跑需要约 12×96 MB ≈ **1.1 GB**，不可行；必须分块（2048² + 32 px 重叠，约 6–8 块），或先降采样到 2048 长边再上采样 alpha（更省）。若只是全分辨率精修边缘，改用 `partialWindow`（3–4 个缓冲）更合适。
- 若只做「mask 边缘 1–2 px 精修」，可只在掩码带（dilate−erode）内跑，成本降到 O(边缘像素)。

**与 `mask_refine.cpp` 的关系**：后者是「置信度 matting + 抗锯齿下采样」，对树枝更激进（会把低置信度像素变半透明）；guided filter 更保守（只做边缘对齐）。两者可以叠加：先 guided filter 对齐边缘，再对 0.3–0.7 的置信带做 1 px feather。**不需要引入 OpenCV**。

---

## 5. 推荐决策（可执行清单）

### 5.1 Inpainting

1. **模型选 MI-GAN-512（MIT）**，不要选 LaMa-Dilated 作为 v1：
   - 体积：28.1 MB vs 183.6 MB；
   - 参数：5.98M vs 45.6M；
   - 场景：Places2 通用 vs CelebA-HQ 人脸；
   - 许可：两者都可商用，但 MI-GAN 权重也有独立 MIT。
2. **导出「生成器-only + 固定 512」ONNX**，把裁切/缩放/羽化/回贴放到 Kotlin：
   - 输入 `float32 [1,4,512,512]`（`mask−0.5`, `image·mask`），输出 `[1,3,512,512]`；
   - 可去掉 `NonZero/ScatterND/Range/Shape` 等 20 余种动态算子，图更小、ORT 优化更充分、内存更低；
   - 代价：需要 PyTorch + 官方 `.pt` 权重重新导出（权重在 Google Drive，需人工下载；或用 HF 的 `migan.onnx` 反推）。
3. **分发**：模型 28 MB 走「首次进入消除面板按需下载」（与 MediaPipe 报告的混合方案一致），SHA-256 固定为本文 §2.1 的值；APK 只加 runtime。
4. **Runtime**：
   - 若接受 +18.3 MB：`onnxruntime-android:1.22.0`，直接加载 `.onnx`，风险最低；
   - 若必须 +4.5 MB：`onnxruntime-mobile:1.18.0` + `.ort` 转换 + 算子白名单校验，但 1.18 已封版，且需自行验证 35 个算子；
   - **不要**指望 FP16 在 CPU 上提速。
5. **精度**：只上 FP32；FP16 仅在检测到 NNAPI/QNN 可用时作为可选；**不做 INT8**（PSNR 16 dB）。
6. **裁切**：Kotlin 侧按连通域 bbox + 128 px 边距裁切，限制单次 bbox ≤ 256 px；多连通域合并或串行，避免一次涂抹跑 10 次模型。
7. **融合**：3–5 px 羽化 + 可选 CDF 直方图匹配；Poisson 作为「深度融合」开关，默认关。
8. **内存**：预裁切到 ≤1024² 再进模型；24MP 整图进 ONNX 会多 340 MB。

### 5.2 天空

1. **v1 不要引入 176 MB / 44 MB 的 SkySeg**：体积与合规都不成立（上传者非版权方）。
2. 若只需要「天空区域提亮/换天」的粗 mask：用**经典算法 + guided filter**（顶部区域 + 颜色/亮度模型 + 边缘精修），零模型体积；先验证效果再决定是否上模型。
3. 若必须上模型：**自训 2–5 MB 学生模型**（U2Netp/MobileNetV3 + SkySeg 大模型伪标签 + 公开天空数据），或与 SkySeg 作者联系获取授权；不要直接分发社区 176 MB 权重。
4. **SegFormer-B0 明确排除**（NVIDIA §3.3 非商业）；BiSeNet 需法务确认权重许可。
5. 精修统一走 `GuidedFilter.smooth`（radius 8、eps 1e-3），分块 2048² + 32 px 重叠。

---

## 6. 未验证项与风险

| 项 | 状态 | 影响 |
|---|---|---|
| Android 真机推理耗时/内存 | **未实测**（无设备） | 论文/Qualcomm 数据是不同设备、不同 runtime；中端机可能 2–3 s |
| MI-GAN 生成器-only ONNX 的实际体积/速度 | 未导出（需 PyTorch + `.pt`） | 预计 27–28 MB、比 Pipeline 少 20+ 算子；未验证 |
| FP16 在 NNAPI/QNN 上的 MI-GAN 精度 | 未实测（无设备） | 权重舍入 PSNR 57 dB 只是权重层面；激活 FP16 风险更高 |
| INT8 QAT | 论文未提供 | 若要做 INT8，需自训 |
| SkySeg 小模型（2.26 MB ncnn）质量 | **未实测**：`ncnn.Mat.from_pixels` 喂 float CHW 时段错误；改用 `Mat(w,h,c).numpy()` 写入后输出为常量/NaN（归一化与输出 blob 未对齐官方 C++ demo） | 无法判断「2 MB 是否够用」，这是本报告最大缺口 |
| 社区 176 MB SkySeg 的版权 | 存疑（上传者非版权方，作者称不公开） | 不能商用分发 |
| BiSeNet 权重许可 | 未声明 | 需法务 |
| 24MP 全链路内存 | 桌面 RSS 外推 | Android 低位设备可能 OOM |
| Poisson 手机耗时 | 桌面 3× 外推 | 1024² 约 200–400 ms，未实测 |

---

## 附录 A：复现命令

```bash
# 1) MI-GAN ONNX（官方 + HF）
curl -sSL -o migan_pipeline_v2.onnx \
  https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx
sha256sum migan_pipeline_v2.onnx        # 6f1f3530…c48c40b, 28,079,181 B
curl -sSL -o migan.onnx \
  https://huggingface.co/andraniksargsyan/migan/resolve/main/migan.onnx

# 2) LaMa-Dilated（Qualcomm v0.67.0）
curl -sSL -o lama_dilated_onnx.zip \
  https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/lama_dilated/releases/v0.67.0/lama_dilated-onnx-float.zip
unzip -l lama_dilated_onnx.zip          # 1,044,115 + 182,568,704

# 3) SkySeg 176MB（仅用于评测，勿分发）
curl -sSL -o skyseg.onnx https://huggingface.co/JianyuanWang/skyseg/resolve/main/skyseg.onnx
sha256sum skyseg.onnx                   # ab9c34c6…9aa1d39, 175,997,079 B

# 4) ONNX 规格
python3 - <<'PY'
import onnx
for f in ["migan_pipeline_v2.onnx","skyseg.onnx"]:
    m=onnx.load(f, load_external_data=False); g=m.graph
    print(f, [(i.name,[d.dim_value or d.dim_param for d in i.type.tensor_type.shape.dim]) for i in g.input])
    print(" ops", sorted({n.op_type for n in g.node}))
PY

# 5) FP16 / INT8 量化
python3 -m pip install onnx onnxruntime onnxconverter-common
python3 - <<'PY'
import onnx
from onnxconverter_common import float16
m=onnx.load("migan_pipeline_v2.onnx")
m16=float16.convert_float_to_float16(m, keep_io_types=True, disable_shape_infer=True)
onnx.save(m16,"migan_fp16.onnx")   # 14,133,691 B，但加载报 Less 类型冲突
PY

# 6) INT8 静态量化（MI-GAN / SkySeg 同法）
python3 - <<'PY'
from onnxruntime.quantization import quantize_static, CalibrationDataReader, QuantType, QuantFormat
# CalibrationDataReader 返回 {"image": uint8 NCHW, "mask": uint8 NCHW}
quantize_static("migan_pipeline_v2.onnx","migan_int8.onnx",Reader(),
                quant_format=QuantFormat.QDQ, per_channel=True,
                activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
                op_types_to_quantize=["Conv"])
PY

# 7) 运行时体积
curl -sSL -O https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar
unzip -l onnxruntime-android-1.22.0.aar | grep '\.so'
curl -sSL -O https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-mobile/1.18.0/onnxruntime-mobile-1.18.0.aar
unzip -l onnxruntime-mobile-1.18.0.aar | grep '\.so'
```

## 附录 B：一手来源

| 主题 | 链接 |
|---|---|
| MI-GAN 仓库（LICENSE / LICENSE-WEIGHTS） | https://github.com/Picsart-AI-Research/MI-GAN |
| MI-GAN ONNX Pipeline 导出脚本 | https://github.com/Picsart-AI-Research/MI-GAN/blob/main/scripts/create_onnx_pipeline.py |
| MI-GAN ONNX 权重（HF，MIT） | https://huggingface.co/andraniksargsyan/migan |
| MI-GAN 论文（ICCV 2023，Table 2/3） | https://openaccess.thecvf.com/content/ICCV2023/papers/Sargsyan_MI-GAN_A_Simple_Baseline_for_Image_Inpainting_on_Mobile_Devices_ICCV_2023_paper.pdf |
| MI-GAN 补充材料（Table S.1，512 手机速度） | https://openaccess.thecvf.com/content/ICCV2023/supplemental/Sargsyan_MI-GAN_A_Simple_ICCV_2023_supplemental.pdf |
| LaMa 仓库（Apache-2.0） | https://github.com/advimman/lama |
| big-lama 权重（HF，apache-2.0） | https://huggingface.co/smartywu/big-lama |
| LaMa 论文（WACV 2022） | https://openaccess.thecvf.com/content/WACV2022/papers/Suvorov_Resolution-Robust_Large_Mask_Inpainting_With_Fourier_Convolutions_WACV_2022_paper.pdf |
| Qualcomm LaMa-Dilated 模型卡（Apache-2.0） | https://huggingface.co/qualcomm/LaMa-Dilated |
| Qualcomm AI Hub 性能页 | https://aihub.qualcomm.com/models/lama_dilated |
| Qualcomm 导出源码（输入 [0,1]，mask {0,1}） | https://github.com/qualcomm/ai-hub-models/blob/v0.67.0/src/qai_hub_models/models/lama_dilated/model.py |
| IOPaint（Apache-2.0） | https://github.com/Sanster/IOPaint |
| IOPaint MI-GAN 裁切/回贴 | https://github.com/Sanster/IOPaint/blob/main/iopaint/model/mi_gan.py |
| IOPaint 通用裁切/融合/直方图匹配 | https://github.com/Sanster/IOPaint/blob/main/iopaint/model/base.py |
| IOPaint 默认参数（margin=128, trigger=800） | https://github.com/Sanster/IOPaint/blob/main/iopaint/schema.py |
| ORT Mobile 预编译包已停供（1.19 起） | https://onnxruntime.ai/docs/reference/operators/MobileOps.html |
| ORT 1.17 Mobile 算子清单 | https://onnxruntime.ai/docs/reference/operators/mobile_package_op_type_support_1.17.html |
| ORT custom build / reduced ops | https://onnxruntime.ai/docs/build/custom.html |
| ORT FP16（CPU 不支持 fp16 算子） | https://onnxruntime.ai/docs/performance/model-optimizations/float16.html |
| ORT 量化（QDQ / static / calibration） | https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html |
| ORT NNAPI EP（FP16 relaxation） | https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html |
| ORT Android 1.22.0 AAR | https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/ |
| ORT Mobile 1.18.0 AAR | https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-mobile/1.18.0/ |
| SkySeg 仓库（MIT） | https://github.com/xiongzhu666/Sky-Segmentation-and-Post-processing |
| SkySeg 社区 ONNX（175,997,079 B） | https://huggingface.co/JianyuanWang/skyseg |
| SkySeg FP16（88,084,505 B） | https://huggingface.co/voyagerfromeast/skyseg |
| SegFormer-B0 ADE20K 模型卡 | https://huggingface.co/nvidia/segformer-b0-finetuned-ade-512-512 |
| SegFormer 许可证 §3.3（仅非商业） | https://github.com/NVlabs/SegFormer/blob/master/LICENSE |
| BiSeNet（MIT 代码） | https://github.com/CoinCheung/BiSeNet |
| 本仓库 GuidedFilter | `editor/src/main/java/cn/devcxl/photosync/editor/domain/GuidedFilter.kt` |
