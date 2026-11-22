# MediaPipe 人像模型：体积、许可证与 GitHub APK/AAB 双渠道分发事实调研

- 日期：2026-11-22
- 关联工单：[MediaPipe 人像模型体积、许可证与 GitHub APK/AAB 双渠道分发事实调研](../../.scratch/image-editor/issues/01-mediapipe-model-packaging-and-distribution.md)
- 上游基线：`docs/research/2026-11-21-image-editor-pipeline.md` §4.5
- 被测版本：`com.google.mediapipe:tasks-vision:1.2.3`（Google Maven `latest`，元数据 `lastUpdated=20261111232110`）
- 实测环境：Linux x86_64、Gradle 8.13、AGP 8.12.0、compileSdk 36、minSdk 24、JDK 17、R8（`proguard-android-optimize.txt`）

> 本文所有字节数均为**实测**：模型文件取自 `storage.googleapis.com` 与 HuggingFace 官方镜像（两者 SHA-256 完全一致），AAR 取自 `dl.google.com`，APK/AAB 由与被测仓库同版本 AGP 的探针工程真实构建后测量。凡属推算而非实测的，均在正文标注「投影」。

---

## 0. 结论摘要

1. **单个模型的确切体积**：`face_landmarker.task` = **3,758,596 B**（3.58 MiB）；`selfie_multiclass_256x256.tflite` = **16,371,837 B**（15.61 MiB）；`deeplab_v3.tflite` = **2,780,176 B**；补充发现 `selfie_segmenter.tflite`（二分类人像 mask）只有 **249,537 B**。
2. **许可证**：上表模型全部 **Apache-2.0**。证据是各自 Model Card PDF 的 “LICENSED UNDER – Apache License, Version 2.0”，加上 HuggingFace `litert-community` 仓库的 `license: apache-2.0` 元数据；`tasks-vision`/`tasks-core` 的 POM 亦声明 Apache-2.0。可商用、可再分发、可修改，但需保留 NOTICE/许可证文本。
3. **量化类型只有一种官方口径**：multiclass 与 deeplab 是**纯 FP32**（权重 `FLOAT32`，无 INT8/FP16 变体），face landmarker 与 selfie_segmenter 是 **FP16 权重 + FP32 IO**。GCS 桶列举证明这些模型**没有** int8 目录（`face_landmarker/int8/...` 返回 404）。
4. **`tasks-vision:1.2.3` AAR 只有 486,255 B，且不含任何 `.so`**——它是一个纯 Java 壳。native 全部在传递依赖 `com.google.mediapipe:tasks-core:1.2.3`（**32,552,186 B**，含 4 个 ABI 的 `libmediapipe_tasks_jni.so`）。只看 `tasks-vision` 的 AAR 大小会严重低估体积。
5. **单 ABI APK 实测增量**（探针工程，release + R8 + `splits.abi`）：
   - `arm64-v8a`：**+16,568,927 B（+15.80 MiB）**，其中 `.so` 占 15,971,160 B（96.4%），MediaPipe 相关 DEX 仅 563,005 B（deflate 后）。
   - `armeabi-v7a`：**+12,034,025 B（+11.48 MiB）**。
6. **本仓库 release APK 的 `.so` 是未压缩存储**（`android:extractNativeLibs="false"`，aapt2 dump 与 zip 条目 “Stored 0%” 双重实测），所以体积增量≈`.so` 原始字节，不能被 deflate 摊薄。
7. **GPU Delegate 在 `minSdk 24` 上可用但不可依赖**：官方要求 **OpenGL ES 3.1+**；任务层**没有自动回退 CPU**，初始化失败会抛 `MediaPipeException`，必须由调用方捕获后重建 CPU task。GPU delegate 内部另有 OpenCL→OpenGL 与「不支持的算子拆给 CPU」两级回退，但图谱层没有。
8. **分发结论（推荐混合方案）**：runtime 必须进 APK（不可下载，`System.loadLibrary` 路径固定）；`face_landmarker.task` + `selfie_segmenter.tflite` 内置（**+3,582,401 B**）；`selfie_multiclass_256x256.tflite` 首次进入人像面板按需 HTTPS 下载（**16,371,837 B 流量 + 同等磁盘**）；`deeplab_v3` v1 不引入。arm64 APK 从 3.83 MB → **23.98 MB**（投影）；若全内置则 **40.11 MB**（投影）。
9. **AAB 双渠道有两个必须处理的大小陷阱**：默认会把 x86/x86_64 也打进 bundle（实测 2 ABI→4 ABI 多 **26,359,011 B**），并默认附带 native 调试符号（实测多 **6,207,008 B / 15,234,120 B**）。两项都可在 release 关掉，见 §3.5。

---

## 1. 测量环境与复现方法

| 项 | 值 | 证据 |
|---|---|---|
| AGP | 8.12.0 | 探针工程 `build.gradle`，与仓库 `gradle/libs.versions.toml` 一致 |
| Gradle | 8.13 | 仓库 `gradle/wrapper/gradle-wrapper.properties` |
| 探针工程 | 空 `Application`（无代码）+ `implementation "com.google.mediapipe:tasks-vision:1.2.3"`，`minifyEnabled true`，`splits.abi` 只留 arm64-v8a / armeabi-v7a | `/tmp/opencode/mp/probe`（临时目录，未写入仓库） |
| 基线 APK | 1,207 B（空壳、无依赖） | 实测 |
| 模型文件 | GCS 与 HuggingFace 两处下载后 `sha256sum` 完全一致 | 见 §2.6 |
| AAR | Google Maven 直接下载后 `unzip -l` / `readelf -d` | 见 §3 |

复现命令见附录 A。

---

## 2. 模型文件：字节、张量规格、量化与许可证

### 2.1 汇总表（全部实测）

| 模型 | 字节数 | SHA-256 | 权重精度 | 输入 | 输出 | 许可证 |
|---|---:|---|---|---|---|---|
| `face_landmarker.task` | **3,758,596** | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` | FP16（IO 为 FP32） | 见 §2.2（三模型级联） | 478×3 landmarks、1 个 face flag、52 个 blendshape | Apache-2.0 |
| `selfie_multiclass_256x256.tflite` | **16,371,837** | `c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0` | **FP32（无量化）** | `[1,256,256,3]` FLOAT32 | `[1,256,256,6]` FLOAT32 | Apache-2.0 |
| `deeplab_v3.tflite` | **2,780,176** | `ff36e24d40547fe9e645e2f4e8745d1876d6e38b332d39a82f0bf0f5d1d561b3` | **FP32（无量化）** | `[1,257,257,3]` FLOAT32（quant min −1.0 / max 0.9921875） | `[1,257,257,21]` FLOAT32 | Apache-2.0 |
| `selfie_segmenter.tflite`（补充） | **249,537** | `191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b` | **FP16 权重** | `[1,256,256,3]` FLOAT32 | `[1,256,256,1]` FLOAT32 | Apache-2.0 |
| `pose_landmarker_full.task`（可选） | **9,398,198** | `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1` | FP16 权重 | 见 §2.5 | 39 点 + 分割 mask | Apache-2.0 |

官方文档给出的模型清单与量化列（“float 16” / “None (float32)”）与上表对应行逐一吻合（pose 见 Pose Landmarker 页面）：
<https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter/android#models>、
<https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android#models>。

### 2.2 `face_landmarker.task` 内部结构

`.task` 本身是一个 **zip 包**（store 模式，几乎无额外开销），解包后 4 个条目（合计 3,758,024 B，与归档 3,758,596 B 的差值为 zip 头）：

| 条目 | 字节 | 输入张量 | 输出张量 | 权重类型 |
|---|---:|---|---|---|
| `face_detector.tflite`（BlazeFace short-range） | 229,746 | `[1,128,128,3]` FLOAT32 | `[1,896,16]`、`[1,896,1]` | FP16（203,132 B 权重） |
| `face_landmarks_detector.tflite`（Face Mesh V2） | 2,553,590 | `[1,256,256,3]` FLOAT32 | `[1,1,1,1434]`（=478×3）、`[1,1,1,1]` face flag、`[1,2]` | FP16（2,398,736 B 权重） |
| `face_blendshapes.tflite` | 955,312 | `[1,146,2]` FLOAT32（146 个关键点） | `[52]` FLOAT32 | FP16（901,192 B 权重） |
| `geometry_pipeline_metadata_landmarks.binarypb` | 19,376 | — | 几何流水线配置（含 4×4 变换矩阵推导） | — |

用法事实：Face Landmarker 的 478 个点是**任务层输出**，由 landmark 模型的 1434 维原始输出经 geometry pipeline 还原；52 个 blendshape 由独立小模型给出，只有设置 `outputFaceBlendshapes(true)` 时才会加载/执行。官方输出说明见
<https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android#task_output>。

> 体积优化点：如果不需要表情驱动（52 blendshape），**无法**只删 `face_blendshapes.tflite`——`.task` 是官方签发的整合包，任务层按固定结构解析；要裁剪只能自己重打包（Apache-2.0 允许，但会失去官方 SHA-256 可验证性）。

### 2.3 `selfie_multiclass_256x256.tflite`

| 项 | 实测值 |
|---|---|
| 文件字节 | 16,371,837 |
| Schema 版本 / 子图数 | 3 / 1 |
| 输入 | `input_29`：`[1,256,256,3]`，`FLOAT32`，无量化参数 |
| 输出 | `Identity`：`[1,256,256,6]`，`FLOAT32`（每像素 6 类概率，`[0,1]`） |
| 权重 | 181 个 `FLOAT32` 张量，合计 16,281,356 B；**零个 INT8/UINT8 张量** |
| 算子 | 11 种（含 `CONV_2D`、`DEPTHWISE_CONV_2D`、`FULLY_CONNECTED`、`SOFTMAX` 等），无 `QUANTIZE`/`DEQUANTIZE` |

类别索引顺序（从文件内嵌 `labels.txt` 的字节偏移逐一读出，**不是**转述文档）：

```
0 background | 1 hair | 2 body-skin | 3 face-skin | 4 clothes | 5 others(accessories)
```

这与官方文档一致（<https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter/android#multi-class_selfie_segmentation_model>），并直接决定了美肤 mask：
`skin = class2 ∪ class3`，`protect = class1(hair) ∪ class0(background)`，美体 mask = `class1..5` 的并集（或 `class2..5`）。

Model Card（`Model Card Multiclass Segmentation.pdf`）确认输入是「normalized RGB image, 256×256×3」，输出是 `[256,256,6]` 的概率张量，模型为 Vision Transformer（XenoFormer）架构。

### 2.4 `deeplab_v3.tflite`

| 项 | 实测值 |
|---|---|
| 文件字节 | 2,780,176 |
| 输入 | `sub_7`：`[1,257,257,3]`，`FLOAT32`，quantization `min=-1.0`、`max=0.9921875`（即 [-1,1] 归一化域） |
| 输出 | `ResizeBilinear_3`：`[1,257,257,21]`，`FLOAT32` |
| 权重 | 110 个 `FLOAT32` 张量，合计 2,753,452 B |

内嵌 `labelmap.txt` 的顺序为 PASCAL VOC 21 类，**person 的索引是 15**（`background, aeroplane, bicycle, bird, boat, bottle, bus, car, cat, chair, cow, dining table, dog, horse, motorbike, person, potted plant, sheep, sofa, train, tv`）。

**结论：`deeplab_v3` 对本项目的美体需求基本冗余。** multiclass Model Card 明确写着「Supports single or multiple people in the frame, selfies and **full body images**」，其 `body-skin + clothes + others` 已经给出比 VOC 的单一 `person` 类更细的身体 mask；deeplab 额外提供的是通用类别（车、狗、沙发），编辑器用不到，却要多付 2.78 MB + 124 ms CPU 推理（官方 Pixel 6 基准 123.93 ms）。

### 2.5 `selfie_segmenter.tflite`（本次调研的意外收获）

| 项 | 实测值 |
|---|---|
| 文件字节 | **249,537**（multiclass 的 1/65） |
| 输入 / 输出 | `[1,256,256,3]` FLOAT32 → `[1,256,256,1]` FLOAT32（sigmoid，人像概率） |
| 权重 | 110 个 `FLOAT16` 张量，合计 212,826 B |

官方基准（Pixel 6）：CPU 33.46 ms / GPU 35.15 ms。它只有「人/背景」二分类，不能区分皮肤与头发，但足以做「背景虚化/背景替换/人像提亮」，也可作为 16 MB 主模型下载完成前的兜底。

`pose_landmarker_full.task`（9,398,198 B）：`pose_detector` 输入 `[1,224,224,3]`、输出 `[1,2254,12]`+`[1,2254,1]`；`pose_landmarks_detector` 输入 `[1,256,256,3]`、输出 `[1,195]`（39×5）、`[1,1]`、`[1,256,256,1]`（分割 mask）、`[1,64,64,39]`（heatmap）、`[1,117]`（world landmarks）。若美体只做 mask 驱动的位移场，用不到骨骼点，可省这 8.96 MiB。

### 2.6 许可证证据链

| 证据 | 内容 |
|---|---|
| `Model Card MediaPipe Selfie Segmentation.pdf` | “AUTHORS … LICENSED UNDER **Apache License, Version 2.0**” |
| `Model Card Multiclass Segmentation.pdf` | “MODEL DATE May 10, 2023 … LICENSED UNDER **Apache License, Version 2.0**” |
| `Model Card MediaPipe Face Mesh V2.pdf` | “LICENSED UNDER **Apache License, Version 2.0**” |
| `Model Card Blendshape V2.pdf` | “LICENSED UNDER **Apache License, Version 2.0**” |
| `MediaPipe BlazeFace Model Card (Short Range).pdf` | “LICENSED UNDER **Apache License, Version 2.0**” |
| HF 仓库元数据 | `huggingface.co/api/models/litert-community/MediaPipe-FaceLandmarker` → `"license": "apache-2.0"`；`…-Selfie-Multiclass`、`…-DeepLab-V3`、`…-Selfie-Square` 同 |
| Maven POM | `tasks-vision-1.2.3.pom` / `tasks-core-1.2.3.pom` 均含 “The Apache Software License, Version 2.0” |

注意：`deeplab_v3` 的 HF 仓库里**没有** PDF Model Card（只有 README + 权重），其许可证以 HF `license: apache-2.0` 字段与上游 Kaggle `tensorflow/deeplabv3` 为准。这对 Apache-2.0 再分发不构成障碍，但若要做合规归档，应把 HF README 与 `LICENSE` 一并留存。

### 2.7 官方镜像一致性与版本固定

| 文件 | GCS (`storage.googleapis.com/mediapipe-models/...`) | HF (`huggingface.co/litert-community/...`) | SHA-256 |
|---|---|---|---|
| `face_landmarker.task` | 3,758,596 B，`Last-Modified 2023-05-03` | 3,758,596 B | 相同 |
| `selfie_multiclass_256x256.tflite` | 16,371,837 B，`2023-04-26` | 16,371,837 B | 相同 |
| `deeplab_v3.tflite` | 2,780,176 B，`2023-04-26` | 2,780,176 B | 相同 |
| `selfie_segmenter.tflite` | 249,537 B，`2023-05-07` | 249,537 B | 相同 |

推论：模型自 2023 年起未变，SHA-256 可以硬编码进 APK 作为下载校验基准；GCS 桶里每个模型只有 `…/vN/` 与 `…/latest/` 两个键，**没有 int8 变体**（`face_landmarker/face_landmarker/int8/latest/face_landmarker.task` 实测 HTTP 404；桶列举见附录 A）。

---

## 3. `tasks-vision:1.2.3` AAR 解剖

### 3.1 `tasks-vision` 本身只有 5 个条目

```
486,255 B  tasks-vision-1.2.3.aar   sha256 96b81edc82867ee3c09d0e662324cabc8b99dc0c0fccabf0c73b5562c63dfe01
  AndroidManifest.xml            261 B   (minSdkVersion=24, targetSdkVersion=34)
  classes.jar                277,070 B
  res/                             0 B
  R.txt                       12,129 B
  META-INF/NOTICE          3,283,018 B   (未压缩；实测不会进 APK，见 §3.5)
```

**没有 `jni/`，没有 `proguard.txt`/consumer rules。** POM 依赖：

```
androidx.annotation:1.1.0
com.google.guava:guava:27.0.1-android
com.google.mediapipe:tasks-core:1.2.3     ← native 在这里
com.google.ai.edge.litertlm:litertlm-android:0.18.0  (optional=true，不传递)
```

### 3.2 `tasks-core` 才是体积主体

```
32,552,186 B  tasks-core-1.2.3.aar   sha256 39dafa4a851b520839cea7cf1a80e873082bd2eb3a136990dfd89061569638d7
  classes.jar                       1,670,963 B
  jni/arm64-v8a/libmediapipe_tasks_jni.so    15,971,160 B
  jni/armeabi-v7a/libmediapipe_tasks_jni.so  11,436,276 B
  jni/x86/libmediapipe_tasks_jni.so          22,750,140 B
  jni/x86_64/libmediapipe_tasks_jni.so       19,803,544 B
  META-INF/NOTICE                    3,283,018 B
```

- 每个 ABI **只有一个** `.so`（`libmediapipe_tasks_jni.so`），TFLite/LiteRT、GPU delegate、图像处理全部静态链入，因此没有「可选 GPU 库」可以裁掉。
- `readelf -d` 实测依赖：`libEGL.so`、`libGLESv3.so`、`libGLESv2.so`、`libandroid.so`、`libjnigraphics.so`、`libz.so`、`liblog.so`、`libm.so`、`libdl.so`、`libc.so`。其中 `libGLESv3`/`libEGL` 说明 GPU 路径是**运行时才用**的，但库本身无法按需拆分。
- AAR 解压后 74,927,489 B，压缩率约 43%，即 Gradle 缓存里躺 32.55 MB、解压后 74.9 MB。

### 3.3 APK 里 `.so` 是「未压缩存储」

本仓库 release APK（v0.2.1，GitHub 实测下载）验证：

```
aapt2 dump xmltree app-arm64-v8a-release.apk --file AndroidManifest.xml
  android:minSdkVersion=24, targetSdkVersion=36, android:extractNativeLibs=false
unzip -lv
  2646464  Stored  2646464  0%  lib/arm64-v8a/libraw_wrapper.so
```

探针工程加入 MediaPipe 后同样 `Stored 0%`。即：**APK 体积增量 ≈ `.so` 原始字节 + 约 16 KB 对齐填充**，deflate 帮不上忙（`.so` 的 gzip-9 后仍为 7.29 MB / 15.97 MB，若改用 `useLegacyPackaging=true` 可省约 8.7 MB，但会引入安装时解压与启动变慢，且与该仓库现有配置不一致，不建议）。

### 3.4 实测 APK 增量（探针工程，release + R8）

| 配置 | arm64-v8a APK | armeabi-v7a APK |
|---|---:|---:|
| 空壳基线 | 1,207（实测） | ≈1,207（推算，未单独构建） |
| + `tasks-vision:1.2.3`（无 assets） | **16,570,134** | **12,035,232** |
| 增量 | **+16,568,927（+15.80 MiB）** | **+12,034,025（+11.48 MiB）** |
| + face_landmarker + selfie_segmenter（内置） | 20,152,535 | 15,617,633 |
| + face_landmarker + selfie_multiclass（内置） | 36,274,856 | 31,739,954 |

拆解（arm64）：`15,971,160`（`.so`）+ `563,005`（`classes.dex` deflate 后，未压缩 1,837,544，含 `-keep class com.google.mediapipe.**`）+ 约 35 KB（manifest/resources/对齐填充）。

> DEX 部分是**保守上界**：探针用了「保留全部 `com.google.mediapipe.**`」的规则。仓库启用 R8 时若不加 keep，JNI 反查的类名可能被重命名（AAR 未提供 consumer rules），因此这笔约 0.56 MB 应当留着当保险费。

### 3.5 AAB 的两个大小陷阱（实测）

探针工程 `bundleRelease`：

| 配置 | AAB |
|---|---:|
| 4 ABI（默认，含 x86/x86_64）+ debug symbols | 47,265,693 |
| 4 ABI + `debugSymbolLevel 'none'` | 32,031,573 |
| 2 ABI（`packaging.jniLibs.excludes` 掉 x86/x86_64）+ symbols | 20,906,682 |
| 2 ABI + `debugSymbolLevel 'none'` | **14,699,674** |

- **陷阱 1：AAB 不认 `splits.abi`**。`splits { abi { include "arm64-v8a","armeabi-v7a" } }` 只影响 APK；bundle 会把依赖里存在的 4 个 ABI 全部打进去。实测 4 ABI → 2 ABI 节省 **26,359,011 B**。修法（已实测可用，且不与 `splits` 冲突）：`packaging { jniLibs { excludes += ["lib/x86/**", "lib/x86_64/**"] } }`，放在 `buildTypes.release` 内以免影响模拟器调试。
- **陷阱 2：AAB 默认塞 native 调试符号**。`BUNDLE-METADATA/**/lib*.so.sym` 在 2 ABI 时多 **6,207,008 B**，4 ABI 时多 **15,234,120 B**（本仓库 v0.2.1 的 AAB 里已经有 `libraw_wrapper.so.sym`，说明这是当前默认行为）。GitHub 直发渠道用不到符号表，`release { ndk { debugSymbolLevel 'none' } }` 即可省下；若要给 Play 保留反混淆能力，应另存符号包而不是塞进 AAB。

另外：AAR 里那个 3.28 MB 的 `META-INF/NOTICE` **不会**进 APK/AAB（探针 APK 与 AAB 条目列表均无 NOTICE），无需额外 exclude。

### 3.6 传递依赖、编译期成本与遥测

- POM 传递依赖：`guava 27.0.1-android`、`protobuf-javalite 4.26.1`、`flogger 0.6`、`com.google.android.datatransport:{transport-api 3.0.0, transport-runtime 3.1.0, transport-backend-cct 3.1.0}`。R8 后 DEX 增量已包含在上表的 0.56 MB 内。
- **CI 成本**：每次干净构建要下载 `486,255 + 32,552,186 ≈ 33 MB` 的 AAR（外加约 5 MB 依赖 jar）。GitHub Actions 上属于可忽略的一次性成本，但本地首次 sync 会明显变慢，建议在 `gradle/actions/setup-gradle` 缓存里覆盖 Google Maven 坐标（现有 workflow 已使用 `setup-gradle`，无需改动）。
- **遥测**：`tasks-core` 内含 `RemoteLoggingClient`、`TasksStatsProtoLogger`，且 `TasksStatsLoggerFactory.create()` 的字节码**无条件**返回真实 logger（不是 `TasksStatsDummyLogger`）。POM 里那三个 `datatransport` 依赖就是它的上报通道。这意味着集成后可能存在任务使用统计的网络请求。**本报告未验证其上报内容、触发条件与关闭方式**（公开文档没有 opt-out API），需要集成后抓包确认；对处理私人照片的应用，这是上线前必须澄清的一项。

### 3.7 `minSdk 24` 与官方支持口径

- 两个 AAR 的 `AndroidManifest.xml` 都写死 `minSdkVersion="24"`，Gradle 合并不会阻止 `minSdk 24` 构建。
- 但官方 [Android setup 页](https://ai.google.dev/edge/mediapipe/solutions/setup_android) 明确写「Android SDK version 28 or higher (`minSdk = 28`)」，并把「不需要 GPU delegate 的任务可以用模拟器」作为前提。
- 差异的实际含义：API 24–27 上库能加载、CPU 路径可用性高，但属于**官方未承诺的组合**。本仓库 `minSdk 24` 若保持不变，必须把 API 24/26 真机（或 Firebase Test Lab 的低版本设备）纳入验收；否则应把 `minSdk` 提到 28（该改动的兼容性影响不在本工单范围，需单独评估）。

---

## 4. GPU Delegate：`minSdk 24` 上的兼容性与回退

### 4.1 硬性要求是 OpenGL ES 3.1，不是 3.0

官方 Framework GPU 文档原文：

> “MediaPipe supports OpenGL ES up to version 3.2 on Android/Linux … **OpenGL ES 3.1 or greater is required (on Android/Linux systems) for running machine learning inference calculators and graphs.**”
> — <https://ai.google.dev/edge/mediapipe/framework/framework_concepts/gpu>

Android 侧的版本可用性（<https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl>）：

| GLES 版本 | 起始 API |
|---|---|
| 3.0 | API 18 |
| **3.1** | **API 21** |
| 3.2 | API 24 |

同一页面还有一条关键警告：**「a device cannot support the OpenGL ES 3.0 API unless the device manufacturer provides an implementation」**——API level 只保证「有这个类」，不保证「有可用驱动」。因此 `minSdk 24` 上 GLES 3.1 是「API 可用」，不是「设备必有」。

### 4.2 代码里的真实行为

MediaPipe 创建 GL 上下文时先试 ES3、失败再退 ES2（`mediapipe/gpu/gl_context_egl.cc` 第 326–337 行）：

```cpp
auto status = CreateContextInternal(share_context, 3);
if (!status.ok()) {
  ABSL_LOG(WARNING) << "Creating a context with OpenGL ES 3 failed: " << status;
  ABSL_LOG(WARNING) << "Fall back on OpenGL ES 2.";
  status = CreateContextInternal(share_context, 2);
}
ABSL_RETURN_IF_ERROR(status);
```

注意这是**上下文创建**的回退，不是推理的回退：ES2 上下文能建出来，但 ML 推理 calculator 要求 3.1，图谱初始化仍会失败。`libmediapipe_tasks_jni.so` 的字符串里同时存在 `Creating a context with OpenGL ES 3 failed:` 与 `Could not create GLES`，说明失败路径是**抛错**而不是静默降级。

任务层没有兜底：`TaskRunner` 的构造 Javadoc 写的是「`@throws MediaPipeException` for any error during TaskRunner creation」，`BaseVisionTaskApi` 的 `createFromOptions` 路径里没有任何 `catch`/`try` 重建逻辑（源码：<https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/core/TaskRunner.java>）。所以：

**`Delegate.GPU` 初始化失败 = `createFromOptions` 抛 `MediaPipeException`，调用方必须自己 catch 并改用 `Delegate.CPU` 重建。**

### 4.3 GPU delegate 内部还会做两级回退

| 层级 | 行为 | 证据 |
|---|---|---|
| 后端 | OpenCL 上下文创建失败 → 退 OpenGL | `.so` 字符串 `Falling back to OpenGL:`、`Failed to create OpenCL context` |
| 算子 | GPU 不支持的算子拆给 CPU 执行（分区） | `.so` 字符串 `Following operations are not supported by GPU delegate:` |
| 图谱/任务 | **无自动回退**，直接抛异常 | §4.2 |

### 4.4 工程建议（可直接写进 spec）

1. **默认 CPU**。理由：与编辑器自有 GLES 3.0 管线不冲突；`minSdk 24` 设备里 GLES 3.1 覆盖不完整；人像分析是「进面板一次」而非逐帧。
2. **GPU 作为可选加速**，仅在 `ActivityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x00030001` 时尝试，且首次失败后把「本机不支持 GPU」写进内存/SharedPreferences，避免每次进面板都抛一次异常。
3. 用 `try { createFromOptions(GPU) } catch (e: MediaPipeException) { createFromOptions(CPU) }` 包住，日志用 Timber 记录 `e.statusCode`。
4. **只在有性能证据时开 GPU**：官方 Pixel 6 基准 multiclass 是 CPU 217.76 ms vs GPU 71.24 ms（3×），确实显著；但 `selfie_segmenter` 反而 CPU 更快（33.46 vs 35.15 ms），小模型开 GPU 得不偿失。
5. 与地图里的「GLES 3.0 + 自建 EGL/FBO」目标存在口径冲突：**着色器管线可以 3.0，MediaPipe GPU 推理不行**。若产品要求「GLES 3.0 设备也完整可用」，那 GPU delegate 只能是加分项，CPU 路径必须是验收基线。

---

## 5. 打包与分发方案对比

### 5.1 现状基线（GitHub Release v0.2.1 实测资产）

| 资产 | 字节 |
|---|---:|
| `app-arm64-v8a-release.apk` | 3,833,143 |
| `app-armeabi-v7a-release.apk` | 3,217,745 |
| `app-release.aab` | 5,951,188 |

AAB 内含 `base/lib/{arm64-v8a,armeabi-v7a}/libraw_wrapper.so`（deflate 存储）以及两份 `.sym`；APK 内 `.so` 为 Stored。发布链路（`.github/workflows/release.yml`）同时产出 AAB 与两个分 ABI APK，全部挂到同一个 draft release，**没有 Play 参与**。

### 5.2 方案 A：全部内置 `assets/`

实测（AGP 8.12 的 AAPT2 行为）：

| 文件 | assets 内 APK 存储 | 占 APK 字节 |
|---|---|---:|
| `face_landmarker.task` | Defl:N | 3,332,599 |
| `selfie_multiclass_256x256.tflite` | **Stored（未压缩）** | **16,371,837** |
| `deeplab_v3.tflite` | **Stored** | 2,780,176 |
| `selfie_segmenter.tflite` | **Stored** | 249,537 |

（`deeplab_v3.tflite` 一行按同一 `noCompress` 规则推断，未单独构建；其余三行均为探针实测。）

`.tflite` 不压缩不是巧合：**AGP 4.1 起 `.tflite` 被加入默认 `noCompress` 列表**（官方说明：<https://developers.google.com/edge/litert/android/metadata/lite_support>，原文 “starting from version 4.1 of the Android Gradle plugin, .tflite will be added to the noCompress list by default”）。所以内置 multiclass 的代价是**完整的 16.37 MB**，没有 7% 的 deflate 折扣可捡。官方示例工程还额外把 `task` 也列入 `noCompress`（[sample build.gradle.kts](https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/image_segmentation/android/category_mask/app/build.gradle.kts) 第 57 行 `noCompress += listOf("tflite", "task", "litertlm")`），若照做，face 的 3.33 MB 会变成 3.76 MB——本仓库没必要跟。

方案 A 的投影体积（实测增量相加）：

| | arm64 APK | v7a APK | AAB（2 ABI、无调试符号） |
|---|---:|---:|---:|
| runtime + face + multiclass | **40,106,792（38.25 MiB）** | 34,956,492 | ≈33,175,545 |
| runtime + face + selfie_seg | 23,984,471 | 18,834,171 | ≈18,243,695 |

### 5.3 方案 B：全部按需下载

| 项 | 字节 |
|---|---:|
| APK 增量（模型部分） | 0 |
| 首进人像面板下载（face + multiclass） | **20,130,433**（3,758,596 + 16,371,837） |
| 仅 multiclass | 16,371,837 |
| 磁盘常驻（`filesDir/models/<ver>/`） | 同上 |
| arm64 APK 投影 | **20,402,070（19.46 MiB）** |

代价从「所有用户付」变成「用人像的用户付」，但首次使用必须联网；GitHub Release 资产与 HuggingFace 都支持 `Range`（实测均返回 `206 Partial Content` + `accept-ranges: bytes`），可做断点续传。

### 5.4 方案 C：混合（推荐）

- **进 APK**：`tasks-core` runtime（不可避免）+ `face_landmarker.task`（3,332,599 deflate 后）+ `selfie_segmenter.tflite`（249,537）。
- **按需下载**：`selfie_multiclass_256x256.tflite`（16,371,837）。
- **不引入**：`deeplab_v3.tflite`、`pose_landmarker_full.task`（除非美体确认需要骨骼）。

理由：
1. 面部重塑（瘦脸/大眼/缩鼻/下巴）只依赖 landmarker，内置后可**离线、零等待**使用，这是人像面板最高频的入口能力。
2. 美肤/美体需要 6 类 mask，只有 multiclass 能给「皮肤 vs 头发 vs 衣服」的区分；`selfie_segmenter` 的 0.24 MB 让「背景虚化/人像提亮」在下载完成前也能用，避免空面板。
3. 16.37 MB 的下载对「GitHub 直发 APK」用户是可以接受的显式一次性成本，且有进度条与明确文案；把它塞进 APK 会让所有用户（包括从不修图的）多付 16.37 MB，APK 从 3.83 MB 涨到 40.11 MB，是 10.5 倍。
4. Play Asset Delivery 在 APK 渠道**不可用**（见 §5.6），双渠道并存时只有「自带下载器」这一条路能同时满足两个渠道，混合方案天然渠道无关。

投影结果：

| 场景 | arm64 APK | v7a APK | 首启下载 | 磁盘 |
|---|---:|---:|---:|---:|
| 现状（无人像） | 3,833,143 | 3,217,745 | 0 | 0 |
| **C：混合（推荐）** | **23,984,471（22.87 MiB）** | 18,834,171 | **16,371,837** | 16,371,837 |
| B：全按需 | 20,402,070 | 15,251,770 | 20,130,433 | 20,130,433 |
| A：全内置 | 40,106,792 | 34,956,492 | 0 | 0 |

### 5.5 决策矩阵

| 维度 | A 全内置 | B 全按需 | **C 混合（推荐）** |
|---|---|---|---|
| APK 体积（arm64） | +36.27 MB | +16.57 MB | **+20.15 MB** |
| 首次使用延迟 | 0 | 20.13 MB 下载 | 16.37 MB 下载（重塑立即可用） |
| 离线可用性 | 完整 | 无（新装） | 重塑可用，美肤需下载 |
| 服务器/带宽 | 无 | GitHub 资产或 HF（可 Range） | 同 B，仅 1 个文件 |
| 供应链/完整性 | APK 签名即可 | 需 SHA-256 + HTTPS 固定域名 | 同 B |
| 渠道一致性（APK/AAB） | 一致 | 一致 | 一致 |
| 用户可清理 | 否 | 是 | 是（仅 multiclass） |

### 5.6 为什么不能指望 Play Asset Delivery

`assetPacks` / Play Asset Delivery 只在「由 Play 安装」的包上生效；本仓库的 APK 由 GitHub Release 直接分发，用户侧没有 Play 的 asset pack 服务。若只为 AAB 渠道做 asset pack，就会出现「Play 版能修图、GitHub 版不能」的行为分裂。因此**双渠道并存时，模型分发必须渠道无关**：要么进 APK，要么由 App 自己下载。

### 5.7 `.cube` LUT 的处置

以构造样本测算一个 33³ LUT（35,937 行，6 位小数）的编码体积：

| 形式 | 字节 |
|---|---:|
| `.cube` ASCII（33³×3，6 位小数） | **970,420**（27 B/行） |
| 同数据 RGB16 二进制 | 215,622 |
| 同数据 RGB32F 二进制 | 431,244 |
| 同数据 RGBA16F（GPU 直接上传） | 287,496 |

- 内置滤镜**不要**用 LUT：上游 `docs/research/2026-11-21-image-editor-pipeline.md` §4.2 已明确「内置滤镜优先用参数预设」，可无损可微调且体积为 0。
- 社区 LUT 走 `ACTION_OPEN_DOCUMENT` 导入，用户自带文件，APK 不增重；导入后转成 RGBA16F 纹理（287,496 B/个）缓存到 `filesDir`，不要每次解析 0.97 MB 文本。
- 若确实要随包带几个「风格 LUT」，用 16-bit 二进制（215,622 B/个）而不是 `.cube`，并放在 `assets/` 外的按需目录，避免主 APK 膨胀。

### 5.8 下载器规格（若采用 B/C）

| 要素 | 要求 | 依据 |
|---|---|---|
| 地址 | 首选 GitHub Release 资产（与 APK 同源），次选 `storage.googleapis.com/mediapipe-models/.../v1/...` 或 HF `litert-community` | 三者内容 SHA-256 相同（§2.7）；GitHub 与 HF 均支持 Range |
| 校验 | 硬编码 SHA-256（本文 §2.1 表），下载后校验，失败删除重试 | 模型 2023 年起未变，可长期固定 |
| 写入 | 下载到 `*.part` → 校验 → `rename` 原子替换 | 避免半包导致的推理崩溃 |
| 版本目录 | `filesDir/models/<model>-<sha256前8位>/`，升级时并存旧版 | 允许灰度回滚，避免升级瞬间下载失败 |
| 加载 | `BaseOptions.setModelAssetFileDescriptor(fd)` 或 `setModelAssetBuffer(mappedBuffer)`；**不能**用 `setModelAssetPath`（只读 assets） | BaseOptions Javadoc 实测原文：「Sets the native fd int of a model asset file」「Sets either the direct ByteBuffer or the MappedByteBuffer」 |
| 并发 | 单飞（同一模型只允许一个进行中的下载），WorkManager 或自建队列 | 避免重复 16 MB 流量 |
| 失败策略 | 指数退避 + 上限次数；超限后提示「稍后重试」，并回退到已内置的 `selfie_segmenter` | — |
| 磁盘 | 下载前检查 `StatFs` 可用空间 ≥ 2× 文件大小 | 16 MB 模型 + 临时 `.part` |
| 隐私 | 只请求模型文件，不带任何用户标识/图片；不做第三方统计 | 与「全程端上」的 PIPL 口径一致 |

## 6. 推荐决策（可执行清单）

1. `:app` 只加一个坐标：`implementation("com.google.mediapipe:tasks-vision:1.2.3")`，`tasks-core` 传递引入；不要单独依赖 `tasks-core`（版本会漂移）。
2. release 构建加两条体积护栏（都已实测有效）：
   - `packaging { jniLibs { excludes += ["lib/x86/**", "lib/x86_64/**"] } }`（AAB −26.36 MB）；
   - `ndk { debugSymbolLevel 'none' }`（AAB −6.21 MB @2 ABI）。
3. `assets/` 只放 `face_landmarker.task` + `selfie_segmenter.tflite`（+3,582,401 B）。
4. `selfie_multiclass_256x256.tflite` 走首进面板按需下载（16,371,837 B），SHA-256 固定为 `c6748b12…e0e0`。
5. `deeplab_v3.tflite` 与 `pose_landmarker_full.task` v1 不引入；若后续「美体」需要骨骼，再按需加 pose（8.96 MiB）。
6. 推理默认 CPU；GPU 仅在 `reqGlEsVersion >= 0x30001` 时尝试，失败捕获 `MediaPipeException` 重建 CPU 并记忆降级。
7. 给 R8 加 `-keep class com.google.mediapipe.** { *; }`（AAR 无 consumer rules），并把「真机 API 24/26 验证」列为发布门禁。
8. 上线前抓包确认 MediaPipe 遥测（`RemoteLoggingClient` + DataTransport）是否发出请求；若不可关闭，需在隐私政策中披露，或评估降级到不含遥测的旧版本（需另行核实）。

## 7. 未验证项与风险

| 项 | 状态 | 影响 |
|---|---|---|
| 仓库真实构建产物体积 | **投影**（探针增量 + v0.2.1 基线相加），未改动仓库 `app/build.gradle` | 误差预期 < 0.5 MB（R8 对 app 代码的裁剪） |
| API 24/26 设备运行 | 未验证（无设备） | 官方口径是 minSdk 28，24 属未承诺组合 |
| R8 无 keep 规则时的运行时行为 | 未验证（未做真机 Instrumentation） | 可能出现 `UnsatisfiedLinkError`/类找不到 |
| 遥测是否真的上传、能否关闭 | 未验证 | 隐私合规风险 |
| `.cube` 压缩率 | 合成样本 deflate 仅 9.7%，不代表真实 LUT | 结论不依赖压缩率（推荐不内置） |
| `useLegacyPackaging=true` 的收益 | 仅按 gzip 估算（可省约 8.7 MB） | 会改变安装/启动特征，未实测，不建议 |

## 附录 A：复现命令

```bash
# 1) 模型字节数与哈希（GCS 与 HF 双源对比）
for u in \
  "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task" \
  "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite" \
  "https://storage.googleapis.com/mediapipe-models/image_segmenter/deeplab_v3/float32/1/deeplab_v3.tflite" \
  "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/1/selfie_segmenter.tflite"; do
  curl -sSIL "$u" | grep -i '^content-length'; curl -sSLO "$u"; sha256sum "$(basename "$u")"
done

# 2) 桶列举（证明没有 int8 变体）
curl -sS "https://storage.googleapis.com/mediapipe-models?prefix=face_landmarker/&max-keys=100"

# 3) AAR 解剖
curl -sSLO https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/1.2.3/tasks-vision-1.2.3.aar
curl -sSLO https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-core/1.2.3/tasks-core-1.2.3.aar
unzip -l tasks-vision-1.2.3.aar; unzip -l tasks-core-1.2.3.aar

# 4) APK 的 .so 是否未压缩
aapt2 dump xmltree app-arm64-v8a-release.apk --file AndroidManifest.xml | grep extractNativeLibs
unzip -lv app-arm64-v8a-release.apk | grep '\.so'

# 5) 张量规格（pip install tflite flatbuffers；.task 先按 zip 解出 .tflite）
python3 - <<'PY'
import zipfile
from tflite.Model import Model
z = zipfile.ZipFile("face_landmarker.task")
for n in [n for n in z.namelist() if n.endswith(".tflite")]:
    sg = Model.GetRootAsModel(z.read(n), 0).Subgraphs(0)
    print(n, [(sg.Tensors(sg.Inputs(i)).Name(),
               [sg.Tensors(sg.Inputs(i)).Shape(k) for k in range(sg.Tensors(sg.Inputs(i)).ShapeLength())])
              for i in range(sg.InputsLength())])
PY
```

## 附录 B：一手来源

| 主题 | 链接 |
|---|---|
| Image Segmenter 模型表/量化/基准 | https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter/android#models |
| Face Landmarker 模型包/输出定义 | https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android#models |
| MediaPipe GPU 需 GLES 3.1 | https://ai.google.dev/edge/mediapipe/framework/framework_concepts/gpu |
| Android GLES 版本与驱动警告 | https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl |
| Android setup（minSdk 28 建议） | https://ai.google.dev/edge/mediapipe/solutions/setup_android |
| Google Maven 元数据（1.2.3 = latest） | https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/maven-metadata.xml |
| `tasks-vision` AAR / POM | https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/1.2.3/tasks-vision-1.2.3.aar |
| `tasks-core` AAR / POM | https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-core/1.2.3/tasks-core-1.2.3.aar |
| GL 上下文 ES3→ES2 回退 | https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/gpu/gl_context_egl.cc#L326-L337 |
| `BaseOptions` fd/buffer 语义 | https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/core/BaseOptions.java |
| `TaskRunner` 创建抛 `MediaPipeException` | https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/core/TaskRunner.java |
| `.tflite` 自 AGP 4.1 默认 noCompress | https://developers.google.com/edge/litert/android/metadata/lite_support |
| 官方 sample 的 noCompress 列表 | https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/image_segmentation/android/category_mask/app/build.gradle.kts |
| Model Card：Face Mesh V2 | https://huggingface.co/litert-community/MediaPipe-FaceLandmarker/resolve/main/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf |
| Model Card：Blendshape V2 | https://huggingface.co/litert-community/MediaPipe-FaceLandmarker/resolve/main/Model%20Card%20Blendshape%20V2.pdf |
| Model Card：BlazeFace Short Range | https://huggingface.co/litert-community/MediaPipe-FaceLandmarker/resolve/main/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf |
| Model Card：Multiclass Segmentation | https://huggingface.co/litert-community/MediaPipe-ImageSegmenter-Selfie-Multiclass/resolve/main/Model%20Card%20Multiclass%20Segmentation.pdf |
| Model Card：Selfie Segmentation | https://huggingface.co/litert-community/MediaPipe-ImageSegmenter-Selfie-Square/resolve/main/Model%20Card%20MediaPipe%20Selfie%20Segmentation.pdf |
| HF 许可证元数据 | https://huggingface.co/api/models/litert-community/MediaPipe-ImageSegmenter-Selfie-Multiclass |
| 模型文件镜像（与 GCS 同字节） | https://huggingface.co/litert-community/MediaPipe-FaceLandmarker |
