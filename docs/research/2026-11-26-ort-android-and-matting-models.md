# ONNX Runtime Android 运行时体积、硬件加速与通用抠图/发丝模型事实调研

- 日期：2026-11-26
- 关联工单：ONNX Runtime Android 运行时体积、硬件加速与通用抠图/发丝模型事实调研
- 上游基线：`docs/research/2026-11-22-mediapipe-model-distribution.md`（MediaPipe 运行时/模型口径，用于横向对比）
- 被测版本：`com.microsoft.onnxruntime:onnxruntime-android:1.32.0`（Maven Central `latest`，元数据 `lastUpdated=20261113101826`）；`onnxruntime-mobile:1.18.0`（最后一个 Full/Minimal 同版本对照）；`onnxruntime-android-qnn:1.29.0`
- 实测环境：Linux x86_64（12 核 / 31 GB）、Gradle 8.13、AGP 8.12.0、compileSdk 36、minSdk 24、JDK 17、R8（`proguard-android-optimize.txt`）、Python 3.14 + onnx 1.23.2 + onnxruntime 1.32.0

> 本文的字节数分三类，正文逐条标注：**实测**（本机真实构建/下载/推理）、**原文**（官方文档/许可证/Model Card 的原话）、**投影**（由实测比例外推，未直接验证）。
>
> 重要背景：`github.com/microsoft/onnxruntime` 自 **2026-11-17 约 22:20 UTC** 起返回 404（Microsoft 对 The Register 的回应是“为调查恶意内容将部分仓库转为私有”），截至本文写作（2026-11-26）仍未恢复。因此本文的 C++ 侧证据全部取自 **Maven 发布的二进制与 sources jar**（与线上产物同字节），而不是 GitHub 源码页；需要引用源码行的位置会显式说明。

---

## 0. 结论摘要

1. **运行时体积（实测）**：`onnxruntime-android:1.32.0` 的 AAR 为 **59,335,382 B**，其中 `arm64-v8a/libonnxruntime.so` = **36,307,784 B**、`libonnxruntime4j_jni.so` = 122,192 B。探针工程（空壳 + R8 + `splits.abi`）实测 APK 增量：**arm64-v8a +36,484,963 B（+34.79 MiB）**，**armeabi-v7a +25,935,411 B（+24.73 MiB）**。作为对照，MediaPipe `tasks-core:1.2.3` 是 +15.80 MiB，**ORT 的运行时是它的 2.2 倍**。
2. **AAB 三个陷阱全部复现（实测）**：4 ABI + 调试符号 = **118,002,187 B**；4 ABI 去符号 = **59,035,901 B**；2 ABI + 符号 = **52,618,659 B**；2 ABI 去符号 = **26,348,869 B**。`splits.abi { include arm64, v7a }` 对 AAB **无效**（实测 bundle 里仍有 x86/x86_64 的 4 个 `.so`），只有 `packaging.jniLibs.excludes` 能裁；`ndk.debugSymbolLevel 'none'` 在 4 ABI 下省 **58.97 MB**、2 ABI 下省 **26.27 MB**。
3. **Full vs Minimal（实测对照，1.18.0 同版本）**：`onnxruntime-android` 的 arm64 `libonnxruntime.so` = 16,276,832 B；`onnxruntime-mobile`（官方 reduced-op、ORT-format only）= **3,735,912 B，仅 22.95%**。按此比例投影到 1.32.0，minimal 版 arm64 `.so` ≈ **8.3 MB**（投影）。代价是：模型必须转成 `.ort`、基本版 minimal build 不支持 NNAPI/CoreML，NNAPI 需要 `--minimal_build extended`；官方最后一次发布 minimal AAR 是 **1.18.0**（2024-05），此后只能自己构建。
4. **R8 是硬性要求**：官方 Android 构建文档原文要求 `-keep class ai.onnxruntime.** { *; }`，否则 release 构建运行时崩溃；AAR 自带的 `proguard.txt` 只有 5 条 telemetry keep 规则（实测 293 B），不覆盖 API。加上 keep 后 DEX 增量实测 135,972 B（deflate 后 **56,016 B**）。
5. **minSdk 24 成立**：AAR 的 `AndroidManifest.xml` 写 `minSdkVersion=24`；`libonnxruntime.so` 的 `.note.android.ident` 记录 **API level = 24**、NDK **r28c**（实测 `readelf -n`）。但 NNAPI EP 需要 **API 27+**，所以 API 24–26 只能用 CPU / XNNPACK。
6. **隐私/权限（新发现，本仓库必须处理）**：1.32.0 的 AAR 会向宿主 App 合并 **`INTERNET` + `ACCESS_NETWORK_STATE`** 权限和一个 `android:initOrder="100"` 的 **`ai.onnxruntime.TelemetryInitializer` ContentProvider**（authority = `${applicationId}.onnxruntime_telemetry_initializer`）。通信端点在 `.so` 里硬编码为 `https://mobile.events.data.microsoft.com/OneCollector/1.0`；Java 侧会读取 `ANDROID_ID`、包名/版本、系统版本、语言、时区、设备类别。Provider 在 `Application.onCreate` **之前**就 `System.loadLibrary("onnxruntime")`，所以 `OrtEnvironment.setTelemetry(false)` 一定晚于初始化；真正的早期关闭手段是 `tools:node="remove"` 掉该 Provider（移除后原生侧只会打印 “Android telemetry is unavailable because the 1DS Java HttpClient was not initialized”）。
7. **EP 边界**：CPU（永远可用）；XNNPACK（预编译 AAR 内含，文档化算子只有 Conv/ConvTranspose/Pool/Resize(bilinear)/Softmax/Gemm/MatMul 等十余种）；NNAPI（**API 27+，Android 15 已废弃**，32 个 builder（含 2 个基础设施类），运行时会把不支持的节点切回 CPU EP，`CPU_DISABLED` 标志在设备只有 CPU 实现时会让 **模型加载失败**）；QNN（独立 AAR，**只有 arm64、只到 1.29.0**，POM 依赖 `com.qualcomm.qti:qnn-runtime:2.42.0`——**Qualcomm AI Hub Model License，非开源**，该 AAR 63,074,692 B 且含 83.7 MB 的 `libQnnHtpPrepare.so`）。对“严格 MIT/Apache-2.0 可商用”的约束，**QNN 路径直接出局**。
8. **模型许可证是本次最硬的结论**：
   - **BiRefNet-lite**：代码与权重均 **MIT**（`ZhengPeng7/BiRefNet/LICENSE`、HF card `license: mit`），可商用。
   - **MODNet**：README 原文「The code, **models**, and demos in this repository … are released under the **Apache License 2.0**」，可商用（但仅人像）。
   - **ISNet-general-use**：**许可证链条不成立**。DIS 仓库只声明「code and evaluation metric use Apache License 2.0」，训练集 **DIS5K 的 Terms of Use 明确“non-commercial use in research or educational purpose”且禁止再分发**；ONNX 镜像的声明互相矛盾——`onnx-community/ISNet-ONNX` 标 **AGPL-3.0**，`imgly/isnet-general-onnx` 标 **MIT**，rembg 的 release 资产**没有任何 weights 许可证**，且 rembg README 自己写明「Model weights carry their own licenses … check the linked source before using any model commercially」。在“严格可商用”口径下 **ISNet 应判为不可用**。
9. **模型文件（实测字节 + 张量）**：
   - BiRefNet-lite：FP32 **224,005,088 B**（55.33 M 参数）、FP16 **114,538,221 B**；**官方没有 INT8**，**2K 变体没有官方 ONNX**（`ZhengPeng7/BiRefNet_lite-2K` 只有 safetensors，输入 2560×1440）。ONNX 输入 `input_image [1,3,1024,1024]` 静态，输出 `output_image [1,1,1024,1024]`，opset 17，**16,400 个节点**，含 `GatherND`/`ScatterND`/`Where`/`Mod`/`ConstantOfShape`（deformable conv 的采样网格被导出成 7,296 个 `Constant`，最大单个 25.7 MB）。
   - ISNet：FP32 **176,114,856 B**（44.00 M）、FP16 88,116,798 B、INT8 44,313,923 B；输入 `input [1,3,1024,1024]` 静态；opset 15；355 节点，算子只有 `Conv/Relu/Concat/Resize/MaxPool/Add/Sigmoid`——**恰好全部落在 NNAPI 支持列表内**。但它的 INT8 是**动态量化**（`DynamicQuantizeLinear` + `ConvInteger`），实测比 FP32 **慢约 25 倍**。
   - MODNet：FP32 **25,888,640 B**（6.46 M）、FP16 12,984,781 B、UINT8（动态量化）6,632,188 B；输入 `input [?,3,?,?]` **动态 shape**；opset 11；含 `InstanceNormalization` 与 `Expand`（NNAPI 不支持）。
10. **实测内存把“端侧可行”直接分层（x86_64 CPU、4 线程、ORT 1.32）**：BiRefNet-lite@1024 峰值 RSS **12.0 GB**（关掉 CPU arena 后 **6.8 GB**），单次 6.5–8.5 s；ISNet@1024 峰值 **1.23 GB**、0.82–0.94 s；MODNet@512 峰值 **334 MB**、0.137 s。**BiRefNet-lite 在手机上不是“慢”，而是“跑不起来”**——它需要数 GB 激活值，根源是 1024² 特征图 + 16k 节点的图。
11. **仓库现状决定了精修必须分块**：当前人像 mask 是 **256×256 的 `GL_R8`（8 bit）**（`SkinMaskMath.MASK_SIZE=256`、`SkinMaskTexture.upload`），预览最长边 2048 px、bake 分块 1920×512 + 64 px halo。24MP 时一个 mask texel 覆盖 **23.4 px**，60MP 时 **37.1 px**；而人像里单根发丝通常只有 0.5–2 px —— **模型 mask 无论怎么上采样都不可能包含发丝**，必须在全分辨率上用图像做引导精修。
12. **`GuidedFilter.smooth` 无法整帧跑**：按现有实现，`smooth()` 峰值同时存活 **≥12 个 FloatArray（48 B/px）**——24MP 需 **1.15 GB**、60MP 需 **2.89 GB**；而单个 bake band（2048×640 = 1.31 MP）只需 **63 MB**。结论：**把 guided filter 放进现有 band 管线（CPU 或 GLSL）**，而不是整帧 CPU。
13. **去色溢首选无模型的 Fast Multi-Level Foreground Estimation**（Germer et al., arXiv:2006.14970）：每像素每轮只解一个 2×2 线性系统，**O(n)/像素**、常数极小；参数原文为 `εr = 5e-3`、`ω = 0.1`，金字塔每级 ≤2×，≤32 px 时 10 轮、其余 2 轮。整帧 24MP 需要约 **10 floats/px ≈ 960 MB**，因此同样要“低分辨率求 F/B + 分块全分辨率细化”或只在 alpha 过渡带跑。

---

## 1. 测量环境与复现方法

| 项 | 值 | 证据 |
|---|---|---|
| AGP / Gradle | 8.12.0 / 8.13 | 探针工程与仓库 `gradle/libs.versions.toml` 同版本 |
| 探针工程 | 空白 `Application`（无代码）+ `implementation "com.microsoft.onnxruntime:onnxruntime-android:1.32.0"`，`minifyEnabled true`，`splits.abi` 只留 arm64-v8a / armeabi-v7a，`proguard-rules.pro` 含官方要求的一条 keep | `/tmp/opencode/ort/probe`（临时目录，未写入仓库） |
| 基线 APK | **5,331 B**（空壳、R8、签名） | 实测 |
| AAR / 模型 | Maven Central、HuggingFace 直接下载后 `sha256sum` | 见附录 A |
| 推理基准 | 本机 x86_64、onnxruntime 1.32.0 CPU EP、4 intra-op 线程、随机输入 | `/tmp/opencode/ort/bench.py` |
| Android 真机 | **无**（`adb devices` 为空） | 端侧延迟/内存均为未验证项，见 §7 |

复现命令见附录 A。

---

## 2. ONNX Runtime Android 集成代价

### 2.1 版本与产物

`https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml`：`<release>1.32.0</release>`，`lastUpdated=20261113101826`。同组下另有：

| artifact | 最新版 | 说明 |
|---|---|---|
| `onnxruntime-android` | **1.32.0** | 全量算子 + CPU/XNNPACK/NNAPI（`.so` 里还注册了 QNN EP），4 个 ABI |
| `onnxruntime-android-qnn` | **1.29.0**（2026-08-12） | QNN 版本**落后主包 3 个小版本**，只有 arm64 |
| `onnxruntime-mobile` | **1.18.0**（2024-05-20，已停更） | 官方 minimal/reduced-op 版，仅 ORT format |
| `onnxruntime-training-android` | 1.32.0 | 训练用，不在本工单范围 |

> 供应链提示：`onnxruntime-android-qnn` 停在 1.29.0 而主包已到 1.32.0，意味着“要用 QNN 就得整体降级到 1.29.0”，或自己从源码构建（而源码仓库当前 404）。这条约束在选型时应先确认。

### 2.2 AAR 解剖（实测）

```
59,335,382 B  onnxruntime-android-1.32.0.aar
   sha256 8e8dbb412ed4939675a157702f0c1e3621b436a645af7f7244536ac20bfad628
  jni/arm64-v8a/libonnxruntime.so        36,307,784 B
  jni/arm64-v8a/libonnxruntime4j_jni.so     122,192 B
  jni/armeabi-v7a/libonnxruntime.so      25,791,836 B
  jni/armeabi-v7a/libonnxruntime4j_jni.so    89,588 B
  jni/x86/libonnxruntime.so              43,328,156 B
  jni/x86_64/libonnxruntime.so           44,970,184 B
  classes.jar                               149,401 B
  headers/*.h                              ~1.1 MB（不进 APK）
  proguard.txt                                  293 B
  AndroidManifest.xml                           623 B
```

`readelf -d jni/arm64-v8a/libonnxruntime.so` 的 `NEEDED` 只有 `libdl/liblog/libandroid/libm/libc`——**libc++ 是静态链入的**，不会与 `:libraw` 已有的 `libc++_shared.so` 冲突，这也是它体积大的原因之一。

`strings libonnxruntime.so` 能直接列出注册的 EP：`CPUExecutionProvider`、`XnnpackExecutionProvider`、`NnapiExecutionProvider`、`QNNExecutionProvider`（另有 CoreML 的编译期字符串，Android 上不可用）。

### 2.3 APK 实测增量

| 配置 | arm64-v8a APK | armeabi-v7a APK |
|---|---:|---:|
| 空壳基线 | 5,331 | ≈5,331（同一构建，未单独测 v7a） |
| + `onnxruntime-android:1.32.0` | **36,490,294** | **25,940,742** |
| **增量** | **+36,484,963（+34.79 MiB）** | **+25,935,411（+24.73 MiB）** |

APK 内条目（arm64）：

```
36307776  Stored  0%  lib/arm64-v8a/libonnxruntime.so
   91808  Stored  0%  lib/arm64-v8a/libonnxruntime4j_jni.so
  135972  Defl:N 59%  classes.dex        (deflate 后 56,016)
```

即 `.so` 仍是 `extractNativeLibs=false` 的**未压缩存储**，deflate 帮不上忙；DEX 在保留 `ai.onnxruntime.**` 的情况下只有 56 KB（deflate 后），可忽略。

横向对比：MediaPipe `tasks-vision:1.2.3` 的 arm64 增量是 **+16.57 MB**（`docs/research/2026-11-22-mediapipe-model-distribution.md` §3.4）。**同样是“带一个推理运行时”，ORT 的固定成本是 MediaPipe 的 2.2 倍。**

### 2.4 AAB 实测（三个陷阱）

| 配置 | AAB 字节 | 备注 |
|---|---:|---|
| 4 ABI（默认）+ 调试符号 | **118,002,187** | `base/lib` 内 4 个 ABI × 2 个 `.so`，另有 4 份 `.sym` |
| 4 ABI，`debugSymbolLevel 'none'` | **59,035,901** | 省 58,966,286 B |
| 2 ABI（`packaging.jniLibs.excludes` 掉 x86/x86_64）+ 符号 | **52,618,659** | 省 65,383,528 B |
| 2 ABI + 无符号 | **26,348,869** | 相对 4 ABI+符号省 **91,653,318 B** |

- **陷阱 1 复现**：把 `splits.abi` 打开（include 只有 arm64-v8a / armeabi-v7a）后再 `bundleRelease`，AAB 里**依然有 x86 与 x86_64**（实测 `base/lib/` 下 8 个条目 = 4 ABI × 2 个 `.so`）。裁 ABI 只能靠 `packaging { jniLibs { excludes += ["lib/x86/**", "lib/x86_64/**"] } }`。
- **陷阱 2 复现**：`.sym` 与 `.so` 几乎同尺寸（arm64 `libonnxruntime.so.sym` = 36,307,776 B），压缩后仍占 26.27 MB（2 ABI）。GitHub 直发渠道应设 `ndk { debugSymbolLevel 'none' }`。
- **陷阱 3**：`shrinkResources true` 与 `splits` 同时开启时 `bundleRelease` 会直接失败（`Multiple shrunk-resources files found`，AGP issue 402800800）。若两个渠道共用一份构建脚本，需要按任务类型切换。

### 2.5 Full AAR vs Minimal / Custom Ops（实测 + 原文）

官方没有发布 1.32 的 minimal AAR，但 **1.18.0 同时发布了 full 与 mobile 两个 AAR**，可以直接量出“reduced ops + ORT format”能省多少：

| 1.18.0（同一天构建） | arm64 `libonnxruntime.so` | 整个 AAR |
|---|---:|---:|
| `onnxruntime-android`（full） | 16,276,832 | 24,415,212 |
| `onnxruntime-mobile`（minimal） | **3,735,912** | **7,241,017** |
| 比例 | **22.95%** | 29.7% |

`onnxruntime-mobile` 的 POM 原文：「built … with reduced disk footprint … supports a reduced set of operators and types … **The ONNX model must be converted to ORT format**」。

投影到 1.32.0：arm64 `.so` ≈ 36.3 MB × 0.2295 ≈ **8.3 MB**，即 **APK 增量从 +34.79 MiB 降到约 +8 MiB**（投影）。但这笔钱有四个前提（原文，<https://onnxruntime.ai/docs/build/custom.html>）：

| 选项 | 作用 | 代价 |
|---|---|---|
| `--include_ops_by_config <ops.config>` | 只编译模型用到的算子 | 换模型要重编；`--update` 阶段会改源码（1.11+ 改为生成到 build 目录） |
| `--enable_reduced_operator_type_support` | 再裁掉算子不需要的 dtype | 需要 ORT-format 转换时开 `--enable_type_reduction` |
| `--minimal_build` | 关 RTTI、去 runtime 优化 | **不支持 ONNX 格式**（必须 `.ort`）；只支持静态注册 kernel 的 EP |
| `--minimal_build extended` | 允许 NNAPI / CoreML 这类编译型 EP 做有限分区 | 体积比 basic 大 |
| `--disable_ml_ops` / `--disable_exceptions` / `--disable_rtti` / `--android_cpp_shared` / `--config MinSizeRel` | 进一步减重 | `--disable_exceptions` 下出错直接 `abort()` |

**实操可行性已验证**：用 `onnxruntime.tools.convert_onnx_models_to_ort --optimization_style Fixed --target_platform arm` 把 MODNet 转成 `.ort` 成功（25,888,640 B → 26,021,640 B，0.3 s），并自动生成 `required_operators.config`：

```
ai.onnx;1;GlobalAveragePool,Shape
ai.onnx;5;Reshape
ai.onnx;6;InstanceNormalization,Relu,Sigmoid
ai.onnx;7;Add,Mul
ai.onnx;8;Expand
ai.onnx;9;BatchNormalization,MatMul
ai.onnx;11;Concat,Conv,Resize,Slice
com.microsoft;1;FusedConv
```

这份 config 就是 `--include_ops_by_config` 的输入；注意 ORT 转换器会把 `Conv+BN+Relu` 融合成 **contrib 算子 `com.microsoft:FusedConv`**，minimal build 必须把它一起编进去。

> 结论：**若最终选 ORT，minimal build 是唯一能把运行时压到 10 MB 量级的路**，但它把“换模型”变成“重编 native 库 + 转 ORT format”，与“模型按需下载”的产品策略冲突（minimal build 加载的是 `.ort`，按需下载的也必须预先是 `.ort`）。

### 2.6 R8 / ProGuard

官方 Android 构建文档原文（<https://onnxruntime.ai/docs/build/android.html>）：

> “For Android consumers using the library with R8-minimized builds, currently you need to add the following line to your proguard-rules.pro file … to avoid runtime crashes: `-keep class ai.onnxruntime.** { *; }`”

AAR 自带的 `proguard.txt` 实测全文只有 5 条，只保 telemetry 的 JNI 回调类：

```
-keep class ai.onnxruntime.TelemetryInitializer { *; }
-keep class ai.onnxruntime.telemetry.HttpClient { *; }
-keep class ai.onnxruntime.telemetry.HttpClient$* { *; }
-keep class ai.onnxruntime.telemetry.HttpClientRequest { *; }
-keep class ai.onnxruntime.telemetry.HttpClientRequest$* { *; }
```

也就是说：**AAR 不提供 API 的 consumer rules，App 必须自己写 keep**。代价实测可忽略（DEX +56 KB deflate），但漏写的后果是 release 包崩溃、debug 包正常，属于典型“只在 CI 产物上炸”的问题。

### 2.7 minSdk 24

| 证据 | 内容 |
|---|---|
| AAR `AndroidManifest.xml` | `<uses-sdk android:minSdkVersion="24" />` |
| `libonnxruntime.so` 的 `.note.android.ident` | 首 4 字节 `18 00 00 00` = **API level 24**，NDK 版本串 `r28c`、build `13676358`（`readelf -n` 实测） |
| 官方构建文档 | `--android_api <level>` 示例与默认值写作 **27**（这是 NDK 编译 target，不是最低运行版本） |
| NNAPI | API **27** 起才有（见 §3.3） |

结论：**minSdk 24 可以集成**（manifest 与 `.so` 双双声明 24），但 API 24–26 的设备上只能用 CPU / XNNPACK；任何 NNAPI 代码路径都必须先判 `Build.VERSION.SDK_INT >= 27`。

### 2.8 遥测、权限与 provider（本仓库必须处理）

AAR 合并进宿主 App 的 manifest（探针 APK 用 `aapt2 dump xmltree` 实测）：

```
uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"
uses-permission android:name="android.permission.INTERNET"
application android:extractNativeLibs="false"
  provider android:name="ai.onnxruntime.TelemetryInitializer"
           android:authorities="${applicationId}.onnxruntime_telemetry_initializer"
           android:exported="false" android:initOrder="100"
```

`ai/onnxruntime/TelemetryInitializer.java`（Maven sources jar，与产物同版本）原文：

```java
public boolean onCreate() {
  ...
  System.loadLibrary("onnxruntime");           // ← 在 Application.onCreate 之前
  synchronized (TelemetryInitializer.class) {
    if (client == null) client = new HttpClient(context.getApplicationContext());
  }
```

`HttpClient` 会采集：`Settings.Secure.ANDROID_ID`（由原生侧 `isDeviceIdCollectionEnabled()` 决定是否启用）、`getPackageName()`、`versionName`、`Build.VERSION.RELEASE/INCREMENTAL`、locale、时区、`MANUFACTURER/MODEL`、设备类别（Phone/PC）。`.so` 内的 1DS 上报地址：

```
https://mobile.events.data.microsoft.com/OneCollector/1.0
```

控制手段与它们的**时序缺陷**：

| 手段 | 时机 | 有效性 |
|---|---|---|
| `OrtEnvironment.setTelemetry(false)` | App 代码，最早 `Application.onCreate` | **晚于** provider 的 `System.loadLibrary`；官方 PR 自述“可能已发出最小初始化事件” |
| 环境变量 `ORT_DISABLE_TELEMETRY=1` | 进程启动前，原生初始化时读取一次并锁定 | Android 上普通 App 无法在 provider 之前设置进程环境变量 |
| `tools:node="remove"` 移除 `ai.onnxruntime.TelemetryInitializer` | manifest 合并期 | **唯一可靠的早期关闭**：Java 传输层不会初始化，原生侧打印 “Android telemetry is unavailable because the 1DS Java HttpClient was not initialized” 后不再上传 |
| 移除 `INTERNET` 权限 | manifest 合并期 | 若 App 本身需要联网（模型下载）则不可行；但可与上一条叠加，只删 `ACCESS_NETWORK_STATE` |

对 PhotoSync 这种“处理私人照片、宣称全程端上”的产品，**建议在集成时就 `tools:node="remove"` 掉该 provider 并写进 ADR**；否则上线前的隐私披露里必须出现“Microsoft 1DS”。这条不是理论风险：AAR 里 `INTERNET` 权限是**自动合并**的，不写任何代码也会出现。

---

## 3. 硬件加速边界

### 3.1 CPU EP

- 永远可用；arm64 走 MLAS 的 NEON/SDOT kernel（运行时按 CPU 特性分派），armv7a 走 NEON。
- 线程：`SessionOptions.setIntraOpNumThreads(n)`；实测 4 线程在本机 x86 上已能把 ISNet 跑到 0.82 s。
- 内存：见 §4.6，**CPU EP 的峰值内存是选型的决定性因素**，比延迟更重要。

### 3.2 XNNPACK EP

原文（<https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html>）：预编译 AAR 已包含；需要显式 `addXnnpack(...)` 注册（Java：`sessionOptions.addXnnpack(Map)`）。文档化支持的算子只有：

| 算子 | 备注 |
|---|---|
| `AveragePool` / `MaxPool` | 仅 2D |
| `Conv` / `ConvTranspose` | 仅 2D，权重必须常量（ConvTranspose since 1.14） |
| `Resize` | 2D/4D，**仅 bilinear**（since 1.14） |
| `Softmax` | opset <13 全支持；13 仅最后一维 |
| `Gemm` / `MatMul` | 仅 2D（since 1.14） |
| `QLinearConv` / `QLinearAveragePool` / `QLinearSoftmax` / `QLinearConvTranspose` | 量化 |

官方推荐配置（原文）：`sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0")` + XNNPACK 线程数 = 物理核数 + ORT intra-op = 1。对“Conv 为主、后处理是 elementwise”的分割模型，这意味着 **Conv 段可能被 XNNPACK 接走、其余落回 CPU**，需要实测收益，不能假设“开了就快”。

API 级别：XNNPACK 是纯 CPU 库，无 Android API 版本要求（跟随 minSdk 24 即可）。

### 3.3 NNAPI EP

**API 要求（原文）**：「The NNAPI Execution Provider (EP) requires Android devices with Android **8.1 or higher**. It is recommended to use Android devices with Android 9 or higher」。

**已被 Google 判死刑（原文，<https://developer.android.com/ndk/guides/neuralnetworks>）**：

> “**Warning: NNAPI is deprecated.** While you can continue to use NNAPI, we expect the majority of devices in the future to use the CPU backend, and therefore for performance critical workloads, we recommend migrating to alternative solutions…”
> “The API is available on all Android devices running Android 8.1 (API level 27) or higher, but **was deprecated in Android 15**.”

**算子边界**：从 1.32.0 的 `.so` 里能直接数出 NNAPI 的 builder 类（32 个，实测 `strings | grep OpBuilder`；括号内是按 ORT 命名推断的算子归类，`Base`/`I` 是基础设施类）：

```
Base, BatchNormalization, Binary(Add/Sub/Mul/Div/Pow/Min/Max/PRelu...), Cast, Clip, Concat,
Conv, DepthToSpace, DequantizeLinear, Elu, Flatten, Gather, Gemm, Identity, I, LeakyRelu, LRN,
MinMax(Min/Max), Pad, Pool(Average/GlobalAverage/GlobalMax/Max), QuantizeLinear, Reduction,
Relu, Reshape, Resize, Slice, SoftMax, Split, Squeeze, Transpose, Unary(Abs/Exp/Floor/Log/Neg/
Sin/Sqrt/Tanh 等), Unsqueeze
```

**没有** `GatherND`、`ScatterND`、`Where`、`Equal`、`Mod`、`Shape`、`Expand`、`ConstantOfShape`、`InstanceNormalization`、`LayerNormalization`、`GridSample`、`Erf`。这直接决定了三个模型的命运（见 §4.7）。

**已知回退与崩溃面**：

| 现象 | 机制 | 证据 |
|---|---|---|
| 不支持的节点静默回退 CPU | ORT 按节点做能力分区，未认领的节点由 CPU EP 执行 | ORT 架构；NNAPI 文档「Supported ops」表 |
| NNAPI 自身回退到参考 CPU 实现 | 「If there is an error during partitioning, if a driver fails to compile … NNAPI might fall back to its own CPU implementation」 | Android NNAPI 文档 “Error management and CPU fallback” |
| `CPU_DISABLED` 导致**加载失败** | Java 枚举注释原文：「If an operator could be assigned to NNAPI, but NNAPI only has a CPU implementation of that operator on the current device, **model load will fail**」 | `NNAPIFlags.java` |
| 动态 shape 无法进 NNAPI | NNAPI 的 unknown dims 直到 API 28/29 才引入；ORT 的 NNAPI EP 以静态 shape 建图 | Android NNAPI 文档「Tensors of unknown rank」 |
| Resize 新属性不支持 | `.so` 字符串：「Resize 18+ antialias feature is not currently supported by NNAPI」「Resize 18+ axes attribute …」「Resize 18+ keep_aspect_ratio_policy …」 | 1.32.0 二进制 |
| 驱动差异导致崩溃/花屏 | 厂商 NNAPI 驱动质量参差（历史高发） | 无法用一手来源量化；本报告未验证 |

对 PhotoSync 的含义：**NNAPI 在 2026 年不应作为主路径**。它既被废弃，又在 API 24–26 上不可用，还会把“模型是否全加速”变成完全不可控的厂商驱动彩票。合理定位是“实验性开关 + 失败回退 CPU + 记忆降级”。

### 3.4 QNN EP

| 项 | 事实 | 证据 |
|---|---|---|
| 产物 | `com.microsoft.onnxruntime:onnxruntime-android-qnn`，**只到 1.29.0**，只有 `arm64-v8a` | Maven metadata + AAR 列表 |
| 传递依赖 | `com.qualcomm.qti:qnn-runtime:2.42.0` | 1.29.0 POM 原文 |
| 许可 | **Qualcomm AI Hub Model License**（`qnn-runtime` POM `<licenses>`），manifest 头写 “Confidential and Proprietary” | POM / AAR manifest |
| 体积 | `qnn-runtime` AAR = **63,074,692 B**；解包后含 `libQnnHtpPrepare.so` **83,686,128 B**、`libQnnHtpV68..V81Skel.so` 各 9–17 MB、`libQnnGpu.so` 6.4 MB、`libQnnSystem.so` 2.7 MB | `unzip -l` 实测 |
| 模型要求 | **HTP 后端只接受量化模型**；**不支持动态 shape**（必须固定）；算子子集（无 Loop/If） | QNN EP 文档原文 |
| 其它 | HTP 子系统重启（SSR）会返回 `ENGINE_ERROR`，需上层重建 session | QNN EP 文档「Error handling」 |

**在“严格 MIT/Apache-2.0 可商用”口径下，QNN 直接排除**：它不是开源组件，且体积（数十 MB 的 skel 库）与“按 ABI 分发”叠加后会让 APK 再涨一个量级。

### 3.5 EP 决策建议

| EP | 何时用 | 结论 |
|---|---|---|
| CPU | 默认、验收基线 | **必选** |
| XNNPACK | 模型以 Conv 为主、且实测有收益时 | 可选，需真机 benchmark |
| NNAPI | 仅作实验开关（API 27+，记得 try/catch + 记忆降级） | **不作主路径** |
| QNN | 明确只支持骁龙 8 系、且接受专有许可 | **本仓库不采用** |

---

## 4. 通用抠图/发丝模型对比

### 4.1 汇总表（全部实测字节 + 原文许可证）

| 模型 | 文件 | 字节 | 参数 | 输入 → 输出 | 动态 shape | 许可证（weights） |
|---|---|---:|---:|---|---|---|
| **BiRefNet-lite** FP32 | `onnx-community/BiRefNet_lite-ONNX/onnx/model.onnx` | **224,005,088** | 55.33 M | `[1,3,1024,1024]` → `[1,1,1024,1024]` FLOAT | 否 | **MIT** |
| BiRefNet-lite FP16 | `…/model_fp16.onnx` | **114,538,221** | 55.33 M | 同上（权重 FP16，IO 仍 FLOAT） | 否 | MIT |
| BiRefNet-lite 2K | 无官方 ONNX（`ZhengPeng7/BiRefNet_lite-2K` 仅 safetensors，2560×1440） | — | — | — | — | MIT（HF card） |
| **ISNet-general-use** FP32 | `onnx-community/ISNet-ONNX/onnx/model.onnx` | **176,114,856** | 44.00 M | `[1,3,1024,1024]` → `[1,1,1024,1024]` | 否 | **AGPL-3.0（该镜像）/ 争议** |
| ISNet FP16 | 同上 `model_fp16.onnx` | 88,116,798 | 44.00 M | 同上 | 否 | 同上 |
| ISNet INT8 | 同上 `model_int8.onnx` | 44,313,923 | 44.00 M | 同上 | 否 | 同上 |
| **MODNet** FP32 | `Xenova/modnet/onnx/model.onnx` | **25,888,640** | 6.46 M | `[?,3,?,?]` → `[?,1,?,?]` | **是** | **Apache-2.0** |
| MODNet FP16 | `…/model_fp16.onnx` | 12,984,781 | 6.46 M | 同上 | 是 | Apache-2.0 |
| MODNet UINT8（动态量化） | `…/model_quantized.onnx` | 6,632,188 | 6.46 M | 同上 | 是 | Apache-2.0 |

SHA-256（实测）：BiRefNet-lite FP32 `5600024376f572a5…`、FP16 `d39b897ceb16ae65…`；ISNet FP32 `8bc7e049e30cdda7…`、INT8 `1f3b305d8093d9d3…`；MODNet FP32 `07c308cf0fc7e6e8…`、INT8 `92e49898c3e05a6d…`（完整值见附录 A）。

### 4.2 BiRefNet-lite

- **许可证**：`github.com/ZhengPeng7/BiRefNet/LICENSE` 原文为 MIT（“Copyright (c) 2024 ZhengPeng … Permission is hereby granted, free of charge…”）；HF `ZhengPeng7/BiRefNet_lite`、`ZhengPeng7/BiRefNet_lite-2K`、`onnx-community/BiRefNet_lite-ONNX` 的 card 均为 `license: mit`。**代码与权重都是 MIT，可直接商用**。
- **量化变体**：官方只有 FP32/FP16。`onnx-community/BiRefNet_lite-ONNX` 的 `onnx/` 目录实测只有 `model.onnx` 与 `model_fp16.onnx`，**没有 INT8/quantized**。自己量化当然可以（MIT 允许），但见 §4.6 的 INT8 实测会让它更慢。
- **2K 变体**：`ZhengPeng7/BiRefNet_lite-2K` 只有 `model.safetensors` + 自定义代码，输入 **2560×1440**（README 原文：“takes inputs in a much higher resolution (2560x1440)”）。HF 上不存在 `onnx-community/BiRefNet_lite-2K-ONNX`（API 返回 “Invalid username or password”，即不存在/私有）。**要用 2K 必须自己导出 ONNX**，而导出需要 `trust_remote_code` 的 Swin 实现。
- **图结构**：opset 17、IR 8、16,400 节点、437 个 initializer（178,489,160 B，全 FLOAT）+ 7,296 个 `Constant`（42,873,792 B，其中 6,988 个 INT64）。最大常量是 deformable conv 的采样网格：

```
/decoder/decoder_block1/dec_att/aspp_deforms.2/atrous_conv/Constant_2  25,690,112 B  [1,1,49,2,256,256]
/decoder/decoder_block2/dec_att/aspp_deforms.2/atrous_conv/Constant_2   6,422,528 B  [1,1,49,2,128,128]
```

  这也解释了它为什么需要 16k 节点：**deformable conv 被拆成了 GatherND/ScatterND + 网格常量**，而不是一个融合算子。
- **预处理**（`preprocessor_config.json` 原文）：resize 到 1024×1024、`rescale 1/255`、ImageNet mean `[0.485,0.456,0.406]` / std `[0.229,0.224,0.225]`。

### 4.3 ISNet-general-use

这一项的结论是 **“许可证不可用”，而不是“效果不好”**：

| 层 | 声明 | 原文/证据 |
|---|---|---|
| 代码（DIS 仓库） | Apache-2.0 | `xuebinqin/DIS/README.md`：「Our code and evaluation metric use Apache License 2.0」 |
| 训练集 DIS5K | **禁止商用** | `DIS5K-Dataset-Terms-of-Use.pdf`：「The Dataset is available for **non-commercial use in research or educational purpose** … **commercial use of this dataset is prohibited** even after copying, editing, processing…」；第 4 条禁止再分发 |
| ONNX 镜像 A | **AGPL-3.0** | `onnx-community/ISNet-ONNX` card `license: agpl-3.0` |
| ONNX 镜像 B | MIT | `imgly/isnet-general-onnx` card `license: mit` |
| rembg 分发的权重 | **无明确许可证** | rembg README：「Model weights carry their own licenses, independent of rembg's MIT license — check the linked source before using any model commercially」 |

同一份权重出现三种互斥声明，且训练集本身禁止商用——**对闭源商业 App 来说这是不可接受的法律风险**。若产品确实需要 ISNet 的效果，唯一干净的路是：拿 DIS 的 Apache-2.0 代码 + 自己在可商用数据上重训，成本远高于直接用 BiRefNet。

即使忽略许可证，ISNet 的技术规格是**三者中最“端侧友好”的**：355 个节点、算子只有 `Conv(114)/Relu(112)/Concat(50)/Resize(34)/MaxPool(33)/Add(11)/Sigmoid(1)`，静态 1024²，Resize 全部 `half_pixel + linear`（NNAPI 支持的模式）。

### 4.4 MODNet（纯人像对照组）

- **许可证**：README 原文「The code, **models**, and demos in this repository (excluding GIF files under the folder `doc/gif`) are released under the **Apache License 2.0** license.」——这是三者中**唯一明确把 weights 写进许可证句子**的。
- **规格**：opset 11、IR 6、487 节点、`input [?,3,?,?]` **动态**、`InstanceNormalization` ×16、`Expand`、`Clip` ×35、`Resize`（`pytorch_half_pixel`）。
- **定位**：只做人像 alpha，不区分发丝级语义；官方论文口径「runs at **67 frames per second on a 1080Ti** GPU」，输入惯例 512×512（`preprocessor_config` 的 `shortest_edge: 512`、`size_divisibility: 32`）。
- **对发丝**：MODNet 的输出是 512 级语义 mask，发丝细节靠低分辨率 alpha 的软边，**不具备 BiRefNet 那种 1024 全分辨率二值分割能力**；作为“实时预览兜底”合适，作为“24MP 精修”不够。

### 4.5 许可证证据链（逐条可点）

| 模型 | 证据链接 | 结论 |
|---|---|---|
| BiRefNet | <https://github.com/ZhengPeng7/BiRefNet/blob/main/LICENSE>、<https://huggingface.co/ZhengPeng7/BiRefNet_lite>、<https://huggingface.co/ZhengPeng7/BiRefNet_lite-2K>、<https://huggingface.co/onnx-community/BiRefNet_lite-ONNX> | MIT，可商用 |
| MODNet | <https://github.com/ZHKKKe/MODNet>（README “License” 节）、<https://github.com/ZHKKKe/MODNet/blob/master/LICENSE>、<https://huggingface.co/Xenova/modnet> | Apache-2.0，可商用 |
| ISNet | <https://github.com/xuebinqin/DIS>（README 只覆盖 code）、<https://github.com/xuebinqin/DIS/blob/master/DIS5K-Dataset-Terms-of-Use.pdf>、<https://huggingface.co/onnx-community/ISNet-ONNX>、<https://huggingface.co/imgly/isnet-general-onnx>、<https://github.com/danielgatis/rembg>（model 表 + 免责声明） | **不可商用（链条矛盾 + 训练集 NC）** |

> 说明：BiRefNet 同样在 DIS5K 上训练过（`ZhengPeng7/BiRefNet_lite-2K` card 的 Training Sets 列了 “DIS5K (except DIS-VD)”）。区别在于 **BiRefNet 的作者自己就是权重版权人，并以 MIT 发布了权重**；DIS5K ToU 约束的是“数据集”的复制/再分发/商用，不直接约束第三方作者发布的模型。这是“法律灰区”，但比 ISNet 的“三种互斥声明 + 无 weights 许可证”干净得多。若法务要求零灰区，则只能自训。

### 4.6 端侧参考延迟与峰值内存（实测 x86_64 + 可引用原文）

**本机实测**（ORT 1.32 CPU EP，4 线程，随机输入，每模型独立进程；宿主为共享开发机，延迟取多轮 min/median）：

| 模型 | 输入 | 加载耗时 | 首次推理 | median | min | **峰值 RSS** | 仅加载后 RSS |
|---|---|---:|---:|---:|---:|---:|---:|
| BiRefNet-lite FP32 | 1024² | 1.23 s | 10.6 s | 6.81 s | **6.50 s** | **11,999 MB** | 459 MB |
| BiRefNet-lite FP32（`enable_cpu_mem_arena=false`） | 1024² | 1.32 s | 7.66 s | 8.54 s | 7.59 s | **6,799 MB** | — |
| BiRefNet-lite FP16 | 1024² | 1.54 s | 8.40 s | 7.59 s | 7.51 s | **13,824 MB** | — |
| ISNet FP32 | 1024² | 0.22 s | 0.97 s | 0.87–1.88 s | **0.80 s** | **1,227 MB** | 451 MB |
| ISNet INT8（动态量化） | 1024² | 0.33 s | 22.8 s | 22.7 s | 22.5 s | 1,478 MB | — |
| MODNet FP32 | 512² | 0.06 s | 0.14 s | 0.137 s | 0.133 s | **334 MB** | 149 MB |
| MODNet FP32 | 1024² | 0.06 s | 0.62 s | 0.64 s | 0.63 s | 959 MB | — |
| MODNet UINT8（动态量化） | 512² | 0.09 s | 0.41 s | 0.40 s | 0.40 s | 378 MB | — |

**可引用的原文基准**（GPU，不是端侧）：

| 模型 | 硬件 | 延迟 | 显存 | 来源 |
|---|---|---:|---:|---|
| BiRefNet（标准，swin_large） | A100 FP32/FP16 | 86.8 / 69.4 ms | 4.8 / 3.5 GB | BiRefNet README “Model efficiency” |
| 同上 | RTX 4090 FP32/FP16 | 95.8 / 57.7 ms | — | 同上 |
| 同上 | V100 FP32/FP16 | 384 / 152 ms | — | 同上 |
| MODNet | 1080Ti | 67 FPS | — | MODNet 论文摘要（arXiv:2011.11961） |

**怎么读这张表（关键）**：

1. **BiRefNet-lite 的瓶颈是内存，不是算力**。12 GB 峰值 / 6.8 GB（关 arena）意味着任何 8 GB 手机都会 OOM；即使关掉 arena，也超过 Android 单 App 的实际可用堆。原因是 1024² 分辨率下的高分辨率特征图（256×256×256 float = 67 MB/张）在 16k 节点的图里同时存活。
2. **ISNet 的 1.23 GB 也在危险区**：中端 4–6 GB 机型上，前台 App 一旦叠加 bitmap/GL 纹理就会触发 `OutOfMemoryError` 或被 LMK 杀掉。
3. **MODNet 是唯一“手机友好”的量级**（334 MB @512），但它只有人像语义。
4. **INT8 不能想当然**：这两个 ONNX 镜像用的是**动态量化**（`DynamicQuantizeLinear` + `ConvInteger`），实测 ISNet 慢 **约 25×**（22.7 s vs 0.87 s）、MODNet 慢 **3×**。若要做 INT8，必须用静态 QDQ + 校准集，且 ConvInteger 在 ARM 上是否走 dot-product 内核要按 ORT 版本实测。
5. 宿主 x86 与手机 SoC 不可直接换算：Cortex-A7xx 单核通常比本机单核慢 3–6×，但大核 + 多线程可部分抵消。**端侧数字必须在真机复测**（§7）。

### 4.7 与 ORT EP 的匹配度

| 模型 | NNAPI | XNNPACK | 说明 |
|---|---|---|---|
| BiRefNet-lite | **不可用** | **仅部分** | `GatherND`/`ScatterND`/`Where`/`Equal`/`Mod`/`Shape`/`Expand`/`ConstantOfShape` 均无 NNAPI builder；XNNPACK 只能接走 Conv/Pool/Resize 子图，其余回 CPU。实际等价于 **CPU EP** |
| ISNet | **理论可用** | **可用** | 全部算子都在 NNAPI 列表内（Conv/Relu/Concat/Resize/MaxPool/Add/Sigmoid），静态 1024；但 API 27+ 且 NNAPI 已废弃，仍需 CPU 兜底 |
| MODNet | 不可用（动态 shape + `InstanceNormalization`/`Expand`） | 文档化算子覆盖 Conv/Resize，动态 shape 下的实际分区未验证 | 必须先做 shape 固定（`make_dynamic_shape_fixed`）才能进 NNAPI；即便固定，`InstanceNormalization` 仍会回 CPU |

> 结论：**选 BiRefNet 就等于选“纯 CPU 推理”**，任何“用 NNAPI 加速抠图”的设想在它身上不成立。这也让 §5 的分块精修成为唯一可行的性能杠杆。

---

## 5. 24MP–60MP 边缘精修与去色溢

### 5.1 仓库现状（代码证据）

| 事实 | 位置 |
|---|---|
| 分析图**短边**缩放到 512 px（长边按比例，可能 >512） | `BakePortraitSupport.ANALYSIS_MAX_EDGE_PX = 512` + `scaleToAnalysisSize()` |
| 模型输出与所有 mask 的坐标网格固定 **256×256** | `SkinProtectionMask.MASK_SIZE = 256` → `SkinMaskMath.MASK_SIZE` |
| mask 上传为 **GL_R8 / 8 bit**（`weight*255+0.5`），bilinear | `SkinMaskTexture.upload()` |
| 预览离屏最长边 2048 px | `OffscreenColorTarget.MAX_PREVIEW_EDGE_PX = 2048` |
| bake 分块 **1920×512 + 64 px halo**，FBO ≤ **2048×640** | `ColumnBandTiling` |
| bake 源纹理三档驻留（全帧纹理 / native slice / 降采样），预算 512 MB | `BakeSourceTextures` / `SourceResidencyPlan` |
| shader 侧按归一化 UV 采样 mask（`guideUv()`），所以“上采样”本身是免费的硬件 bilinear | `DetailShaderSources.skinMaskWeight()` |

因此当前链路是：**512 分析图 → 256 mask（8 bit）→ 任意分辨率 bilinear 采样**。分辨率与位深都已经被钉死。

### 5.2 分辨率对齐数学

设输出宽 W、高 H，mask 边长 M，则一个 mask texel 覆盖 `W/M` 个像素：

| 输出 | 像素数 | M=256（现状） | M=1024（BiRefNet-lite） | M=2048（自训/2K 导出） |
|---|---:|---:|---:|---:|
| 24 MP（6000×4000） | 24.0 M | **23.4 px/texel** | 5.86 | 2.93 |
| 45 MP（8192×5464） | 44.8 M | 32.0 | 8.00 | 4.00 |
| 60 MP（9504×6336） | 60.2 M | 37.1 | 9.28 | 4.64 |

而按头肩构图估算，人像里单根发丝的投影像宽通常只有 **0.5–2 px**（估算，取决于拍摄距离与镜头；不是实测）。结论很直接：

> **M=1024 仍然比发丝粗约 3–12 倍；M=2048 也粗约 1.5–6 倍。** 任何“把模型 mask 上采样到 6000 px”的做法，输出都只能是“软边”，不可能是“发丝”。发丝必须由**全分辨率图像引导的精修**补出来，而不是由 mask 分辨率补出来。

同时注意位深：`GL_R8` 的 alpha 量化步长是 1/255 ≈ 0.0039。对最终 8-bit 输出够用，但**在 guided filter 的线性系数里会被放大**（`a = cov/(var+eps)`），所以精修链路内部应使用 `GL_R16F` / float，最后再量化。

### 5.3 `GuidedFilter.kt` 的成本与内存

现有实现（`editor/src/main/java/cn/devcxl/photosync/editor/domain/GuidedFilter.kt`）：

- `smooth(guide, input, w, h, radius, epsilon)`：标准 He et al. guided filter，6 次 `boxMean`（meanGuide、meanInput、meanGuideSquares、meanGuideInput、meanCoefficients、meanIntercepts）。
- `partialWindow(values, w, h, radius, epsilon)`：单窗口自引导版本，3 次 `boxMean`。

**数组计数**（按 `smooth` 的求值顺序，峰值同时存活）：`guide, input, meanGuide, meanInput, guideSquares, guideInput, meanGuideSquares, meanGuideInput, coefficients, intercepts, meanCoefficients, meanIntercepts, output` + `boxMean` 内部的水平临时数组 ⇒ **≥12–13 个 FloatArray 同时存在，≈48–52 B/px**。

| 分辨率 | 像素 | `smooth` 峰值 | `partialWindow` 峰值（≈6 数组/24 B） |
|---|---:|---:|---:|
| 预览 2048×1365 | 2.80 M | 134–146 MB | 67 MB |
| bake 单 band 2048×640 | 1.31 M | **63–68 MB** | **31 MB** |
| 24 MP | 24.0 M | **1.15–1.25 GB** | 576 MB |
| 45 MP | 44.8 M | 2.15–2.33 GB | 1.07 GB |
| 60 MP | 60.2 M | **2.89–3.13 GB** | 1.44 GB |

再叠加时间成本：`boxMean` 是 2 遍 O(N) 标量循环（`Double` 累加、边界 `coerceIn`），`smooth` 一轮 = 12 遍。Kotlin/JVM 标量在手机大核上大约 50–150 Mpx/s（估算，未在真机标定）：

- 单 band：1.31 MP × 12 = 15.7 M 次 → **0.1–0.3 s/band**
- 60 MP 全帧：720 M 次 → **5–15 s**（一次性还可以接受，但内存先炸）

**结论**：`smooth` 作为“整帧 CPU 精修”不可行；作为“**band 内 CPU 精修**”完全可行（63 MB/band，落在现有 512 MB 预算内）。

### 5.4 两条可行路线

#### 路线 A：GLSL band 内 guided filter（推荐，与现有管线同构）

现有 bake 已经把画面切成 1920×512 + 64 halo 的 band，并在同一个 `BakePassChain` 里跑 Pass A/B/C。新增一个“matting refine” pass：

```
输入：uAlpha（GL_R16F, 1024² 或 2048²）、uGuide（band 内的 sRGB/RGB16F，已有）
输出：GL_R16F band alpha（2048×640）
步骤（每 band）：boxMean(guide) → boxMean(alpha) → boxMean(guide²) → boxMean(guide·alpha)
                → a,b → boxMean(a) → boxMean(b) → out = a*guide + b
```

- 参数：`radius` 取“上采样倍率的 2 倍”左右。1024 mask → 6000 px 是 5.86×，取 **r = 12–16 px**；2048 mask 取 **r = 6–8 px**。`epsilon` 以 guide∈[0,1] 计，取 **1e-4（保边）～1e-3（更平滑）**。
- halo 64 px 足够覆盖 r ≤ 32 的窗口；band 内所有 pass 都已经用 `uTileUvTransform` 做全局 UV 映射，seam 问题已被现有架构解决。
- 显存：每个 R16F band 目标 2048×640×2 B = **2.6 MB**；同时 3–4 张 ≈ 10 MB，可忽略。
- 时间（估算，非实测）：1.31 MP × 12 次全屏 draw ≈ 16 M fragment；中端手机 ~1 GPix/s ⇒ **~15–30 ms/band**，60MP 的 65 个 band（5×13）≈ **1–2 s**。
- 位深：R16F 保留 10+ bit 有效精度，避免 R8 在 `a` 系数里放大量化噪声。

#### 路线 B：CPU per-band fast guided filter（不改 shader，改动最小）

- 在 `BakeExporter` 的 band 循环里，对每个 band：把 1024 alpha 双线性放大到 band 尺寸（1.31 MP float = 5.2 MB），用 band 的 sRGB 亮度（或 RGB 三通道）做 guide，调 `GuidedFilter.smooth`，得到 band alpha 后上传为纹理。
- 峰值 = band 尺寸的 63 MB + band 源数据，仍在预算内；60MP ≈ 5–15 s，可放在 bake 的 IO 等待间隙。
- 若嫌慢，使用 **Fast Guided Filter**（He & Sun, arXiv:1505.00996）：在 `1/s²` 的子采样网格上算 `a,b`，再双线性放大回全分辨率，原文「O(N) → O(N/s²) … speedup of >10x with almost no visible degradation」。`s = 4` 时单 band 只需 ~4 MB、~1–4 ms/band。

#### 路线 A/B 的共同前置

1. **mask 从 256 升到 1024/2048**：`SkinMaskMath.MASK_SIZE`、`SkinMaskTexture` 的固定尺寸、`PortraitAnalysisSnapshot` 的缓存键都要改；缓存 profile 字符串需要 bump（`PREVIEW_ANALYSIS_PROFILE`）。
2. **位深从 R8 升到 R16F**：`SkinMaskTexture` 增加 half-float 上传路径（仓库已有 `EditorTextureUploader.uploadLinearHalfFloat` 可复用），并把 `hasMask` 的能力判定与 `allowHalfFloat` 对齐。
3. **预览**：屏幕上 ≤2048 px，1024 mask 的 5.86× 上采样 + guided filter（s=4）已足够；不需要 2048 mask。

### 5.5 去色溢（Color Decontamination）算法对比

合成方程 `I = αF + (1-α)B`（Porter & Duff, SIGGRAPH 1984）。去色溢的本质是**在已知 α 的前提下反解 F**，否则把 `I` 直接贴到新背景上会把旧背景颜色带过去（发丝边缘最明显）。

| 方案 | 复杂度 | 内存 | 质量 | 出处 |
|---|---|---|---|---|
| 朴素反解 `F = (I-(1-α)B)/α` | O(n) | 2 张图 | α→0 处爆炸，需要 clamp | 合成方程直接变形 |
| 闭式 F/B（Levin et al.） | 稀疏线性系统 | 高 | 最好 | CVPR 2006 / PAMI 2008, doi:10.1109/TPAMI.2007.1177；FMGE 论文实测「~30 s per color channel for n = 0.4 MP」 |
| **Fast Multi-Level Foreground Estimation** | **O(n)/像素/轮** | **~10 floats/px** | 接近闭式 | **arXiv:2006.14970**；实现 `pymatting.estimate_foreground_ml`（MIT） |
| KNN / Shared matting 的 F 估计 | O(n·k) | 中 | 中 | Gastal & Oliveira, “Shared Sampling for Real-Time Alpha Matting”, Eurographics 2010 |

**FMGE 的原始参数（论文 Algorithm 1 + pymatting 默认值）**：

| 参数 | 论文 | pymatting 代码 |
|---|---|---|
| 正则 `εr` | `5e-3` | `regularization=1e-5` |
| alpha 梯度权重 `ω` | `0.1` | `gradient_weight=1.0` |
| 邻域 | 4 邻域 | 4 邻域 |
| 金字塔层数 | `nl = ceil(log2(max(w,h)))`，逐级 ≤2× | 同 |
| 小尺度迭代 | ≤32 px 时 **10 轮** | `n_small_iterations=10`，`small_size=32` |
| 大尺度迭代 | **2 轮** | `n_big_iterations=2` |

每像素每轮只解一个 2×2 系统（`g = (U Uᵀ + Rᵀ V R)⁻¹ (I U + Rᵀ V h)`），矩阵与颜色通道无关，**可以一次解出 RGB 三个通道**——这正是它比闭式解法快两个数量级的原因。

**内存账**：`I, F, B` 各 3 通道 + `α` = **10 floats/px = 40 B/px**。

| 分辨率 | 全帧内存 | 结论 |
|---|---:|---|
| 1024×683（代理） | 28 MB | ✅ 可作为初始化 |
| 2048×1365 | 112 MB | ⚠️ 可接受 |
| 24 MP | **960 MB** | ❌ |
| 60 MP | **2.4 GB** | ❌ |

**可行的工程化写法**（与 §5.4 的 band 架构一致）：

1. 在 1024 代理上跑完整 FMGE（28 MB，~0.2–0.5 s），得到低频 `F_lo, B_lo`。
2. 对每个 bake band：把 `F_lo, B_lo` 双线性放大作为**初值**，在 band 分辨率上只跑 **2 轮**局部迭代（1.31 MP × 40 B = 52 MB）。
3. 只在 `α ∈ (0.02, 0.98)` 的过渡带 ±16 px 内写回；其余像素保持 `F = I`（α=1）或 `F = 0`（α=0），避免无谓计算与颜色漂移。
4. 最终合成用 `out = α·F + (1-α)·B_new`，而不是 `α·I + (1-α)·B_new`。

这样 60MP 的额外开销约 **52 MB 常驻 + 65 band × (2 轮 × 1.31 MP) ≈ 0.6–2 s**（估算）。

### 5.6 端到端 bake 预算（投影）

以 60MP、2 ABI、现有 band 管线为基线：

| 项 | 现状 | +mask 1024 + GLSL guided filter | +FMGE 去色溢 |
|---|---:|---:|---:|
| 常驻内存 | 源纹理/切片（≤512 MB 预算） | +10 MB（R16F band 目标） | +52 MB（band F/B） |
| 额外耗时 | — | ~1–2 s | ~0.6–2 s |
| 是否改 shader | — | 是（1 个 pass，6 次 box） | 否（CPU band 后处理） |
| 风险 | — | GLES 3.0 半浮点 RT 需 `EXT_color_buffer_half_float`（仓库已有能力判定） | Kotlin 标量性能 |

结论：**在 24–60MP 上做“发丝级”精修是可行的，但前提是“分块 + 低分辨率初始化”，而不是“整帧 float 管线”**。

---

## 6. 推荐决策清单

先给一张“换不换”的对照（全部来自本文与前一份调研的实测/原文）：

| 维度 | MediaPipe 现状（`tasks-vision:1.2.3`） | ORT + BiRefNet-lite |
|---|---|---|
| 运行时 APK 增量（arm64） | **+15.80 MiB** | **+34.79 MiB** |
| 模型体积 | 0.25 MB（selfie_segmenter）～16.37 MB（multiclass） | **114.5 MB（FP16）～224.0 MB（FP32）** |
| 许可证 | Apache-2.0 | MIT（BiRefNet） |
| 硬件加速 | GPU delegate（GLES 3.1）/ CPU | 实际只有 CPU（NNAPI 用不上，§4.7） |
| 端侧内存 | 未测量（模型 ≤16 MB） | **6.8–12 GB（本机实测，手机不可行）** |
| 发丝能力 | 无（256 语义 mask） | 有，但当前设备跑不动 |

结论：按本文实测（BiRefNet-lite 峰值 6.8–12 GB），**“换 ORT 以获得发丝”在 8 GB 以内的手机上不成立**；能成立的是“保留 MediaPipe 的分割 + 在全分辨率上用无模型精修补发丝”（§5）。

1. **不要把 ORT 当作 MediaPipe 的“同类替换”**：运行时 +34.79 MiB（arm64）是 MediaPipe 的 2.2 倍，而且 ORT 不带任何分割模型；换 ORT 的唯一理由是“要 BiRefNet 级的发丝质量”。
2. 如果确定引入 ORT：
   - 固定 `1.32.0`，R8 加 `-keep class ai.onnxruntime.** { *; }`；
   - release 加 `packaging.jniLibs.excludes += ["lib/x86/**","lib/x86_64/**"]` 与 `ndk.debugSymbolLevel 'none'`（AAB 从 118 MB → 26.3 MB，实测）；
   - manifest 里 `tools:node="remove"` 掉 `ai.onnxruntime.TelemetryInitializer`，并评估是否移除 `ACCESS_NETWORK_STATE`（§2.8）；
   - **不要**注册 NNAPI / QNN；CPU EP 为唯一路径，XNNPACK 仅在有 benchmark 证据时开。
3. **模型选 BiRefNet-lite（MIT）**：FP16 权重（114.5 MB）优先，但 IO 仍是 FLOAT；**不要**用 onnx-community 的 ISNet（AGPL + DIS5K NC）；MODNet 只作为“人像实时预览”的兜底（Apache-2.0、26 MB、334 MB 内存）。
4. **BiRefNet-lite 不能整帧跑**：即使关掉 arena 也要 6.8 GB。若坚持用它，必须做二选一：
   - 在 1024 上跑完后立刻释放（峰值仍 6.8 GB，手机上不可行）；
   - 重新导出/改写 deformable conv 为融合算子（把 16k 节点压到 ~1k），或改用轻量架构——这属于独立的模型工程，不在“直接下载 ONNX”范围内。
5. **精修路线**：mask 升到 1024 + R16F，bake 在 band 内做 guided filter（GLSL 优先，CPU fast guided filter 兜底），参数 `r = 12–16 px @1024`、`eps = 1e-4…1e-3`。
6. **去色溢**：FMGE（arXiv:2006.14970）作为唯一候选，按 §5.5 的“低分辨率初始化 + band 细化”实现；`pymatting`（MIT）可作为参考实现与离线对拍工具。
7. **先做真机验证再定型**：本报告没有 Android 设备数据；至少要在一台 arm64（8 GB）与一台 4 GB 低端机上验证 ISNet/MODNet 的峰值 RSS 与 NNAPI/XNNPACK 行为。

---

## 7. 未验证项与风险

| 项 | 状态 | 影响 |
|---|---|---|
| Android 真机延迟/内存 | **未验证**（无设备） | 表 §4.6 是 x86_64 数据；手机可能慢 3–6× 且内存更紧张 |
| BiRefNet-lite 在 Android 上的实际峰值 | 未验证（仅 x86 投影） | 若 >2 GB，方案直接不可行 |
| minimal build 的实际体积（1.32） | **投影**（按 1.18 的 22.95% 比例） | 需要本地重编才能证实；源码仓库当前 404 |
| NNAPI 驱动崩溃率 | 未验证 | 只能靠真机矩阵与降级策略兜底 |
| XNNPACK 对 ISNet/BiRefNet 的实际收益 | 未验证 | 文档化算子少，可能为 0 |
| ONNX Runtime 遥测默认是否真的上传 | 部分验证：AAR 含 provider/权限/端点；**默认开关未在设备上抓包** | 隐私合规风险；建议按“默认开启”处理 |
| GLSL guided filter 的帧时间 | **估算**（~15–30 ms/band） | 需在目标 GPU 上实测 |
| FMGE 在 JVM/Kotlin 的实现性能 | 未实现 | 标量实现可能比论文的 NumPy 版本慢；建议 native 或 GLSL |
| DIS5K ToU 对“训练出的模型”的约束力 | 法律问题，非技术 | 若法务认定传染，则 BiRefNet 也不能用（需自训） |
| `qnn-runtime` 的再分发条款 | 只读到 POM 的 license 名称与 manifest 的 “Confidential and Proprietary” | 商业分发前必须走 Qualcomm 授权 |

---

## 附录 A：复现命令

```bash
# 1) 版本与 AAR
curl -s https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml | tail -20
curl -sSLO https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.32.0/onnxruntime-android-1.32.0.aar
unzip -l onnxruntime-android-1.32.0.aar
unzip -p onnxruntime-android-1.32.0.aar proguard.txt
unzip -p onnxruntime-android-1.32.0.aar AndroidManifest.xml | strings

# 2) Full vs minimal（1.18.0）
for a in onnxruntime-android onnxruntime-mobile; do
  curl -sSLO "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/$a/1.18.0/$a-1.18.0.aar"
  unzip -l "$a-1.18.0.aar" | grep libonnxruntime.so
done

# 3) ABI 的 API level 与依赖
readelf -n jni/arm64-v8a/libonnxruntime.so      # .note.android.ident 首 4 字节 = API 24
readelf -d jni/arm64-v8a/libonnxruntime.so      # NEEDED: 无 libc++_shared

# 4) 探针 APK / AAB（与本报告同配置）
#    /tmp/opencode/ort/probe/app/build.gradle: minSdk 24, R8, splits.abi(arm64,v7a)
gradlew -p /tmp/opencode/ort/probe assembleRelease
gradlew -p /tmp/opencode/ort/probe bundleRelease

# 5) 模型下载与规格
mkdir -p models && cd models
curl -sSLO https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model.onnx
curl -sSLO https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model_fp16.onnx
curl -sSLO https://huggingface.co/onnx-community/ISNet-ONNX/resolve/main/onnx/model.onnx
curl -sSLO https://huggingface.co/onnx-community/ISNet-ONNX/resolve/main/onnx/model_int8.onnx
curl -sSLO https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx
curl -sSLO https://huggingface.co/Xenova/modnet/resolve/main/onnx/model_quantized.onnx
sha256sum *.onnx

# 6) 图规格（opset / 输入输出 / 动态 shape / 算子直方图）
python3 -c "
import onnx
m=onnx.load('birefnet_lite_fp32.onnx', load_external_data=False)
g=m.graph
print(m.opset_import, [(i.name,[d.dim_value or d.dim_param for d in i.type.tensor_type.shape.dim]) for i in g.input])
print(sorted({n.op_type for n in g.node}))
"

# 7) ORT format 转换（minimal build 的前置）
python3 -m onnxruntime.tools.convert_onnx_models_to_ort \
  --optimization_style Fixed --target_platform arm modnet_fp32.onnx
cat modnet_fp32.required_operators.config

# 8) 延迟/峰值内存（每模型独立进程）
python3 bench.py isnet_fp32.onnx 1024 4 5
```

## 附录 B：一手来源

| 主题 | 链接 |
|---|---|
| Maven 版本元数据 | https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml |
| ORT Android AAR / POM / sources | https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.32.0/ |
| ORT QNN AAR / POM | https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.29.0/ |
| Qualcomm QNN runtime AAR / POM | https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/2.42.0/ |
| ORT Android 构建（R8 规则、NNAPI/QNN 构建） | https://onnxruntime.ai/docs/build/android.html |
| ORT 自定义/minimal build | https://onnxruntime.ai/docs/build/custom.html |
| ORT 安装（Android 坐标） | https://onnxruntime.ai/docs/install/#install-on-android |
| NNAPI EP（API 27+、算子表、flag 语义） | https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html |
| XNNPACK EP（算子表、推荐配置） | https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html |
| QNN EP（量化/静态 shape/SSR） | https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html |
| Android NNAPI 指南（弃用声明、unknown dims、CPU fallback） | https://developer.android.com/ndk/guides/neuralnetworks |
| BiRefNet LICENSE | https://github.com/ZhengPeng7/BiRefNet/blob/main/LICENSE |
| BiRefNet_lite / 2K Model Card | https://huggingface.co/ZhengPeng7/BiRefNet_lite 、https://huggingface.co/ZhengPeng7/BiRefNet_lite-2K |
| BiRefNet-lite ONNX 权重 | https://huggingface.co/onnx-community/BiRefNet_lite-ONNX |
| ISNet ONNX（AGPL 声明） | https://huggingface.co/onnx-community/ISNet-ONNX |
| ISNet ONNX（MIT 声明） | https://huggingface.co/imgly/isnet-general-onnx |
| DIS 仓库与 DIS5K ToU | https://github.com/xuebinqin/DIS 、https://github.com/xuebinqin/DIS/blob/master/DIS5K-Dataset-Terms-of-Use.pdf |
| rembg（MIT，含 weights 免责声明） | https://github.com/danielgatis/rembg |
| MODNet LICENSE / README | https://github.com/ZHKKKe/MODNet |
| MODNet ONNX 权重 | https://huggingface.co/Xenova/modnet |
| MODNet 论文 | https://arxiv.org/abs/2011.11961 |
| Fast Multi-Level Foreground Estimation | https://arxiv.org/abs/2006.14970 |
| pymatting（MIT） | https://github.com/pymatting/pymatting |
| Fast Guided Filter | https://arxiv.org/abs/1505.00996 |
| Guided Image Filtering（PAMI 2013） | https://doi.org/10.1109/TPAMI.2012.213 |
| Closed-Form Matting（PAMI 2008） | https://doi.org/10.1109/TPAMI.2007.1177 |
| Compositing Digital Images（Porter & Duff 1984） | https://doi.org/10.1145/800031.808606 |
