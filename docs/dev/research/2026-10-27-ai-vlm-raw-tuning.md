# 视觉大模型生成 LibRaw 调参参数、端上自动调色：技术调研

> 调研时间：2026-10-27　|　范围：Android 端 RAW/JPEG 后处理 + 可选启用的云端/端侧视觉模型
> 关联代码：`libraw/src/main/cpp/*.cpp`（`decodeToRGB` 中参数硬编码）、`libraw/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt`

## 研究结论摘要

1. **方向成立，但不是「让 AI 出个配置文件就能一键出好片」**：已有同构研究（PhotoArtAgent 2025：把直方图喂给 VLM，输出 Lightroom 参数 JSON；RetouchLLM 2025：生成 7 类光度操作的程序）。但同期基准显示 **VLM 的审美判断准确率接近抛硬币**（AesBench：最强模型三分类 52.86%），且**视觉结构化输出仍不稳定**（SO-Bench, CVPR 2026）。因此产品定位应是「给建议 + 一键尝试 + 可回退」，而不是「AI 决定最终成片」。
2. **LibRaw 的能力边界被高估了**：LibRaw 是 **RAW 解码器**，不是调色软件。它能调的是解码前的线性域参数（`exp_shift`/`exp_preser` 曝光、`bright` 增益、`highlight` 高光恢复、`user_mul` 白平衡、`gamm` 幂函数曲线、`threshold`/`med_passes`/`fbdd_noiserd` 降噪、`output_color` 色彩空间）。**它没有「对比度」「饱和度」「阴影/高光」这些感知域滑杆**：`user_sat` 是传感器饱和点（白点），不是 HSL 饱和度；`gamm` 只能表达幂函数 + 趾部斜率，表达不了 S 曲线。所以「对比度/饱和度/鲜艳度」必须在解码之后另做一层（GPU shader / LUT / CPU 曲线）。
3. **必须设计成两层参数 + 两阶段渲染**：`rawParams`（进 JNI，改一次要重新解码）+ `toneParams`（解码后实时作用于 8/16bit 位图）。预览用缩略图尺寸解码 + GPU 实时预览，**只有导出才用全参数做全分辨率重解码**。否则每滑一次滑杆都要等几秒。
4. **成本不是瓶颈，隐私和延迟才是**：Gemini 官方价目表显示单张图像输入约 560 tokens（≈ 0.0011 美元/张量级），一次调参调用成本约千分之一美元；真正的成本是 **PIPL 合规**（人脸属敏感个人信息，需单独同意；跨境提供需安全评估或标准合同）与 **2~10 秒网络往返**。
5. **推荐落地顺序**：先做**纯本地算法基线**（直方图自动曝光 + 自动白平衡 + 曲线），它零成本、零隐私风险、可离线、可作为 AI 的兜底与对照；再把 AI 作为「可选增强」，只输出**受限数值**（不是代码、不是任意参数），失败必须能静默降级（置信度：高，属于工程判断 + 上述证据支撑）。

## 背景与问题定义

- **研究目标**：评估「把预览图交给具备视觉能力的大模型 → 得到 LibRaw 调参配置 → 在手机端批量处理」这条链路的可行性、正确姿势、成本与风险，并给出可验证的实施路径。
- **范围**：单机 Android 应用；照片为相机 RAW（CR3/ARW/NEF/RAF/DNG 等，项目已支持）与 JPEG；模型侧覆盖云端 API 与端侧小 VLM 两类。
- **限制**：不改 PTP 协议层；不引入服务端中转（可选）；模型能力以公开基准与官方文档为准，不臆测私有模型表现。
- **输出形式**：结论 + 参数映射表 + 方案对比 + 分阶段建议 + 评估方法。

## 研究方法

- **来源策略**：LibRaw 官方 API 文档与官方论坛（作者 Alex Tutubalin 直接答复）；Android 官方文档；arXiv/CVPR/ICCV 论文；厂商价目表；中国法律法规原文。
- **迭代轮次**：
  - 第 1 轮：确认 LibRaw 到底能调什么 → 发现「对比度/饱和度」不在 LibRaw 内，纠正原始设想。
  - 第 2 轮：找「VLM 输出编辑参数」的先例 → 找到 PhotoArtAgent / MonetGPT / RetouchLLM / PixTalk，同时找到反向证据 AesBench、SO-Bench。
  - 第 3 轮：核对端侧可行性（Gemma 3n / Qwen2.5-VL-3B on Android）、成本、合规与降级策略。
- **已知局限**：未实测本项目 LibRaw 构建在目标机型上的解码耗时；各家模型价格与版本更新快，文中价格仅作量级参考。

## 关键发现

### 发现 1：LibRaw 的 `dcraw_process` 参数表是「解码器参数」，不是「调色滑杆」

- **发现内容**：`libraw_output_params_t` 中与画面观感相关的字段（官方文档原文语义）：

  | 字段 | 作用 | 取值范围 / 默认 | 域 |
  |---|---|---|---|
  | `exp_correc` | 是否开启曝光补偿 | 0 关 / >0 开（默认关） | **demosaic 前**（线性） |
  | `exp_shift` | 曝光位移（线性倍数） | 0.25（暗 2 档）~ 8.0（亮 3 档），默认 1.0 | demosaic 前 |
  | `exp_preser` | 提亮时保留高光 | 0.0 ~ 1.0，默认 0.0 | demosaic 前 |
  | `bright` | 输出增益 | 默认 1.0 | 解码后 |
  | `no_auto_bright` / `auto_bright_thr` | 关闭 / 调节直方图自动提亮（允许被裁切的像素比例） | 默认 0.01；现代高像素机建议 0.001 ~ 0.00003 | 解码后 |
  | `adjust_maximum_thr` | 通道最大值自动校正 | 默认 0.75；设 0 关闭 | 解码后 |
  | `highlight` | 高光处理 | 0 裁切 / 1 unclip / 2 blend / 3+ rebuild | 解码 |
  | `gamm[6]` | 伽马曲线（幂 + 趾部斜率） | sRGB: `gamm[0]=1/2.4, gamm[1]=12.92` | 解码后 |
  | `user_mul[4]` | 白平衡 RGBG 乘子 | 1.0 附近 | 解码前 |
  | `user_sat` | **传感器饱和点（白点）** | 原始数据最大值量级 | 解码前 |
  | `threshold` / `med_passes` / `fbdd_noiserd` | 小波降噪 / 中值滤波 / FBDD | 0 / 0 / 0 | 解码 |
  | `output_bps` | 输出位深 | 8 / 16 | 输出 |
  | `output_color` | 输出色彩空间 | 0 raw … 1 sRGB … 8 Rec.2020 | 输出 |

  关键纠正：**`user_sat` 不是 HSL 饱和度**，LibRaw 作者明确答复「它是设置传感器饱和（最大值），与 HSL 空间无关」；**没有** `contrast` / `saturation` / `shadows` / `clarity` 字段；`gamm` 只能表达幂函数与趾部斜率，无法表达摄影常用的 S 曲线（因此「对比度」只能近似为改 gamma，或留到后处理层）。
- **依据来源**：
  - [Data Structures and Constants（libraw_output_params_t）](https://www.libraw.org/docs/API-datastruct-eng.html) — LibRaw LLC — 持续更新 — 权威 5 / 一手 5 / 时效 5 / 独立 5（综合 25）
  - [Saturation（user_sat 语义答复）](https://www.libraw.org/node/2271) — LibRaw 官方论坛，Alex Tutubalin — 2017-04-27 — 权威 5 / 一手 5（综合 23）
  - [Clipping of over-exposed image（-W / -U 自动提亮）](https://www.libraw.org/node/2267) — LibRaw 官方论坛 — 2017-04-05 — 权威 4 / 一手 5（综合 21）
  - [Usage Examples（-aexpo e p 参数说明）](https://www.libraw.org/book/export/html/35) — LibRaw LLC — 权威 5 / 一手 5（综合 25）
- **置信度**：高
- **反方证据**：暂无。这是源码级事实，不是观点。

### 发现 2：16bit 输出可用，后处理层应保留高位深

- **发现内容**：`dcraw_make_mem_image()` 返回的 `libraw_processed_image_t` 有 `bits`（8 或 16）与 `colors` 字段，`data` 需按 `uint16_t*` 重解释；内存不随 `LibRaw::recycle()` 释放，必须 `dcraw_clear_mem()`。当前项目只取 8bit RGB（`decodeToRGB` 返回 3 字节/像素），**用于预览没问题，但用于「调色后导出」会丢动态余量**（曝光/曲线在 8bit 上做容易断层、色带）。
- **依据来源**：
  - [Data Structures and Constants（libraw_processed_image_t）](https://www.libraw.org/docs/API-datastruct-eng.html) — 权威 5 / 一手 5（综合 25）
  - [A question about dcraw_make_mem_image](https://www.libraw.org/node/2263) — LibRaw 官方论坛 — 2017-03-21 — 权威 5 / 一手 5（综合 24）
- **置信度**：高
- **反方证据**：16bit 使内存翻倍（6000×4000×3×2 ≈ 144MB/张），中低端机可能直接 OOM → 全分辨率建议只走「解码 → 编码 JPEG → 释放」的流式路径，不做多张并行。

### 发现 3：VLM 输出调色参数已有先例，但效果依赖「任务被拆小 + 参数受限」

- **发现内容**：
  - **PhotoArtAgent**（2025）：把「直方图图像 + 照片」一起给 VLM，让它按 Lightroom 的 Light/Color/ColorMixer 三类产出结构化 JSON 参数，再执行；作者的取巧点是**把视觉判断转成直方图数值判断**，并用 function calling 保证格式。
  - **RetouchLLM**（2025-10）：不直接回归参数，而是让 LLM 生成由 7 种光度操作（曝光/对比度/饱和度/色温/高光/阴影/纹理）组成的**程序**，从而与分辨率解耦、可解释。
  - **MonetGPT**（Adobe Research UK, 2025）：明确指出「用输入/输出图对预测滤镜参数」是一条长期研究线（Bychkovsky 2011、Yan 2014 → 深度网络），并把它建模成需要强化学习/推理训练的难题——**说明这不是一个 prompt 就能解决的简单回归任务**。
  - **PixTalk**（ICCV 2025）：构建了「语言指令 → 光度操作」的数据集（含曝光/对比度/饱和度/色温/白平衡），说明指令级控制在研究上可行，但也说明需要专门数据与训练。
  - 共性结论：**可行的形态是「受限参数空间 + 结构化输出 + 可执行 DSL」**，而不是「模型直接写出一个配置文件就完事」。
- **依据来源**：
  - [PhotoArtAgent: Intelligent Photo Retouching with Language Model-Based Artist Agents](https://arxiv.org/html/2505.23130v1) — arXiv — 2025-05 — 权威 4 / 一手 5 / 时效 5 / 独立 5（综合 23）
  - [MonetGPT: Solving Puzzles Enhances MLLMs' Image Retouching Skills](https://arxiv.org/html/2505.06176v1) — Adobe Research UK / arXiv — 2025-05 — 权威 5 / 一手 5（综合 24）
  - [RetouchLLM: Training-free Code-based Image Retouching with Vision Language Models](https://arxiv.org/html/2510.08054v1) — arXiv — 2025-10 — 权威 4 / 一手 5 / 时效 5（综合 22）
  - [PixTalk (ICCV 2025)](https://openaccess.thecvf.com/content/ICCV2025/papers/Conde_PixTalk_Controlling_Photorealistic_Image_Processing_and_Editing_with_Language_ICCV_2025_paper.pdf) — ICCV/CVF — 2025 — 权威 5 / 一手 5（综合 24）
- **置信度**：中高（先例确凿；但各论文的定量效果不可直接迁移到本项目）
- **反方证据**：这些系统多为「有参考图/有用户反馈的迭代式修图」，单次开环预测的质量在论文中较少给出严格的用户研究数据。

### 发现 4：VLM 的「审美判断」本身不可靠——这是最大的产品风险

- **发现内容**：AesBench（2800 张专家评级图）测试多个 MLLM 的美学三分类（高/中/低），**最高准确率仅 52.86%**（Q-Instruct），GPT-4V 为 50.86%——接近三分类随机基线（33%）之上但不构成可用判断力。同时 SO-Bench（CVPR 2026）用 6.5K schema / 1.8K 图文对评测视觉结构化输出，发现开源与前沿闭源模型在「schema 合规 + 视觉 grounding」上仍有持续差距。
  → 对本项目的含义：**不要把「好不好看」交给模型判断**；让模型做它相对擅长的「描述性问题」（这张图偏暗吗？高光溢出在哪？色温偏冷吗？主体是什么？），再把这些**文本化的事实**映射到数值参数。这是 PhotoArtAgent 用直方图取巧的同一逻辑。
- **依据来源**：
  - [AesBench: An Expert Benchmark for MLLMs on Image Aesthetics Perception](https://ar5iv.labs.arxiv.org/html/2401.08276) — arXiv — 2024-01 — 权威 4 / 一手 5 / 时效 4（综合 21）
  - [SO-Bench: A Structural Output Evaluation of Multimodal LLM (CVPR 2026)](https://openaccess.thecvf.com/content/CVPR2026/html/Feng_SO-Bench_A_Structural_Output_Evaluation_of_Multimodal_LLM_CVPR_2026_paper.html) — CVPR — 2026 — 权威 5 / 一手 5 / 时效 5（综合 24）
- **置信度**：中高（基准本身可靠；「因此不能用于本项目」是推断）
- **反方证据**：AesBench 测的是「三分类美学」，不等于「判断曝光是否不足」——后者是更偏客观的任务，VLM 表现通常更好（暂无公开量化，需自测）。

### 发现 5：结构化输出已工程化，可以把「不可控」收敛成「可校验」

- **发现内容**：主流 API 均支持 JSON Schema 严格约束输出（OpenAI Structured Outputs `response_format: {type:"json_schema", strict:true}`，自 `gpt-4o-2024-08-06` 起；Gemini 有 `responseSchema`）。图像输入方面 OpenAI 支持 PNG/JPEG/WEBP/GIF，单请求最多 1500 张图，超限/过大图会被拒而不是自动缩放。工程上应：
  - 所有数值字段带 `minimum`/`maximum`/`multipleOf`，模型无法越界；
  - 枚举字段限定操作名；
  - **客户端再做一次 clamp 与物理约束校验**（如 `exp_shift` ∈ [0.25, 8]，提亮时 `exp_preser` 建议 > 0.3）；
  - 解析失败 → 重试 1 次 → 仍失败则退回本地算法（**绝不把模型输出直接当代码执行**，这是与 RetouchLLM 路线的关键区别）。
- **依据来源**：
  - [Structured model outputs](https://developers.openai.com/api/docs/guides/structured-outputs) — OpenAI — 持续更新 — 权威 5 / 一手 5（综合 25）
  - [Images and vision](https://developers.openai.com/api/docs/guides/images-vision) — OpenAI — 持续更新 — 权威 5 / 一手 5（综合 25）
- **置信度**：高
- **反方证据**：SO-Bench 表明即使有 schema，语义正确率仍可能偏低——格式合规 ≠ 参数正确。

### 发现 6：成本可忽略，延迟与合规才是决策变量

- **发现内容**：
  - **成本**：Gemini 官方价目表（2026-10 访问）中图像输入约 560 tokens/图，按 $1~$2 / 1M input tokens 计，**单次调参 < $0.005**；即使每张照片重试 3 次、每天 100 张，月成本也在 $1 量级。
  - **延迟**：云端 VLM 一次往返通常 2~10 秒（含上传 1~2MB 预览图），交互上必须做成「后台建议 + 完成后可选应用」，不能阻塞预览。
  - **合规**（中国境内用户，PIPL）：照片可能包含人脸等**敏感个人信息**（第 28 条），处理需「特定目的 + 充分必要 + 严格保护」并取得**单独同意**（第 29 条）；向境外提供（调用海外 API）还需满足第 38 条（安全评估 / 认证 / 标准合同）并履行第 39 条的告知与单独同意。2025-03 的《人脸识别技术应用安全管理办法》更直接要求「人脸信息应存储于人脸识别设备内，不得通过互联网对外传输」（除法律另有规定或单独同意）。
  - **端侧替代**：Gemma 3n（LiteRT/Google AI Edge，支持图文输入、INT4、CPU/GPU/NPU）与 Qwen2.5-VL-3B / Qwen3-VL-2B（MNN / llama.cpp）均已有 Android 端跑通的公开路径，代价是**数 GB 模型下载 + 数 GB 内存 + 首 token 秒级延迟**。
- **依据来源**：
  - [Gemini Developer API pricing](https://ai.google.dev/gemini-api/docs/pricing) — Google — 2026-10 访问 — 权威 5 / 一手 5 / 时效 5（综合 25）
  - [中华人民共和国个人信息保护法](https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm) — 中央网信办 — 2021-08-20 — 权威 5 / 一手 5（综合 25）
  - [人脸识别技术应用安全管理办法](https://www.gov.cn/zhengce/zhengceku/202503/content_7016075.htm) — 中国政府网 — 2025-03-13 — 权威 5 / 一手 5 / 时效 5（综合 25）
  - [Gemma 3n Android VLM 指南](https://aashidutt.github.io/gemma3n-android-guide) — 社区教程 — 2025-05-26 — 权威 3 / 一手 3 / 时效 4（综合 16）
  - [Run an optimized VLM on Android (Arm)](https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/ai-portal-mobile-vision-language/) — Arm — 权威 4 / 一手 4（综合 21）
- **置信度**：成本高（官方价目）；合规高（法条原文）；端侧可行性中（教程可跑，但机型/速度差异大）
- **反方证据**：把「用户自己的照片上传给模型」在个人工具、用户主动点击的前提下，风险显著低于运营型服务；是否构成「个人信息处理者」需法律判断，不能一概而论。

### 发现 7：现有代码是「参数硬编码」，需要先做参数化改造

- **发现内容**：`libraw/src/main/cpp` 的 `decodeToRGB` 里，`output_bps=8`、`user_qual=3`、`med_passes=1`、`highlight=2`、`no_auto_bright=1`、`bright=1.0` 全部写死，且 JNI 只接受 `path`。要做「AI 调参」，必须：
  1. JNI 增加参数入参（建议传一段 JSON 或固定顺序的 float 数组，避免 JNI 结构体版本地狱）；
  2. 增加 `decodePreview(path, maxEdge, params)`（复用 `half_size` 或降采样）与 `decodeFull(path, params)` 两个入口；
  3. 增加 `dcraw_clear_mem` 释放（当前用 RAII `ImageGuard` 已处理，保持）。
  Java 层 `RawWrapper` 增加 `data class RawTuningParams(...)` 与对应重载，保持现有 `decodeToRGB(path)` 行为不变（默认参数 = 当前硬编码值），确保**向后兼容**。
- **依据来源**：代码事实 `libraw/src/main/cpp`、`RawWrapper.kt`
- **置信度**：高
- **反方证据**：无。

## 参数映射：把模型输出落到可执行的两层

| 用户语言 | 感知域意图 | RAW 层（LibRaw） | 后处理层（解码后） |
|---|---|---|---|
| 太暗 / 太亮 | 曝光 | `exp_correc=1`，`exp_shift` 0.5~2.0（1 档≈√2） | 必要时再叠加线性增益 |
| 高光死白 | 高光恢复 | `highlight=2`（blend），提亮时 `exp_preser` 0.5~1.0 | 高光滚降曲线 |
| 暗部死黑 | 阴影 | 无直接参数（只能靠 `exp_shift`/`bright`） | 阴影提亮曲线（局部/全局） |
| 灰、平 | 对比度 | 只能用 `gamm` 近似 | **S 曲线 / LUT（推荐）** |
| 颜色发闷 | 饱和度/鲜艳度 | **无** | HSL 饱和度、鲜艳度（保护肤色） |
| 偏黄/偏蓝 | 白平衡 | `user_mul[4]`、`use_camera_wb` | 色温/色调矩阵 |
| 噪点多 | 降噪 | `threshold`、`med_passes`、`fbdd_noiserd`（牺牲细节） | 频域/双边降噪 |
| 暗角 | 镜头校正 | `aber`、暗角校正需镜头 profile | 暗角补偿 |

> 结论：**AI 输出的 JSON 必须显式分 `raw` 与 `tone` 两组**，否则「对比度」「饱和度」无处安放。

建议的 schema（精简版，可直接用于 Structured Outputs）：

```json
{
  "raw": {
    "expShift": 1.26,        // 0.25 ~ 8.0
    "expPreserve": 0.6,      // 0.0 ~ 1.0
    "bright": 1.05,          // 0.5 ~ 3.0
    "highlightMode": 2,      // 0 ~ 3
    "noAutoBright": true,
    "gamma": 0.4545,         // 1/2.2 ~ 1/2.6
    "wbMul": [1.9, 1.0, 1.5, 1.0]
  },
  "tone": {
    "contrast": 0.12,        // -0.5 ~ 0.5
    "saturation": 0.05,      // -0.5 ~ 0.5
    "vibrance": 0.08,
    "highlights": -0.2,
    "shadows": 0.15,
    "temperature": 200,      // K 偏移
    "tint": 0.0
  },
  "reason": "整体欠曝约 0.3EV，高光云层有细节可救；肤色偏黄，建议轻微降温"
}
```

## 烘焙（导出）管线设计

> 需求澄清：不做「AI 直接改图」，而是 **AI 产出 profile → 用 profile 重新烘焙出成片**（Lightroom「RAW + XMP，导出时渲染」的模型）。

### 五条铁律

1. **原图永不覆盖**：烘焙产物一律是新文件（`PhotoSync/` 目录或 MediaStore），源文件与 profile 保持不动。
2. **烘焙只从原图出发**：第二次烘焙必须仍以原图为输入，**不能拿上次的烘焙产物再烘**——否则参数会叠加、画质代际损失，这是此类功能最常见的 bug。
3. **确定性**：同一（原图 + profile + `pipelineVersion`）必须渲染出同一批像素。渲染器改了就升 `pipelineVersion`，旧 profile 仍按旧版本解释。
4. **失败不碰照片**：profile 解析失败/参数越界 → 该张标记失败、跳过，源文件与已有成片都不动。
5. **可再编辑**：profile 是可读 JSON（sidecar），能手动改、能换模型重生成、能「重置为原图」。

### 参数 → 渲染的两段式

```
原图 RAW ──(LibRaw: raw 组参数)──▶ 线性/8bit RGB ──(tone 组参数)──▶ JPEG/HEIF ──▶ 新文件
原图 JPEG ────────────────────────▶ 解码一次 ──────(tone 组参数)──▶ JPEG ────────▶ 新文件
```

- **RAW 段**只能改解码参数（`exp_shift`/`exp_preser`/`bright`/`highlight`/`gamm`/`user_mul`/降噪），改一次=重新 `dcraw_process`。
- **tone 段**（对比度/饱和度/鲜艳度/阴影/高光/色温）在解码后的位图上做，与源格式无关，RAW 与 JPEG 共用同一段代码。
- 预览用同一套 tone 数学（缩略图 + GPU shader），导出用 CPU 实现，两者需**黄金图对比**（同参数下直方图差 < 1%，见 P1 验收）。

### JPEG 源的特殊处理

- JPEG 不是 RAW：解码→再编码会损失一代。烘焙时用 `quality ≥ 95` + `subsampling = 4:4:4`，或中间产物用 PNG/HEIF 无损/高位深。
- 若 tone 参数接近中性（|Δ| 小于阈值），直接跳过烘焙、复用原图，避免「什么都没改却掉了画质」。

### EXIF 与方向（易踩坑）

- LibRaw 默认 `user_flip = -1`，即**已按相机方向转正**；烘焙输出的 EXIF `Orientation` 必须写 `1`（Top-Left），否则相册里会被二次旋转。
- 需要拷贝：拍摄时间、相机/镜头、ISO/光圈/快门、GPS（按用户意愿，默认关闭 GPS 以保护隐私）；需要写入：`Software = PhotoSync/<version>`、以及 profile 摘要，便于日后追溯。
- 色彩：`output_color = 1`（sRGB）→ JPEG 按 sRGB 编码，不要在未做转换时直接贴 P3 profile。

### 批量烘焙与资源

- 用 `WorkManager` 的 `OneTimeWorkRequest`（可加 `setExpedited` 或前台通知），支持进度、取消、失败重试；**RAW 并发固定为 1**（内存峰值单张可达百 MB 级），JPEG 可 2。
- 内存：6000×4000×3×2B ≈ 144MB（16bit）。中低端机建议 `output_bps = 8` 直接出 JPEG；只有强 tone 曲线或 10bit HEIF 输出才上 16bit，并改成「解码→编码→立刻释放」不并行。
- 输出尺寸做成参数（长边 2048 / 4096 / 原始），默认原始；批量时可先出 2048 版给预览确认。

### 幂等与缓存键

烘焙前先算 key：`hash(源文件内容 + profile + pipelineVersion + 输出规格)`。命中已有成片就直接复用，避免重复解码。DB 里存 `baked_path`、`baked_key`、`baked_at`。

### 调色用什么技术（端上实现选型）

| 方案 | 能力 | 性能（24MP 量级） | 后台可用 | 结论 |
|---|---|---|---|---|
| `ColorMatrixColorFilter` / `Canvas` 绘制 | 只能线性矩阵（亮度/饱和度/色温可近似），不能曲线 | 快 | 是 | 不够用 |
| `RenderScript` | 任意 kernel | 快 | 是 | **已废弃（API 31）**，不选 |
| `RuntimeShader` / AGSL（API 33+） | 任意 shader，适合实时预览 | 极快 | 需 `HardwareRenderer`+`RenderNode` 离屏，兼容性与内存皆差 | **只用于预览** |
| OpenCV 4.x | LUT、矩阵、色彩空间、降噪，CPU 多线程 | 0.5~2s/张 | 是 | **仓库已移除该依赖**（`cdf805d`，连同 `ImageDenoiseUtils`），不推荐为烘焙单独恢复 |
| **自写 C++/JNI（`:libraw` 模块内）** | 最快、可 float 精度、可与 LibRaw 缓冲零拷贝 | 0.2~1s/张 | 是 | **推荐（烘焙主路径）** |
| Android `Bitmap` + 手写 LUT（Kotlin） | 简单、零依赖 | 1~3s/张 | 是 | 原型 / 兜底实现 |

**核心思路：把整条调色链合并成「一次遍历」**，而不是串联多个滤镜（串联会对 8bit 反复量化，出现色带与暗部断层）：

- 曝光 → 线性域乘 `2^EV`；
- 对比度 / 高光 / 阴影 → 生成 **1D LUT（每通道 1024 项）**，用单调三次插值（保证单调、不反转）；
- 饱和度 / 鲜艳度 → 亮度保持模型 `out = Y + (in − Y) × (1 + s)`，`Y = 0.2126R + 0.7152G + 0.0722B`；鲜艳度按原图色度加权，避免灰区被推色；
- 色温 / 色调 → RGB 增益矩阵；
- 可选「风格」→ **3D LUT（33³）+ 三线性插值**，可与 `.cube` 预设互通；
- 输出前再做 sRGB 编码。

实现上，1D 曲线在 C++ 里就是一张 256×3 的 `uint8_t` 表（或 float 表），每个像素查三次；矩阵与饱和度都是一次乘加。放在 `:libraw/src/main/cpp` 里可以直接吃 `dcraw_make_mem_image` 的缓冲，省掉一次「字节数组 → Bitmap → Mat」的拷贝，也避免再引入新依赖。

**通道顺序坑**：LibRaw 返回**交错 RGB**，`Bitmap` 的 `ARGB_8888` 是 **BGRA** 字节序（`setPixels` 用 `Int` 时是 `0xAARRGGBB`），第三方库又可能是 BGR。入口处统一成一种内部布局（建议统一 RGB，只在写 `Bitmap` 时转换），否则必出色偏。

### 实现步骤与对应 API

| # | 步骤 | 具体做法 |
|---|---|---|
| 1 | 读 profile | `org.json` / `kotlinx.serialization` 解析；schema 校验 + clamp |
| 2 | 幂等检查 | `bakeKey = sha256(源文件 + profile + pipelineVersion + 输出规格)`，命中则跳过 |
| 3 | 解码 | RAW：`RawWrapper.decodeToRGB(path, params)`（需先做 JNI 参数化）；JPEG：`BitmapFactory`（`ARGB_8888`）或 `Imgcodecs.imread` |
| 4 | 调色 | 曲线 LUT + 饱和度矩阵 + 可选 3D LUT，**原地做、不复制整图**（native 用 `uint8_t*`，Kotlin 用 `IntArray` 像素缓冲） |
| 5 | 编码 | `Bitmap.compress(JPEG, 95)`；要 4:4:4 需自备 libjpeg-turbo（平台编码器抽样固定） |
| 6 | 写 EXIF | `androidx.exifinterface:exifinterface`：`Orientation=1`，拷贝 `DateTimeOriginal`/`Make`/`Model`/`ISO`/曝光参数，写入 `Software` |
| 7 | 落盘 | 复用现有 `MainActivity.saveToGallery`（`IS_PENDING=1` → 写流 → `IS_PENDING=0`；失败要 `resolver.delete(uri)`） |
| 8 | 记库 | 更新 `baked_path` / `bake_key` / `baked_at`，UI 显示「已调色」角标 |

**批量**：`WorkManager`（`OneTimeWorkRequest` + `setExpedited` 或前台通知），RAW 并发 1；失败写 `Result.retry()` 并记录原因，不阻塞其他照片。

### 验收标准

- 同一 profile 连续烘焙两次，输出字节级一致（或 PSNR > 50dB，取决于编码器）。
- 对同一张 24MP RAW：烘焙期间峰值内存 < 400MB、无 OOM；耗时记录进日志。
- 烘焙后 `Orientation=1`，在系统相册中方向正确、无重影。
- 删除成片不影响原图；「重置」后原图完好。

## 对比分析

### 方案对比（能力来源）

| 维度 | A. 纯本地算法基线 | B. 云端视觉大模型（可选增强） | C. 端侧小 VLM |
|---|---|---|---|
| 能力 | 直方图自动曝光/白平衡/曲线，稳定但「千人一面」 | 能理解场景（人像/风光/夜景/逆光），给出语义化建议 | 介于两者之间，隐私最好 |
| 隐私 | 无上传 | 需上传预览图（PIPL 敏感信息风险） | 全离线 |
| 延迟 | < 100ms | 2~10s（网络） | 2~20s（模型加载另计） |
| 单次成本 | 0 | ≈ $0.001~0.005 | 0（但包体/内存代价） |
| 可靠性 | 高（确定性） | 中低（审美判断弱、结构化输出不稳） | 中低 |
| 工程复杂度 | 低 | 中（API、鉴权、重试、降级、合规弹窗） | 高（模型分发、内存、机型适配） |
| 离线可用 | 是 | 否 | 是 |
| 建议 | **先做，作为兜底** | **P2，默认关闭** | P3，可选实验 |

### 渲染架构对比

| 维度 | 每次改参重新全量解码 | 缩略图解码 + GPU 实时预览 + 导出时全量重解码（推荐） |
|---|---|---|
| 预览响应 | 秒级~十秒级，不可用 | 16ms 级（滑杆跟手） |
| 内存 | 全分辨率反复分配，易 OOM | 预览仅 1200px |
| 实现复杂度 | 低 | 中（需一套 shader/LUT） |
| 一致性 | 天然一致 | 需保证预览与导出用同一曲线（否则「预览好看、导出翻车」） |

## 反方观点与分歧

- **「AI 直接输出成片」 vs 「AI 只出参数」**：生成式图像编辑（InstructPix2Pix、Nano Banana 类）能直接改像素，效果直观；但对 RAW 工作流是破坏性的——它不是「可回退的调参」，而是重新生成内容，会改变真实细节。本报告坚持「参数化、可回退、可再编辑」，与 PhotoArtAgent/RetouchLLM 一致。
- **「让模型写代码」**：RetouchLLM 证明可行且可解释；但在手机端执行模型生成的代码存在安全与确定性风险，**不建议**。用固定 schema 更好。
- **「LibRaw 能调对比度」**：常见误解。社区里把 `gamm` 当对比度用，效果是「整体变灰/变硬」，不是 S 曲线。此处与多数教程说法相左，以官方参数表为准。
- **「端侧模型可替代云 API」**：模型确实能跑（Gemma 3n / Qwen-VL 2B-3B），但 2~4GB 下载与内存占用，对一个「导入照片」工具来说代价过高；更适合作为「隐私模式」的长期选项。
- **未解决**：VLM 对「曝光是否不足」这类客观判断的实际准确率没有公开基准，必须自建小样本评测。

## 风险与不确定性

- **未验证**：本项目 JNI 增加参数后，全分辨率 `dcraw_process` 在典型手机上的耗时（估计 0.5~3s/张，**未知**）；需实测再决定是否支持「批量一键」。测试方案：选 3 台机型（低/中/高），对 5 种 RAW 各跑 10 次，记录 `open+unpack+process+encode` 分段时间与峰值内存。
- **未验证**：模型给出的参数落在「物理合理但视觉难看」区间的比例（需要人评）。
- **需要进一步验证**：`half_size` 解码与全解码的构图/色彩是否足以让模型判断（降采样会掩盖噪点与细节问题）。
- **元评审结论**：
  - 剩余未知：手机端 RAW 解码性能、VLM 对客观曝光缺陷的判断准确率、用户对「AI 建议」的接受度。
  - 最弱证据：端侧 VLM 在本项目场景下的可用性（只有社区教程，无本项目实测）。
  - 可能错误的假设：「用户愿意把照片上传到云端」。对摄影用户，这可能是**一票否决**。
  - 遗漏角度：**批处理一致性**（同一场景 30 张应当同参数，逐张让 AI 判断会产生跳变）；**AI 建议的可解释性**（为什么这么调，需展示 reason）；**离线/弱网**；**成本上限与配额**；**模型版本漂移**（同图不同版本给不同参数，需记录 `modelVersion`）。
- **最坏情况分析**：
  - 失败方式 1：AI 参数在 30% 的照片上产生更差结果，用户不再点「AI 建议」，功能沦为装饰。
  - 失败方式 2：上传含人脸的私照引发隐私投诉/合规问题。
  - 失败方式 3：预览与导出不一致（预览用 shader、导出用 LibRaw），用户认为「导出坏了」。
  - 回滚可行性：功能默认关闭 + 参数不写死原图（只存 sidecar JSON），**完全可回滚**。
  - 损失是否可控：可控，前提是**永不覆盖原文件**（导出为新文件）。

## 建议

按优先级：

1. **P0 — 参数化 JNI（不改行为）**：`decodeToRGB(path, paramsJson)` + `decodePreview(path, maxEdge, params)`；默认参数等于现值；加 16bit 变体。验收：现有 `RawLibIntegrationTest` 全绿，输出逐像素与改造前一致。
2. **P0 — 本地算法基线**：基于缩略图算直方图（1%/99% 分位做黑白点、均值做曝光目标、灰世界做白平衡），产出与 AI **同一套 JSON schema**。这一步让「AI 开关」变成纯可选，且是 AI 失败时的兜底与对照。
3. **P1 — 两层渲染管线**：预览 = 8bit 缩略图 + GPU shader（`RuntimeShader`/AGSL，API 33+；低版本降级到 CPU LUT 或 `ColorMatrix`）；导出 = 全分辨率 LibRaw 解码（`raw` 组参数）+ 后处理（`tone` 组）+ JPEG 编码。验收：同一参数下预览与导出的直方图差 < 1%。
4. **P1 — sidecar 存储**：`photos` 表加 `tune_json TEXT`、`tune_source TEXT`（`manual`/`local_auto`/`ai`）、`model_version TEXT`、`tuned_at INTEGER`。**永不修改原文件**。验收：重开 App 参数仍在、可一键还原。
5. **P2 — AI 建议（默认关闭）**：
   - 上传：最长边 1024~1536 的 JPEG（含可选直方图小图）；
   - 输出：严格 schema（上节），客户端 clamp；
   - 交互：「获取建议」→ 展示理由 → 「应用」/「忽略」；
   - 隐私：首次启用时明确告知「将上传压缩后的预览图到 X 服务」，单独勾选；提供「仅本地」开关；
   - 失败：超时/解析失败/参数越界 → 静默回退本地基线，不弹错误。
6. **P2 — 评估集**：自建 30~50 张（人像/风光/夜景/逆光/高 ISO 各若干），三人盲评 A/B（原图 / 本地基线 / AI 建议），记录「接受率」「更差率」「回退率」；没有这组数据就不要上线。
7. **P3 — 端侧模型**：仅在「隐私模式」需求明确时评估 Gemma 3n / Qwen3-VL-2B，先做可行性 demo 再谈集成。

**明确不建议**：
- 让模型直接生成代码在端上执行；
- 逐张独立让 AI 判断同一场景的连拍（会跳变，应按场景/时间聚类后复用参数）；
- 把 `tone` 参数硬塞进 LibRaw（表达不了，会做出「假对比度」）。

## 参考来源

| 标题 | URL | 发布者 | 发布时间 | 类型 |
|---|---|---|---|---|
| Data Structures and Constants（libraw_output_params_t） | https://www.libraw.org/docs/API-datastruct-eng.html | LibRaw LLC | 持续更新 | 官方 |
| LibRaw C++ API | https://www.libraw.org/docs/API-CXX.html | LibRaw LLC | 持续更新 | 官方 |
| user_sat 语义（官方答复） | https://www.libraw.org/node/2271 | LibRaw 论坛 | 2017-04-27 | 官方 |
| dcraw_make_mem_image 16bit（官方答复） | https://www.libraw.org/node/2263 | LibRaw 论坛 | 2017-03-21 | 官方 |
| Clipping of over-exposed image（auto_bright_thr） | https://www.libraw.org/node/2267 | LibRaw 论坛 | 2017-04-05 | 官方 |
| PhotoArtAgent | https://arxiv.org/html/2505.23130v1 | arXiv | 2025-05 | 学术 |
| MonetGPT | https://arxiv.org/html/2505.06176v1 | Adobe Research UK / arXiv | 2025-05 | 学术（厂商） |
| RetouchLLM | https://arxiv.org/html/2510.08054v1 | arXiv | 2025-10 | 学术 |
| PixTalk (ICCV 2025) | https://openaccess.thecvf.com/content/ICCV2025/papers/Conde_PixTalk_Controlling_Photorealistic_Image_Processing_and_Editing_with_Language_ICCV_2025_paper.pdf | CVF | 2025 | 学术 |
| AesBench | https://ar5iv.labs.arxiv.org/html/2401.08276 | arXiv | 2024-01 | 学术 |
| SO-Bench (CVPR 2026) | https://openaccess.thecvf.com/content/CVPR2026/html/Feng_SO-Bench_A_Structural_Output_Evaluation_of_Multimodal_LLM_CVPR_2026_paper.html | CVF | 2026 | 学术 |
| Structured model outputs | https://developers.openai.com/api/docs/guides/structured-outputs | OpenAI | 持续更新 | 官方 |
| Images and vision | https://developers.openai.com/api/docs/guides/images-vision | OpenAI | 持续更新 | 官方 |
| Gemini Developer API pricing | https://ai.google.dev/gemini-api/docs/pricing | Google | 2026-10 访问 | 官方 |
| 中华人民共和国个人信息保护法 | https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm | 中央网信办 | 2021-08-20 | 法规 |
| 人脸识别技术应用安全管理办法 | https://www.gov.cn/zhengce/zhengceku/202503/content_7016075.htm | 中国政府网 | 2025-03-13 | 法规 |
| Run an optimized VLM on Android | https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/ai-portal-mobile-vision-language/ | Arm | 2025 | 厂商教程 |
| Gemma 3n Android 指南 | https://aashidutt.github.io/gemma3n-android-guide | 社区 | 2025-05-26 | 社区 |
| 项目代码：libraw/cpp、RawWrapper.kt | 本地仓库 | 项目自身 | 2026-10-27 | 一手 |
