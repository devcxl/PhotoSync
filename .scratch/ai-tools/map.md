# PhotoSync 端侧与可选云端 AI 工具（P0–P4）路线规划地图

Labels: wayfinder:map

## Destination

锁定 PhotoSync 离线优先 AI 工具套件（P0 统一端侧 AI 底座、P1 主体蒙版一模多用、P2 涂抹智能消除、P3 换天空、P4 默认关闭的纯 BYOK 云端生成式能力）的全部前置技术事实、领域边界、渲染/导出挂载点、安全隐私契约与交互原型，产出一份零待决事项、可直接按 P0→P4 分阶段交接实施的完整技术规范（`.scratch/ai-tools/spec.md`）。

## Notes

- **领域**：Android 相机伴侣与非破坏性图像编辑器（`:app` + `:editor` + `:libraw`；`minSdk 24` / `compileSdk 36`，GLES 3.0 离屏 Pass A–D + `BakeExporter` 列带分块导出，XML + ViewBinding，无 Jetpack Compose）。
- **必读材料与 Skill**：
  - 既有编辑器基线规范与 ADR：`.scratch/image-editor/spec.md`、`docs/adr/0007-libjpeg-turbo-and-column-band-bake.md`、`docs/adr/0008-mediapipe-portrait-mls-and-dual-skin-mask.md`、`docs/adr/0009-tethered-darkroom-presets-and-batch-bake.md`
  - 领域词汇表：`CONTEXT.md`
  - 现有模型下载器与滤波基建：`editor/src/main/java/cn/devcxl/photosync/editor/vision/ModelDownloadManager.kt`、`editor/src/main/java/cn/devcxl/photosync/editor/domain/GuidedFilter.kt`
  - 推进工单时按类型查阅 Skill：`grilling` + `domain-modeling`（默认决策工单）、`research`（事实调研）、`prototype` + `frontend-design`（交互原型）、`security-audit`（P4 密钥与隐私边界）。
- **固定偏好与硬约束**：
  - **纯规划模式**：本轮地图只做决策、事实调研与原型验证，收敛为 `.scratch/ai-tools/spec.md` 后再交接实施，不在推进工单期间编写生产代码。
  - **严守商用开源合规**：PhotoSync 以 MIT 许可发布，所有新增端侧模型权重与推理运行时必须采用可商用开源许可（如 MIT / Apache-2.0），明确排除任何 NC（非商用）许可模型（如 BRIA RMBG-1.4 / RMBG-2.0）。
  - **模型 100% 按需下载**：新增 AI 模型绝不内置进 APK/AAB，统一经扩展后的模型下载器（多镜像回退 + SHA-256 强校验 + 本地导入 + 空间管理）按需获取。
  - **纯 BYOK 零后端**：P4 云端生成式能力默认关闭、由用户主动开启，仅支持用户自带 API Key 或自建端点（如 ComfyUI），PhotoSync 不搭建任何官方推理中转后端。
  - **非破坏性原则**：原始 RAW/JPEG 永不改写；AI 产物作为外置资产层纳入 `EditGraph` 生命周期并支持 Before / After 原图对比。
  - **用名字引用**：叙述与索引中始终用 `[工单标题](链接)` 引用工单，绝不使用裸编号。

## Decisions so far

<!-- 索引：每个已关闭工单一行，足以判断相关性，然后顺着链接放大看工单保存的细节 -->

- [ONNX Runtime Android 运行时体积、硬件加速与通用抠图/发丝模型事实调研](wayfinder/01-onnx-runtime-mobile-and-matting-models-research.md): 实测 `onnxruntime-android:1.32.0` 使 arm64 APK 暴涨 +34.79 MiB 且自动带微软遥测 provider（须 manifest 移除），官方 minimal AAR 停更于 1.18.0（+4.5 MB）；BiRefNet-lite 1024² 峰值内存 6.8–12.0 GB 在手机端不可行；ISNet 因 DIS5K 禁商用/AGPL 排除；MODNet（Apache-2.0，25.9 MB，0.137 s / 334 MB）可行但仅限人像；24–60MP 精修需将 `GuidedFilter` 纳入 bake band（单 band 63 MB）并配 Fast Multi-Level Foreground Estimation 去色溢（详报见分支 `research/ort-matting` `547eb20`）。
- [端侧智能消除（MI-GAN / LaMa-Dilated）与天空分割模型事实调研](wayfinder/02-on-device-inpainting-and-sky-models-research.md): 确认 MI-GAN 为唯一双 MIT 端侧消除方案（28.1 MB，FP16 视觉无损但 INT8 QDQ 不可用，生成器需与裁切融合拆开）；排除 CelebA-HQ 人脸权重且 183.6 MB 的 LaMa-Dilated 与 NVIDIA 非商用的 SegFormer-B0；发现 SkySeg 社区 176 MB ONNX 版权存疑（官方仅发 2.26 MB ncnn）；`GuidedFilter.smooth` 24MP 需分块防 1.1 GB 峰值内存（详报见分支 `research/inpainting-sky` `d3013fd`）。
- [BYOK 云端生成式图像编辑协议、ComfyUI 接入与客户端安全事实调研](wayfinder/03-byok-cloud-edit-protocols-and-security-research.md): 锁定三类协议最小抽象 `CloudEditProvider`（OpenAI `POST /v1/images/edits` 禁硬编码模型名；Gemini Interactions API 无二值 mask 仅 `SOFT` 且支持 `store:false`；ComfyUI 走轮询并防 `/upload/mask` 静默 200）；`security-crypto` 已废弃，改为直连 `AndroidKeyStore` AES-256-GCM + `noBackupFilesDir`；外发必须经 `Bitmap::compress` 重编码剥离 EXIF 与嵌入式缩略图（详报见分支 `research/byok-cloud` `551bcaa`）。
- [端侧 AI 底座、多镜像按需分发与低内存预算规范](wayfinder/04-ai-runtime-distribution-and-memory-budget-spec.md): 锁定 `onnxruntime-android:1.22.0`（+18.3 MB、CPU/XNNPACK、manifest 移除遥测、保留 MediaPipe 专职人脸关键点）；端侧 v1 模型矩阵定为 P1 MODNet（25.9 MB 人像发丝 + 分块 GuidedFilter/FMGE）、P2 MI-GAN-512 生成器-only（28.1 MB，禁 INT8 QDQ）、P3 零模型经典天空分割 + GuidedFilter，并预留通用主体/天空模型热插拔插槽；扩展 `ModelDownloadManager` 支持多镜像回退与 SAF 本地 `.onnx` 导入；确立单活 `OrtSession`、30 s 闲置释放与严禁预热的内存红线。
- [非破坏性 AI 资产层、EditGraph 扩展与渲染/导出管线挂载点规范](wayfinder/05-non-destructive-ai-assets-and-editgraph-v2-spec.md): 升级 `EditGraph` 为 Schema v2（保持 Room v4 表结构不变），确立 `SubjectMask` / `InpaintPatch` / `SkyLayer` / `CloudEditLayer` 内容寻址外置存储与离线 GC；锁定 Pass A–D 精确挂载顺序（消除在 Pre-Pass A、云端层在 Post-Pass A/Pre-Pass B 保留后续调色、前后景调色与换天协调在 Pass B、背景虚化在 Pass C 末、天空/去背与分块 GuidedFilter/FMGE 在 Pass D 且位于 LUT/Grain/Vignette 之前）；`SubjectOps`/`SkyOps` 支持进预设与 `LiveTetherPreset` 串行自动套用（≤4GB 自动降级），像素资产绝不跨图复制；新增零原生依赖流式 `PngStripSink` 支持 24–60MP 透明 PNG 导出。
- [AI 工具面板交互、涂抹消除画笔与模型下载/管理原型](wayfinder/06-ai-tools-editor-ui-and-interaction-prototype.md): 交付可交互原型 [`prototype-ai-tools-interaction.html`](prototype-ai-tools-interaction.html)；锁定底栏扩为 7 Tab（新增「AI」收纳主体/消除/天空，P4 关闭时隐藏云端）、涂抹消除采用「多笔涂抹+橡皮修边 → 点击确认消除」与 256 px 超限提示、双指绝对优先让路、底栏顶部非阻塞可取消推理进度条、双镜像失败切 SAF 本地 `.onnx` 导入，以及设置页统一 AI 模型管理。
- [P4 云端生成式能力（BYOK / ComfyUI）接口契约、安全存储与隐私边界规范](wayfinder/07-byok-cloud-provider-contract-and-privacy-boundary.md): 锁定零重依赖 `CloudEditProvider` 抽象与首批 3 类适配器（OpenAI-compatible 禁硬编码模型名、Gemini Interactions 标 `SOFT` 掩码且默认 `store:false`、ComfyUI 纯 HTTP 轮询并防 `/upload/mask` 静默 200）；凭据直连 `AndroidKeyStore` AES-256-GCM 存 `noBackupFilesDir`；公网强制 HTTPS、仅对经应用层私有 IP 校验的局域网 ComfyUI 放开显式明文 HTTP 并禁跨源重定向；外发强制 `Bitmap::compress` 重编码剥离 EXIF/缩略图、超时不自动重试防重复扣费，结果回写为 Post-Pass A `CloudEditLayer`。
- [P3 换天空光影融合、前景色彩协调与天空素材分发规范](wayfinder/08-sky-replacement-relighting-and-asset-pack-spec.md): 锁定零模型经典天空分割 + 分块 `GuidedFilter`（r=8, eps=1e-3）及 `SkySegmenter` 热插拔插槽；Pre-pass 一次性统计原/新天空 OKLab 均值差驱动 Pass B 前景受光协调（防分块拼缝），Pass D（位于 LUT/Grain/Vignette 之前）提供地平线偏移/柔化与边缘去白边；采用 0 KB 程序化 4 组大气基准 + 用户 SAF 自选天空（内容寻址 4096px）+ 可选按需 CC0 高清云彩包。
- [全分辨率烘焙与端侧 AI 降级矩阵、金标验收基线及最终技术规范总装](wayfinder/09-degradation-matrix-golden-tests-and-spec-assembly.md): 确立 ≤4 GB / >4 GB 内存档与 Tier 1–3 GPU 安全降级矩阵、P0→P4 五波次交付顺序（含 P0 真机延迟/RSS 与 MI-GAN 生成器导出准入门禁）以及 JVM + 真机隐私/内存/导出金标测试集，并完成最终技术规范总装 [`.scratch/ai-tools/spec.md`](spec.md)。

## Not yet specified

<!-- 在范围内但暂时还无法成工单的迷雾；随前沿推进而毕业 -->
<!-- 所有迷雾区块均已毕业为正式工单（08, 09） -->

## Out of scope

<!-- 被判定在目的地之外的工作；关闭，永不毕业 -->

- **非商用（NC）许可模型权重（如 BRIA RMBG-1.4 / RMBG-2.0、SegFormer-B0 ADE20K、ISNet/DIS5K、版权存疑社区 SkySeg 176MB）**：与 MIT 开源商用及应用商店分发合规要求直接冲突，永不纳入路线。
- **用 ONNX Runtime 替换现有 MediaPipe 人脸关键点管线**：近期保持 MediaPipe 专职人脸关键点与离线美肤兜底、ORT 专职新增按需视觉模型，避免高风险重写。
- **官方托管云端推理后端、账号体系与计费/配额系统**：P4 严守纯 BYOK 与零服务端原则，不在本产品定位之内。
- **将新增 AI 模型内置打包进 APK/AAB**：控制安装包体积与双渠道（GitHub APK / Google Play AAB）分发成本，所有新增模型一律按需下载。
- **在地图阶段直接落地生产代码**：本地图专注于消除全部设计迷雾并输出 `.scratch/ai-tools/spec.md`，实施工作在交接后按 P0→P4 分波次展开。
