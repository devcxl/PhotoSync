# 端侧 AI 底座、多镜像按需分发与低内存预算规范

Type: grilling
Status: resolved
Assignee: devcxl/wayfinder-session
Blocked by:
- [ONNX Runtime Android 运行时体积、硬件加速与通用抠图/发丝模型事实调研](01-onnx-runtime-mobile-and-matting-models-research.md)
- [端侧智能消除（MI-GAN / LaMa-Dilated）与天空分割模型事实调研](02-on-device-inpainting-and-sky-models-research.md)

## Question

基于前沿两项事实调研的实测数据，锁定 P0 端侧 AI 基础设施的全部关键架构决策：

1. **推理运行时与模块归属**：确认 ONNX Runtime Android 的引入形态（Full vs Minimal/Custom Ops）、Gradle/R8/ABI 裁剪规则（沿用现有排除 x86/x86_64 与 `debugSymbolLevel = "none"` 护栏），以及执行提供者降级策略（CPU/XNNPACK 默认 vs 受控 NNAPI 黑名单回退）。
2. **模型选型与分档矩阵**：为 P1 主体蒙版、P2 智能消除、P3 天空分割分别锁定默认推荐模型与低端机轻量备选（严格 MIT/Apache-2.0），并记录所有模型字节数、输入分辨率与许可证清单（同步规划 `THIRD_PARTY_NOTICES.md` 更新）。
3. **多镜像按需分发与空间管理**：扩展现有 `ModelDownloadManager` 支持多下载源自动回退（GitHub Releases / 国内可访问镜像 / 用户本地导入 `.onnx` 文件），保留字节数 + SHA-256 强校验与断点续传，并明确模型目录的存储上限、单独清理与版本升级淘汰策略。
4. **内存预算与生命周期红线**：明确大模型按需创建、闲置短 TTL 释放、严禁在主界面或预设条展开时预热（回应 issue #33）、以及根据设备可用内存自动降档输入分辨率的硬契约，确保叠加 24MP–60MP 相机原图与 GLES 纹理时不触发系统 LMK 强杀。

## Answer

基于 [ONNX Runtime Android 运行时体积、硬件加速与通用抠图/发丝模型事实调研](01-onnx-runtime-mobile-and-matting-models-research.md) 与 [端侧智能消除（MI-GAN / LaMa-Dilated）与天空分割模型事实调研](02-on-device-inpainting-and-sky-models-research.md) 的实测数据，锁定 P0 端侧 AI 底座规范如下：

### 1. 推理运行时与构建护栏
- **选型**：锁定 `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`（arm64 净增 **+18.3 MB**，arm64 APK 约 43 MB；比 1.32.0 的 +34.8 MB 省 16.5 MB，同时避免 `onnxruntime-mobile:1.18.0` 封版停更及 `.ort` 格式锁死，天然支持标准 `.onnx` 与用户本地导入）。
- **与现有 MediaPipe 的长期分工**：保留现有 MediaPipe Tasks（`face_landmarker.task` + `selfie_segmenter.tflite`）专职轻量人像关键点与离线美肤兜底；ORT 1.22.0 专职所有新增按需下载视觉模型。近期不做用 ORT 替代 MediaPipe 的重构。
- **构建与隐私护栏**：
  - 沿用现有 `packaging.jniLibs.excludes += ["lib/x86/**", "lib/x86_64/**"]` 与 release `ndk.debugSymbolLevel = "none"`。
  - R8 补充 `-keep class ai.onnxruntime.** { *; }`。
  - Manifest 使用 `tools:node="remove"` 移除 `ai.onnxruntime.TelemetryInitializer` ContentProvider，杜绝冷启动前置加载及向微软端点上报遥测。
- **Execution Provider 策略**：默认仅启用 **CPU / XNNPACK**（可靠支持 `minSdk 24`）；**不引入 QNN**（高通专有许可违反商用开源底线），**不默认开启 NNAPI**（Android 15 已废弃且跨 SoC 回退崩溃风险高）。

### 2. 务实端侧模型矩阵与热插拔插槽（100% MIT / Apache-2.0，100% 按需下载）

| 能力 | 端侧 v1 默认方案 | 许可证 | 体积 / 精度 | 端侧性能与内存基线 | 通用/高阶增强与插槽 |
|---|---|---|---|---|---|
| **P1 主体蒙版（人像发丝精抠）** | **MODNet** + 分块 `GuidedFilter` + **FMGE** 去色溢 | Apache-2.0（含权重） | **25.9 MB**（FP32 ONNX，禁止动态 INT8） | 512² 约 0.14 s / **334 MB** 峰值；band 精修 63 MB + FMGE 52 MB | 架构定义通用 `SubjectSegmenter` 接口；端侧 v1 聚焦人像发丝（排除 6.8–12 GB OOM 的 BiRefNet-lite 与 DIS5K 禁商用的 ISNet）；通用静物/宠物由 P4 云端或未来 <500 MB 合规轻量模型热插拔补齐 |
| **P2 涂抹智能消除** | **MI-GAN-512 生成器-only** + Kotlin 侧 128 px 上下文裁切 & 3–5 px 羽化 | 双 MIT（代码 + `LICENSE-WEIGHTS`） | **~28 MB**（FP32，禁止 INT8 QDQ） | 512² 0.54 s（旗舰）～5.4 s（低端）；Kotlin 预裁切 ≤1024² 防 24MP 内存暴涨 | 单次涂抹外接框建议 ≤256×256 px（超限时提示分次或自动 256 px 网格切块 + 128 px 重叠）；废弃 183.6 MB CelebA-HQ 人脸权重的 LaMa-Dilated |
| **P3 换天空** | **零模型经典天空分割**（顶部空间 + 色度/亮度先验）+ 分块 `GuidedFilter`（r=8, eps=1e-3） | 项目自研（MIT） | **0 MB** | 分块 2048² + 32 px 重叠（~200 MB）或 2048 代理上采样 | 排除 NVIDIA 非商用的 SegFormer-B0 与版权存疑的社区 176 MB SkySeg ONNX；架构预留 `SkySegmenter` 按需模型插槽（未来接入明确 MIT/Apache 2–5 MB 权重），复杂树枝场景支持走 P4 云端 |

> **实施前置注记**：MI-GAN 需在模型托管前导出「生成器-only 固定 512」ONNX（输入 `float32 [1,4,512,512]`，输出 `[1,3,512,512]`），消除官方 Pipeline ONNX 中的 20+ 动态裁切算子并锁定最终文件字节数与 SHA-256；同步更新 `THIRD_PARTY_NOTICES.md`。

### 3. 多镜像按需分发与空间管理（扩展 `ModelDownloadManager`）
- **统一目录**：所有按需模型集中存于 `<filesDir>/models/<model-id>/`，复用 `.part` Range 断点续传 → 精确字节数 + SHA-256 强校验 → 原子 `renameTo` 契约。
- **多镜像有序回退**：`AiModelSpec` 支持 `mirrorUrls: List<String>`（按顺序尝试：主 GitHub Releases → 可配置国内可达镜像 / CDN）；任一源网络失败或超时自动切换下一镜像，任一源 SHA-256 不匹配立即丢弃 `.part`。
- **用户本地导入兜底**：在下载失败或无网环境下，支持通过系统 SAF 文件选择器导入本地 `.onnx` 文件，导入时执行同一套字节数 + SHA-256 校验（防止错装/篡改模型导致推理崩溃），校验通过后拷入模型目录。
- **空间管理与淘汰**：提供统一「AI 模型管理」设置页，列出各模型名称、体积、下载状态，支持单模型删除/重下/一键清理；升级模型版本时按目录版本号清理旧模型文件，不留孤儿缓存。

### 4. 内存预算与会话生命周期红线
- **严禁后台预热（回应 issue #33）**：任何 ORT 大模型（MODNet / MI-GAN）严禁在 `App.onCreate`、主界面浏览或预设条展开时预加载或预热；仅在用户显式进入对应 AI 工具并触发操作时按需创建 `OrtSession`。
- **单活互斥 + 短 TTL 释放**：进程内通过单锁串行化 ORT 推理，同一时刻内存中最多驻留 **1 个** `OrtSession`（切换工具时先 `close()` 上一个会话再加载下一个）；空闲超过 **30 秒** 或退出 `EditorActivity` / 收到 `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` 时立即 `close()` 释放 native 内存。
- **设备分级与 OOM 优雅降级**：
  - 按 `ActivityManager.MemoryInfo.totalMem` 分两档：**低内存档（≤4 GB RAM）** 与 **标准档（>4 GB RAM）**。
  - **禁止 24MP–60MP 整图直接喂给推理或全分辨率 `GuidedFilter.smooth`**：推理前必须先缩放到模型输入代理（MODNet 512²；MI-GAN Kotlin 侧先裁至 ≤1024² 再按 512 送模）；全分辨率烘焙期的 `GuidedFilter`（63 MB/band）与 FMGE（1024 代理初始化 28 MB + band 内 2 轮细化 52 MB）强制纳入现有列带分块（2048×640）管线。
  - 所有推理入口捕获 `OutOfMemoryError` 与 `OrtException`，立即释放会话并向 UI 返回可恢复降级错误（不崩溃、不破坏 `EditGraph`）。
