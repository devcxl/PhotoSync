# MediaPipe 人像模型体积、许可证与 GitHub APK/AAB 双渠道分发事实调研

Type: research
Status: claimed

## Question

本仓库的发布工作流（`.github/workflows/release.yml`）同时构建 `app-release.aab` 与按 ABI 拆分的 `app-release-apks` 并直接上传到 GitHub Releases（不依赖 Google Play 商店安装，因此直接下载 APK 的用户无法使用 Play Asset Delivery）。

针对 `docs/research/2026-11-21-image-editor-pipeline.md` §4.5 选定的 MediaPipe 视觉模型（`face_landmarker.task`、`selfie_multiclass_256x256.tflite` 以及可选的全身分割模型）：
1. 这些官方模型文件的确切字节大小、输入/输出张量规格、量化类型（FP16/INT8）以及再分发许可证（Model Card License）分别是什么？
2. `com.google.mediapipe:tasks-vision:1.2.3` AAR 本身包含哪些 native `.so` 库，对 `arm64-v8a` 和 `armeabi-v7a` 单 ABI APK 的体积增量是多少？在 `minSdk 24` 上的 GPU Delegate 兼容性与回退机制如何？
3. 在 GitHub Release 直发 APK 与 AAB 双渠道并存的前提下，内置 `.cube` LUT 与人像模型文件应采用哪种打包/加载方案（直接内置 `assets/` vs 自建首次进入人像面板时的 HTTPS 按需下载缓存 + SHA-256 校验），各自的确切体积代价是什么？
