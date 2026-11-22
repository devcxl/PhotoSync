# MediaPipe 人像模型体积、许可证与 GitHub APK/AAB 双渠道分发事实调研

Type: research
Status: resolved

## Question

本仓库的发布工作流（`.github/workflows/release.yml`）同时构建 `app-release.aab` 与按 ABI 拆分的 `app-release-apks` 并直接上传到 GitHub Releases（不依赖 Google Play 商店安装，因此直接下载 APK 的用户无法使用 Play Asset Delivery）。

针对 `docs/research/2026-11-21-image-editor-pipeline.md` §4.5 选定的 MediaPipe 视觉模型（`face_landmarker.task`、`selfie_multiclass_256x256.tflite` 以及可选的全身分割模型）：
1. 这些官方模型文件的确切字节大小、输入/输出张量规格、量化类型（FP16/INT8）以及再分发许可证（Model Card License）分别是什么？
2. `com.google.mediapipe:tasks-vision:1.2.3` AAR 本身包含哪些 native `.so` 库，对 `arm64-v8a` 和 `armeabi-v7a` 单 ABI APK 的体积增量是多少？在 `minSdk 24` 上的 GPU Delegate 兼容性与回退机制如何？
3. 在 GitHub Release 直发 APK 与 AAB 双渠道并存的前提下，内置 `.cube` LUT 与人像模型文件应采用哪种打包/加载方案（直接内置 `assets/` vs 自建首次进入人像面板时的 HTTPS 按需下载缓存 + SHA-256 校验），各自的确切体积代价是什么？

## Answer

完整一手调研（含每个结论的来源链接、实测字节数与复现命令）见
[`docs/research/2026-11-22-mediapipe-model-distribution.md`](../../../docs/research/2026-11-22-mediapipe-model-distribution.md)。

### 1. 模型字节、张量、量化、许可证（全部实测）

| 模型 | 字节 | 输入 → 输出 | 权重精度 | 许可证 |
|---|---:|---|---|---|
| `face_landmarker.task` | 3,758,596 | 128² 检测器 + 256² landmarker（→478×3）+ 146 点 blendshape（→52） | FP16（IO FP32） | Apache-2.0 |
| `selfie_multiclass_256x256.tflite` | 16,371,837 | `[1,256,256,3]` → `[1,256,256,6]` | **FP32，无量化** | Apache-2.0 |
| `deeplab_v3.tflite` | 2,780,176 | `[1,257,257,3]`（[-1,1]）→ `[1,257,257,21]`，person=15 | **FP32，无量化** | Apache-2.0 |
| `selfie_segmenter.tflite`（补充） | 249,537 | `[1,256,256,3]` → `[1,256,256,1]` | FP16 | Apache-2.0 |
| `pose_landmarker_full.task`（可选） | 9,398,198 | 224² 检测 + 256² landmarker（39 点 + mask） | FP16 | Apache-2.0 |

- 许可证证据：5 份官方 Model Card PDF 的 “LICENSED UNDER – Apache License, Version 2.0” + HF `litert-community` 仓库 `license: apache-2.0` + Maven POM 的 Apache-2.0。可再分发/商用。
- 官方**没有** INT8/FP16 的 multiclass 或 deeplab 变体（GCS 桶只有 `float32`/`float16` 目录，`int8` 路径 404）。
- 类别索引实测为 `0 background / 1 hair / 2 body-skin / 3 face-skin / 4 clothes / 5 others`。

### 2. AAR 解剖与单 ABI 增量

- `tasks-vision:1.2.3` AAR 仅 **486,255 B**，`unzip -l` 只有 5 个条目、**无任何 `.so`、无 consumer proguard 规则**；native 全在传递依赖 `tasks-core:1.2.3`（**32,552,186 B**，单 `.so` `libmediapipe_tasks_jni.so`：arm64 15,971,160 / v7a 11,436,276 / x86 22,750,140 / x86_64 19,803,544）。
- 探针工程（AGP 8.12 + R8 + `splits.abi`）实测 APK 增量：**arm64 +16,568,927 B（15.80 MiB）**、**v7a +12,034,025 B（11.48 MiB）**；其中 `.so` 占 96.4%，MediaPipe DEX 仅 563,005 B。
- 本仓库 release APK 的 `extractNativeLibs=false`，`.so` 以 Stored 写入（aapt2 dump + zip 条目双证），故增量≈`.so` 原始字节。
- **AAB 两个陷阱（实测）**：默认会把 x86/x86_64 打进 bundle（2 ABI → 4 ABI 多 26,359,011 B），默认附 native 调试符号（2 ABI 多 6,207,008 B / 4 ABI 多 15,234,120 B）。修法：`packaging.jniLibs.excludes` + `ndk.debugSymbolLevel 'none'`（release）。
- `minSdk`：两个 AAR 的 manifest 均为 24，但官方 setup 页建议 28，24–27 属未承诺组合。

### 3. GPU Delegate（minSdk 24）

- 官方硬性要求 **OpenGL ES 3.1+**（Android 上 API 21 起有 API，但不保证驱动实现）。
- 代码里只有「ES3 上下文创建失败 → 退 ES2」这一级，且 ES2 不满足 ML 推理；**任务层无自动 CPU 回退**，初始化失败抛 `MediaPipeException`，调用方必须 catch 后用 `Delegate.CPU` 重建。
- GPU delegate 内部还有两级回退：OpenCL→OpenGL、单算子不支持则拆给 CPU。
- 结论：**默认 CPU**，GPU 仅当 `reqGlEsVersion >= 0x30001` 时尝试并记忆降级；官方 Pixel 6 基准 multiclass CPU 217.76 ms vs GPU 71.24 ms，selfie_segmenter 反而 CPU 更快。

### 4. 分发方案与体积代价

现状基线（v0.2.1）：arm64 APK 3,833,143 / v7a 3,217,745 / AAB 5,951,188。

| 方案 | arm64 APK | 首启下载 | 诚实评价 |
|---|---:|---:|---|
| A 全内置 `assets/` | **40,106,792** | 0 | 10.5× 膨胀；`.tflite` 因 AGP 4.1 默认 `noCompress` 以 **Stored** 入包，16.37 MB 一分不省 |
| B 全按需下载 | 20,402,070 | 20,130,433 | 首次必须联网，新装用户无任何兜底 |
| **C 混合（推荐）** | **23,984,471** | **16,371,837** | 重塑离线可用；美肤下载 16.37 MB；渠道无关 |

**推荐 C**：runtime 必进 APK（`System.loadLibrary` 无法按需）＋内置 `face_landmarker.task`（3,332,599 deflate 后）与 `selfie_segmenter.tflite`（249,537，兜底 1 类人像 mask）＋首进面板下载 `selfie_multiclass_256x256.tflite`（16,371,837，SHA-256 `c6748b12…e0e0`）＋**不引入** `deeplab_v3`（multiclass 已覆盖全身，person 类更粗）与 `pose_landmarker_full`。

- 加载方式：`setModelAssetFileDescriptor(fd)` 或 `setModelAssetBuffer(mapped)`；`setModelAssetPath` 只认 `assets/`。
- Play Asset Delivery 不可用（GitHub 直发 APK 无 Play 服务），双渠道必须渠道无关，故自建下载器是唯一同时满足两渠道的方案；GitHub 资产与 HF 均支持 Range（实测 206），可断点续传。
- `.cube` LUT：内置滤镜用参数预设（0 字节）；33³ `.cube` ASCII 实测 970,420 B，社区 LUT 走 SAF 导入并转 RGBA16F（287,496 B/个）缓存，不随包分发。
- 落地护栏：`packaging.jniLibs.excludes` 掉 x86/x86_64（AAB −26.36 MB）、`debugSymbolLevel 'none'`（AAB −6.21 MB）、给 R8 加 `-keep class com.google.mediapipe.**`（AAR 无 consumer rules）、上线前抓包确认 `RemoteLoggingClient` 遥测行为。
