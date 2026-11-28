# 第三方开源组件声明（Third-Party Notices）

PhotoSync 本身以 [MIT](LICENSE) 许可发布。本文件列出随应用分发的第三方组件、许可、源码
位置，以及 LGPL 组件的替换方式。

## LibRaw

- **版本**：0.21.4
- **源码**：`libraw/src/main/cpp/third_party/LibRaw/`（git submodule）
- **许可**：LGPL-2.1 / CDDL-1.0 双授权；本项目按 **LGPL-2.1** 分发
- **产物**：独立的 `lib/<abi>/libraw.so`

### LGPL-2.1 合规说明

LGPL-2.1 第 6 条要求：把该库组合进非 LGPL 应用分发二进制时，必须允许接收者修改该库并
替换应用中的对应库文件。本项目通过以下方式满足：

1. LibRaw（连同静态链接其中的 MIT 许可 Little-CMS 与 OpenMP 运行时）编译为**独立的**
   `libraw.so`，不与 JNI 桥接库 `libraw_wrapper.so` 静态合并；
2. 完整源码与构建脚本随仓库提供：`libraw/src/main/cpp/third_party/LibRaw` 与
   `libraw/src/main/cpp/CMakeLists.txt`；构建环境为 Android NDK + CMake（版本见
   `libraw/build.gradle` 与 CI workflow）；
3. 任何人均可修改 LibRaw 源码、重新编译 `libraw.so`，替换 APK 中的
   `lib/<abi>/libraw.so`，重新签名后安装；本项目不限制为替换该库而进行的重打包；
4. `libraw_wrapper.so` 只通过 LibRaw 的公开 C++ 接口动态链接 `libraw.so`。

**当前限制**：JNI 桥接层中自动高光增益的上限（`raw_autobright.cpp`）仍读取 LibRaw 的
内部直方图（`get_internal_data_pointer()`），因此替换 `libraw.so` 时请使用 **0.21.x**
兼容构建，以保证内部结构布局一致；后续会改为仅依赖公开 API。

## Little-CMS (lcms2)

- **版本**：2.18
- **源码**：`libraw/src/main/cpp/third_party/Little-CMS/`（git submodule）
- **许可**：MIT
- **链接方式**：静态链接进 `libraw.so`

## libjpeg-turbo

- **版本**：3.2.2
- **源码**：`libraw/src/main/cpp/third_party/libjpeg-turbo/`（git submodule）
- **许可**：IJG / BSD-3-Clause / zlib（详见上游 `LICENSE.md`）
- **链接方式**：以静态库形式链接进 `libraw_wrapper.so`，仅启用编码器；符号通过
  `-Wl,--exclude-libs,ALL` 隐藏，避免与系统 `libjpeg.so` 冲突。

## ONNX Runtime

- **版本**：1.22.0（`com.microsoft.onnxruntime:onnxruntime-android`）
- **源码**：https://github.com/microsoft/onnxruntime
- **许可**：MIT
- **产物**：`lib/<abi>/libonnxruntime.so` 与 Java/Kotlin 绑定，随安装包分发
- **隐私**：Manifest 显式移除上游后加入的遥测 `ContentProvider`
  （`ai.onnxruntime.TelemetryInitializer`），本应用不向 Microsoft 1DS 发送任何数据。

## MODNet

- **版本**：ONNX FP32 导出（`Xenova/modnet`，512×512）
- **源码**：https://github.com/ZHKKKe/MODNet
- **许可**：Apache-2.0（代码与权重）
- **分发方式**：**不内置于安装包**。设置页「AI 模型管理」按需下载（HuggingFace 上游 →
  `hf-mirror.com` 镜像回退），或由用户通过 SAF 导入本地 `.onnx` 文件；两种路径都先校验固化
  的精确字节数与 SHA-256 才会启用模型。旧的 GitHub Releases 主源（`ai-models-v1`）已返回
  404，不再作为镜像。
- **用途**：生成人像主体蒙版，用于主体虚化与透明 PNG 导出。

## MI-GAN

- **版本**：MI-GAN-512 pipeline ONNX 导出（`migan_pipeline_512_v1.onnx`，28,079,181 B）
- **源码**：https://github.com/Picsart-AI-Research/MI-GAN
- **导出来源**：上游社区导出的 pipeline 变体（`andraniksargsyan/migan` 的
  `migan_pipeline_v2.onnx`），由上游仓库发布，**非本仓库自产**；输入为 uint8 `image` + uint8
  `mask`（H/W 动态），输出为 uint8 RGB，掩码语义为 `255 = 保留 / 0 = 待修复`。
- **许可**：MIT（代码 `LICENSE` 与权重 `LICENSE-WEIGHTS` 均为 MIT）；上游模型卡标注
  `license: mit`。第三方镜像 `anyisalin/migan-onnx` 与主源字节一致，但没有许可元数据，因此
  只作为第三兜底，不作首选。
- **分发方式**：**不内置于安装包**。设置页「AI 模型管理」按需下载（HuggingFace 上游 →
  `hf-mirror.com` 同域镜像 → 字节一致的第三方镜像），或由用户通过 SAF 导入本地 `.onnx`
  文件；两条路径都先校验固化的精确字节数与 SHA-256
  （`6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b`）才会启用模型。
- **兼容识别**：旧的生成器-only 导出（`migan_generator_512_v1.onnx`，28,026,303 B /
  `522048b4fc3fae7adff3af48859cd8d19412d02a8a9420614f3d4474a0901bfe`）从未发布，但仍保留为
  只读目录条目，供本地导入识别与运行时变体探测使用；它不出现在下载按钮上。
- **用途**：涂抹区域内容感知修复（智能消除）。
- **合规说明**：仓库自身以 MIT 分发，随应用分发的声明保留在上游仓库
  `LICENSE-WEIGHTS` 与本文件；若上游权重许可发生变化，删除该按需模型即可，不影响安装包其余部分。

## 程序化天空（P3）

- **实现**：`editor/src/main/java/cn/devcxl/photosync/editor/domain/ProceduralSky.kt` 与
  `editor/src/main/java/cn/devcxl/photosync/editor/render/SkyShaderSources.kt`
- **许可**：项目自研，随本仓库以 MIT 发布
- **分发方式**：随应用**内置** 4 组 0 KB 程序化大气基准（`clear_blue`、`sunset_glow`、
  `blue_hour`、`warm_dusk`）。它们由 Shader 按解析式实时生成，不包含任何第三方位图或权重，
  不产生额外署名义务。

## 可选 CC0 高清云彩包（P3）

- **分发方式**：**不内置**。仅在用户主动从「AI 模型管理」下载并启用时按需获取，存放于
  `<filesDir>/models/sky-pack-cc0-clouds/`，与 ONNX 模型共享多镜像下载与 SHA-256 校验路径。
- **许可要求**：只接受 **CC0-1.0 / Public Domain** 素材。每个包的作者、原始 URL、许可文本与
  CC0 声明必须在发布说明和本节逐条记录，并固化精确字节数与 SHA-256 之后才可发布。
- **当前清单**：当前版本尚未发布任何 CC0 天空包（`SkyModelCatalog.CC0_CLOUD_PACK` 是未固化
  的预留槽位，`isPinned == false`，安装会以 `NOT_AVAILABLE` 干净失败），因此本节暂无条目。
  新增包时必须先补齐上述出处与校验值。

## Android NDK 运行时

- **libc++_shared**（`lib/<abi>/libc++_shared.so`）：Apache-2.0 with LLVM exceptions。
  `libraw.so` 与 `libraw_wrapper.so` 共享该运行时，以保证跨 `.so` 边界传递 C++ 对象安全。
- **libomp**（OpenMP）：Apache-2.0 with LLVM exceptions。静态链接进 `libraw.so`，其符号
  导出供 `libraw_wrapper.so` 复用，确保进程内只有一份 OpenMP 线程池。

## 报告问题

如发现许可或归属问题，请在仓库 Issues 中提出；如需要替换 LibRaw，可按上文步骤自行重编
`libraw.so` 并重打包。
