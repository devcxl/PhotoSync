# ONNX Runtime Android 运行时体积、硬件加速与通用抠图/发丝模型事实调研

Type: research
Status: resolved
Assignee: subagent/research-ort-matting
Blocked by: none
Research branch: `research/ort-matting` (`547eb20`) → `docs/research/2026-11-26-ort-android-and-matting-models.md`

## Question

在严守 MIT / Apache-2.0 商用合规、目标平台 `minSdk 24` / `compileSdk 36`、且现有 arm64 APK 已达 24.6 MB（已含 MediaPipe `libmediapipe_tasks_jni.so`）的前提下，围绕 P0 推理底座与 P1 主体蒙版（通用抠图 + 发丝边缘），需要通过一手来源查清以下事实：

1. **ONNX Runtime Android 集成代价与硬件加速**：
   - `com.microsoft.onnxruntime:onnxruntime-android` 最新稳定版本在 `arm64-v8a` 与 `armeabi-v7a` 下各增加多少 APK/AAB 体积（Full build vs Minimal/Custom Ops build）？是否需要额外的 R8 / ProGuard 规则？
   - 在 `minSdk 24` 上支持哪些 Execution Provider（CPU / XNNPACK / NNAPI / QNN）？NNAPI 在不同 Android 版本与 SoC 上的已知算子回退或崩溃边界是什么？
2. **可商用开源通用抠图与发丝模型对比**：
   - 核实 **BiRefNet-lite**（含 FP32/FP16/INT8 及 2K 变体）、**ISNet-general-use**、**MODNet**（纯人像对照组）的精确许可证（Model Card / LICENSE 原文）、官方或社区可验证的 ONNX 文件字节大小、输入/输出张量规格、是否包含动态 shape 或 ORT Mobile/NNAPI 不兼容算子，以及移动端参考推理延迟与峰值内存。
3. **高分辨率相机照片（24MP–60MP）边缘精修与去色溢**：
   - 将 512×512 或 1024×1024 模型输出的 alpha matte 通过现有 `GuidedFilter` 上采样对齐到 24MP–60MP 原图（及预览分辨率）的经典半径/正则化参数与内存/耗时边界是什么？
   - 换底/抠图时去除发丝背景渗色（Color Decontamination / Fast Multi-Level Foreground Estimation 等）有哪些适合移动端实时预览与列带分块烘焙的轻量无模型算法？

## Answer

完整一手实证调研与复现命令见分支 `research/ort-matting`（commit `547eb20`）下的 `docs/research/2026-11-26-ort-android-and-matting-models.md`。关键结论：

1. **ONNX Runtime Android 集成代价远超预期（实测探针构建）**：
   - `onnxruntime-android:1.32.0` 使 arm64 APK 净增 **+34.79 MiB**（v7a +24.73 MiB），是 MediaPipe（+15.80 MiB）的 2.2 倍；AAB 必须显式配 `packaging.jniLibs.excludes` 与 `debugSymbolLevel 'none'`。
   - Full vs Minimal：1.18.0 同版实测 `.so` 从 16.28 MB 降至 3.74 MB（22.95%，投影 1.32 自编约 8.3 MB），但官方 minimal AAR 已停更在 1.18.0，且需转 `.ort` 格式。
   - **遥测与权限隐患**：ORT AAR 自动合并 `INTERNET` + `ACCESS_NETWORK_STATE` 与 `TelemetryInitializer` ContentProvider（端点 `mobile.events.data.microsoft.com`），必须用 manifest `tools:node="remove"` 移除；R8 需手动补 `-keep class ai.onnxruntime.** { *; }`。
   - **硬件加速边界**：NNAPI 已在 Android 15 废弃（且要求 API 27+）；QNN 依赖高通专有许可（63 MB AAR）不符合商用开源约束；实际可靠基线为 CPU / XNNPACK。
2. **三大抠图候选模型实测结论（重大修正）**：
   - **BiRefNet-lite（MIT）手机端不可行**：FP32 224 MB / FP16 114.5 MB，无官方 INT8，固定 1024²、16,400 节点（含 GatherND/ScatterND/Where/Mod），实测单次推理 **6.5 s、峰值内存 6.8–12.0 GB**，移动端必然 OOM。
   - **ISNet-general-use 排除**：许可证链条断裂（onnx-community 标 AGPL-3.0，训练集 DIS5K 明确禁商用）。
   - **MODNet（Apache-2.0，25.9 MB）端侧可行**：动态 shape，512² 仅 **0.137 s / 334 MB 峰值内存**，但仅覆盖人像，不覆盖静物/通用主体；且社区动态 INT8 镜像反而慢 3×，不建议动态 INT8。
3. **24–60MP 边缘精修与去色溢**：
   - `GuidedFilter.smooth` 整帧需 1.15–3.13 GB，但放进现有 bake band（2048×640）仅需 **63 MB**（建议 GLSL 优先 + CPU fast guided filter 兜底，参数 `r=12–16 px @1024`、`eps=1e-4…1e-3`）。
   - 去色溢采用 **Fast Multi-Level Foreground Estimation**（arXiv:2006.14970，O(n)/像素，2×2 线性解，`εr=5e-3, ω=0.1`），按「1024 代理求 F/B + band 内 2 轮细化」落地。
