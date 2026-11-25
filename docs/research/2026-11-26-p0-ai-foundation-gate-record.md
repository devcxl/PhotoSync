# P0 端侧 AI 底座验收记录（ORT 1.22.0、模型分发与真机门禁）

- 日期：2026-11-26（同日补测 MI-GAN 生成器-only 导出与 Redmi K20 Pro 真机数据）
- 关联工单：`.scratch/ai-tools/issues/01-p0-ort-runtime-model-manager-and-device-gate.md`
- 关联规范：`.scratch/ai-tools/spec.md` §2、§7–§8
- 上游调研：`docs/research/2026-11-26-ort-android-and-matting-models.md`、`docs/research/2026-11-26-on-device-inpainting-and-sky-models.md`

> 本文只记录本机实际执行过的验证。MI-GAN 生成器-only 导出、20 样图数值等价与 Redmi K20 Pro
> 真机耗时/峰值 RSS 均为 2026-11-26 实测；**4 GB 级低端机没有实体设备**，只有 K20 Pro 上以
> `DeviceMemoryTier.LOW` 档位模拟的契约验证，见 §2.1，不用模拟数据冒充低端机数据。

---

## 1. 已验证项

### 1.1 ONNX Runtime 1.22.0 产物与构建护栏

| 检查 | 结果 |
|---|---|
| Maven 坐标存在性 | `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` 可下载，AAR = **28,515,295 B** |
| AAR 内 ABI | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四套 |
| arm64 `libonnxruntime.so` | **18,214,224 B**（与 spec §2.1 的 +18.3 MB 口径一致） |
| arm64 `libonnxruntime4j_jni.so` | 100,600 B（release 裁符号后 75,720 B） |
| 1.22.0 AAR Manifest | 只有 `<uses-sdk minSdkVersion="24">`，**不含** `TelemetryInitializer`、不含额外权限 |
| 1.22.0 AAR ProGuard | **不含** `proguard.txt`，因此 App 必须自己保留 `ai.onnxruntime.**` |
| Java API | `OrtSession.SessionOptions.addXnnpack(Map)`、`setIntraOpNumThreads(int)`、`OrtException(String)` 均存在 |

说明：`TelemetryInitializer` 是 1.32.0 才合入的 provider（见 ORT 调研 §2.8）。本仓库仍在
`app/src/main/AndroidManifest.xml` 中以 `tools:node="remove"` 声明移除，并用
`tools:ignore="MissingClass"` 抑制 1.22.0 下“类不存在”的 lint 报错；Manifest Merger 对该
声明的输出是 “no other declaration present”，即当前版本无实际节点、升级版本时自动生效。

### 1.2 Release APK（R8 + 资源压缩 + debug keystore 签名）

执行：

```bash
ANDROID_HOME=~/Android/Sdk \
KEYSTORE_PATH=~/.android/debug.keystore KEYSTORE_PASSWORD=android \
KEY_ALIAS=androiddebugkey KEY_PASSWORD=android \
./gradlew :app:assembleRelease
```

产物 `app/build/outputs/apk/release/app-release.apk`（**71,964,814 B**，约 68.6 MiB）实测：

| 门禁 | 结果 |
|---|---|
| APK 不含 `x86` / `x86_64` | ✅ `unzip -l` 无任何 `lib/x86*` 条目 |
| 不含 native 调试符号 | ✅ 无 `.sym` 条目 |
| 保留 `ai.onnxruntime.**` | ✅ `mapping.txt` 中 63 个 `ai.onnxruntime.*` 类名未被混淆 |
| 合并清单无 `ai.onnxruntime.TelemetryInitializer` | ✅ `aapt2 dump xmltree` 只剩 `androidx.startup.InitializationProvider` |
| `AiModelSettingsActivity` 已注册 | ✅ 打包清单可见 |
| arm64 / v7a ORT `.so` | ✅ 两个 ABI 均含 `libonnxruntime.so` 与 `libonnxruntime4j_jni.so` |

> 这是 debug keystore 的验证构建，不是可发布产物；验证目标仅为 R8 keep、Manifest 合并与 ABI 裁剪。

### 1.3 模型清单与完整性固定值

#### 1.3.1 MODNet

2026-11-26 实际下载并计算：

```text
URL     https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx
bytes   25,888,640
sha256  07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9
```

该值已写入 `AiModelCatalog.MODNET_PORTRAIT`，下载与 SAF 导入共用同一字节数 + SHA-256 校验。

镜像顺序：HuggingFace 主源 → `hf-mirror.com` 备用。实测 `hf-mirror.com` 在本次网络环境下
返回 `308` 到 HuggingFace，因此它是“受限网络代理入口”而非独立字节源；下载器的契约只要求
镜像提供**相同字节**，后续可把 GitHub Releases 主源插到列表最前面而不改代码。

#### 1.3.2 MI-GAN 生成器-only 512（2026-11-26 固化）

**工具链**（独立 venv，未安装进系统 Python，也未改动仓库依赖）：

```text
uv venv /tmp/opencode/migan-venv --python 3.11
python 3.11.15, torch 2.15.0+cpu, onnx 1.23.2, onnxruntime 1.32.0, onnxscript 0.7.2
```

**权重来源**：官方 `migan_512_places2.pt` 检查点（29,553,256 B，
`sha256 1d6087eee0aac8923ad2606be5d8caeb4824d3e4de331995e420c74e124a466a`），它是
`lib.model_zoo.migan_inference.Generator(resolution=512)` 的纯 `state_dict`（177 个键，
无 `w0..wN` 重参数化张量，`strict=True` 加载成功），与官方 `scripts/create_onnx_pipeline.py`
的加载方式一致，即发布权重已完成重参数化，导出时无需（也无法）再调用 `reparameterize()`。

**导出命令**（`torch.onnx.export` 的 legacy TorchScript 路径，与官方 pipeline 导出一致）：

```python
model = MIGAN(resolution=512)
model.load_state_dict(torch.load("migan_512_places2.pt", weights_only=True), strict=True)
model.eval()
torch.onnx.export(
    model, (torch.full((1, 4, 512, 512), 0.25),), out,
    input_names=["input"], output_names=["output"],
    do_constant_folding=True, opset_version=17, dynamo=False,
)
```

导出后把输出 shape 元数据钉死为 `[1, 3, 512, 512]` 并跑 `onnx.checker.check_model`。

**产物**：

```text
bytes        28,026,303
sha256       522048b4fc3fae7adff3af48859cd8d19412d02a8a9420614f3d4474a0901bfe
ir_version   8, opset ai.onnx 17, producer pytorch 2.15.0, 无 external data
graph        798 节点 / 15 种算子 / 144 个 initializer、6,972,424 参数（27.89 MB fp32）
input        input   float32 [1, 4, 512, 512]
output       output  float32 [1, 3, 512, 512]
```

算子清单（798 节点，其中 321 个 `Constant`）：

```text
Constant 321, Conv 94, Mul 79, Clip 65, LeakyRelu 65, Add 29, Reshape 28, Identity 19,
Cast 14, Concat 14, ConstantOfShape 14, Pad 14, Resize 14, Slice 14, Transpose 14
```

* **无** `NonZero`、`ScatterND`、`GatherND`、`Where`、`Range`、`Loop`、`If`、`Scan`、`TopK`
  —— 上游 `MIGAN_Pipeline` 的动态裁切/回贴控制流已不存在；
* 也**无** `MaxPool`/合成算子 —— mask 模糊与回贴不在图内，图就是生成器；
* 所有 `Resize`/`Reshape`/`Slice`/`Pad` 的 shape 参数均可常量折叠（`audit_model.py` 用传递
  闭包证明），因此图对 `[1,4,512,512]` 完全静态。

**数值等价**（参照 `migan_pipeline_v2.onnx`，28,079,181 B，
`sha256 6f1f3530…c48c40b`，与 HuggingFace 仓库 `lfs.oid` 完全一致，即官方未修改的 pipeline）：

方法：对每张样图 ① 跑官方 pipeline 端到端（uint8 图像 + uint8 mask 进，uint8 出）；② 用一份
“探针副本”额外暴露生成器边界张量（输入 `/Concat_23_output_0`、输出
`/model/synthesis/b512/Add_1_output_0`）；③ 把同一输入喂给 `migan_generator_512.onnx`；
④ 按官方后处理回贴后逐字节比较。

样本：`examples/places2_512_object` 全部 20 张，双线性缩放到 512×512，mask 最近邻，
挖洞覆盖率 3.26%–29.58%。

| 比较对象 | 样本 | 像素数 | MAE | MaxAbsDiff | PSNR | 完全一致 |
|---|---:|---:|---:|---:|---|---:|
| 生成器输入：探针 vs Kotlin 侧预处理 | 20 | 20×1,048,576 | 0 | 0 | ∞ | 100% |
| 生成器输出 vs pipeline 内部生成器输出 | 20 | 15,728,640 | 0 | 0 | ∞ | 100% |
| 最终图 vs pipeline 结果 —— 全帧 | 20 | 15,728,640 | 0 | 0 | ∞ | 100% |
| 最终图 vs pipeline 结果 —— 仅洞内 | 20 | 1,688,379 | 0 | 0 | ∞ | 100% |
| 最终图 vs pipeline 结果 —— 已知区域 | 20 | 14,040,261 | 0 | 0 | ∞ | 100% |

逐样本（20 张结果相同，完整精度见 `equivalence.json`）：

```text
10.png  hole=5.01%   gen out: MAE=0.0 MaxAbsDiff=0.0 exact=100.00%
                     e2e    : MAE=0.0 MaxAbsDiff=0.0 exact=100.00%  (hole + known)
pipeline 自检         洞内 mean|out-in| = 19.23 … 67.46（确实在合成内容）
                      已知区域 mean|out-in| = 0.0011 … 0.0082（未被改动）
```

控制实验：对 **大于 512** 的输入（`1024×1024`、`1600×1200`、`900×1400`）走 pipeline 真实
裁切/缩放路径后，生成器输出仍是 `MAE=0 / MaxAbsDiff=0 / 100% 一致`，说明导出的图是 pipeline
的生成器本身，而不只是“全帧退化”这一条路径。边界情形（全已知 mask、全洞 mask、黑图白 mask）
输出均为有限值。

**落库**：`AiModelCatalog.MI_GAN_GENERATOR` 已改为 `version="1"`、
`fileName="migan_generator_512_v1.onnx"`、`byteSize=28_026_303`、
`sha256=522048b4…901bfe`、`mirrorUrls=[GitHub Releases ai-models-v1]`，`isPinned=true`。
GitHub Release 资产尚未上传，因此当前可用安装路径是设置页的 SAF 本地导入（同一字节数 +
SHA-256 校验）；资产上传后无需改代码即可自动走主源下载。`AiModelCatalogTest` 把上述固定值
锁进单元测试。

### 1.4 JVM 单元测试

```bash
./gradlew :editor:testDebugUnitTest --tests "cn.devcxl.photosync.editor.vision.*"
```

`vision` 包 63 个测试全部通过（`AiModelDownloadManagerTest` 16、`OrtSessionManagerTest` 13、
`DeviceMemoryTierTest` 3、`AiModelCatalogTest` 2、`ModelDownloadManagerTest` 6、
`MediaPipeDelegatePolicyTest` 2、`PortraitAnalysisCacheTest` 16、`PortraitAnalyzerBitmapTest` 3、
`PortraitSegmentationTest` 2），`:editor` 全模块 662 个测试通过。本轮新增：

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| `AiModelCatalogTest` | 2 | 每个上架模型都必须可固化（字节数 > 0、64 位小写 SHA-256、非 `unreleased`、`.onnx`、全部 https 镜像）、id/文件名唯一；MI-GAN 生成器-only 的字节数、SHA-256、镜像与许可证逐字段锁定 |

### 1.5 静态质量门禁

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

通过（BUILD SUCCESSFUL，0 error）。Android Lint 只对既有代码给出警告（依赖可升级、未使用旧字符串等）。

### 1.6 Redmi K20 Pro 真机实测（2026-11-26）

**设备**：

| 项 | 值 |
|---|---|
| 型号 | Redmi K20 Pro Premium Edition（`raphael`） |
| SoC | Qualcomm SM8150（骁龙 855） |
| 系统 | Android 11（API 30），MIUI 12.5.2.0.RFKMIXM |
| 内核 | `4.14.180-perf-gc68f062`（`/proc/self/clear_refs` 高水位重置可用，日志 `hwmReset=true`） |
| ABI | `arm64-v8a` |
| MemTotal | 7,680,864 kB（7.3 GiB）→ `DeviceMemoryTier.STANDARD` |

**测试入口**（`editor/src/androidTest/.../OrtModelDeviceBenchmarkTest.kt`）：

```bash
adb push modnet_fp32.onnx migan_generator_512.onnx \
  /sdcard/Android/data/cn.devcxl.photosync.editor.test/files/ort-benchmark-models/
./gradlew :editor:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
cn.devcxl.photosync.editor.vision.OrtModelDeviceBenchmarkTest
```

harness 说明：

* 用**生产** `OnnxRuntimeSessionFactory`（CPU + XNNPACK、`intra_op_num_threads=2`、`ALL_OPT`）
  与生产 `OrtSessionManager`，不修改任何推理配置；
* 模型先按 `AiModelSpec.byteSize` + `AiModelSpec.sha256` 校验（在真机上重算 SHA-256）再拷进
  测试 `cacheDir`，因此测的就是 `AiModelCatalog` 固化的那份字节；
* 每阶段前写 `/proc/self/clear_refs=5` 复位 `VmHWM`，读 `/proc/self/status` 的 `VmRSS`/`VmHWM`
  与 `Debug.getNativeHeapAllocatedSize()`；输入为确定性图案，断言输出 shape 与“全有限、非常量”。

**单模型干净进程数据**（每个模型都是该进程内首个 ORT 会话）：

| 模型 | 档位 | 会话加载 | 首次推理 | 热身 3 次 | VmRSS 基线 | 峰值 VmRSS | 峰值增量 |
|---|---|---:|---:|---|---:|---:|---:|
| MODNet 512（`1×3×512×512` → `1×1×512×512`） | STANDARD | 66.1 ms | 884.9 ms | 829.3 / 808.3 / 807.0 ms | 138.6 MiB | 341.8 MiB | 203.2 MiB |
| MI-GAN 生成器 512（`1×4×512×512` → `1×3×512×512`） | LOW（每次调用重载） | 131.9 ms | 1192.1 ms | 1115.0 / 1143.8 ms | 130.6 MiB | 778.1 MiB | 647.6 MiB |

`Debug.getNativeHeapAllocatedSize()`：MODNet 阶段 15.7 → 349.0 MB，MI-GAN 阶段
11.4 → 1,327.5 MB。该口径包含已预留但未触页的 arena 空间，故大于 `VmHWM`，只用来说明
ORT/XNNPACK 的分配器预留量，不当作 RSS。

**同进程顺序执行**（官方 `connectedDebugAndroidTest` 一次跑完 3 个用例，形态最接近“用户先用
抠像、再用智能消除”）：

| 阶段 | 会话加载 | 首次推理 | 热身 | 基线 RSS | 峰值 RSS | 增量 |
|---|---:|---:|---|---:|---:|---:|
| MODNet STANDARD | 44.0 ms | 753.8 ms | 803.2 / 799.2 / 809.9 ms | 307.7 MiB | 376.3 MiB | 68.6 MiB |
| MI-GAN STANDARD（切换加载） | 103.6 ms | 1236.2 ms | 1218.8 / 1126.6 / 1159.5 ms | 351.2 MiB | 1086.8 MiB | 735.7 MiB |
| MI-GAN LOW | 104.3 ms | 1175.0 ms | 1245.0 / 1197.8 ms | 1077.1 MiB | 1110.9 MiB | 33.8 MiB |

**契约断言（真机全部通过，`tests=3 failures=0`）**：

| 契约 | 实测 |
|---|---|
| 标准档推理后保持单活会话 | `activeSessionCount == 1`，`handles[1].closed == false` |
| 切换模型先关闭旧会话 | `handles.size == 2`、`handles[0].closed == true`、`handles[1].closed == false` |
| 标准档闲置 30 s 释放 | 实测 `waitedMs=30033` 后 `activeSessionCount == 0`，RSS 296.2 → 273.9 MiB |
| `onTrimMemory` 立即释放 | 重新加载后 `onMemoryPressure()` → `activeSessionCount == 0`，两个句柄均关闭 |
| 低端档推理结束立即释放 | 每次调用后 `activeSessionCount == 0`、3 次调用产生 3 次加载且全部 `closed` |
| 关闭管理器后无残留会话 | `phase=standard-release` 与 `phase=low-release` 均为 `sessions=0` |

**结论与风险**：

1. 单活互斥、切换先关闭、档位释放三套契约在真机上成立，`OrtSessionManager` 的语义没有偏差。
2. 推理耗时：MODNet 512 热身 **807–829 ms**（干净进程 3 次均值 815 ms），MI-GAN 512 热身
   **1115–1259 ms**（干净进程 1115–1144 ms，同进程 1127–1219 ms），会话加载 **44–132 ms**。
   这个量级适合“点一下、等 1 秒”的交互，不足以做实时涂抹预览，P2 需要笔刷期间降分辨率或用
   mask 合成占位。
3. **峰值内存是主要风险**：MI-GAN 512 fp32 单模型峰值 **778 MiB**，MODNet **342 MiB**。
   关闭会话后 RSS 只回落 25–45 MiB（arena 不还给 OS），所以同进程先后使用两个模型时峰值会
   累加到 **~1.09 GiB**。7.3 GiB 设备无压力，但对 4 GB 级设备（典型单进程预算 0.5–1.5 GiB）
   已处于 OOM 边缘，见 §2.2。

---

## 2. 剩余缺口

### 2.1 4 GB 级低端机实体设备未实测

当前只有 1 台 7.3 GiB 设备（Redmi K20 Pro）。低端档的**契约**已在同一台设备上以
`DeviceMemoryTier.LOW` 模拟验证（推理结束 `activeSessionCount == 0`、每次调用重新加载并关闭），
但“物理 4 GB 设备上 MI-GAN 是否会 OOM”没有实测，不能用模拟档位替代。

解除缺口所需：一台 ≤4 GB、arm64、Android 8+ 的真机，跑同一条
`connectedDebugAndroidTest` 命令并归档 `VmHWM`；若 MI-GAN 阶段出现 `OUT_OF_MEMORY`，
该结果本身就是 §2.2 的输入，需要先收敛内存再重测。

### 2.2 MI-GAN 峰值内存（P2 前置风险）

真机数据表明 512² fp32 生成器单模型峰值 778 MiB，主要来自高分辨率特征图与 XNNPACK/ORT 的
权重打包与 arena 预留。按当前配置，**MI-GAN 不能承诺在 4 GB 设备上可用**，P2 落地前必须选一条：

| 候选手段 | 预期收益 | 代价 | 状态 |
|---|---|---|---|
| 分块推理（256² 或 384² 滑窗 + 重叠回贴） | 激活内存约降到 1/4–1/2 | 需要处理接缝，耗时随块数上升 | 未验证 |
| fp16 / int8 权重与激活 | 权重减半，RSS 可降 | CPU 上 fp16 未必加速；int8 需校准且可能掉质量 | 未验证（此前 fp16 pipeline 曾在加载期报类型冲突） |
| 关闭 CPU arena / memory pattern（`disableCpuMemArena`、`setMemoryPatternOptimization(false)`） | 峰值可能下降，且释放更彻底 | 每轮重新分配，耗时上升 | 未验证 |
| 把 AI 推理放进可被杀死的独立进程 | 释放即整进程回收，主进程不受累 | 需要跨进程传 bitmap | 未验证 |

在上述任一项落地并复测之前，设置页不应把 MI-GAN 描述成“低端机可用”；模型仍按需下载 +
SHA-256 校验，是否在低端机上提供入口由 P2 的实现决定。

---

## 3. 结论

P0 的运行时接入、Manifest/R8 护栏、单活会话管理、多镜像下载器、SAF 导入、设置页与
MI-GAN 生成器-only 产物固化均已完成，并通过 JVM 单测、Lint、release 构建与 Redmi K20 Pro
真机验证；真机数据同时给出了 P2 必须先解决的内存预算问题。P0 准入门禁只剩“≤4 GB 实体设备
实测”一项未勾选（工单第 5 条已拆分为可核对的三小项）。
