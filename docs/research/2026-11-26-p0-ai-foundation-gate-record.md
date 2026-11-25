# P0 端侧 AI 底座验收记录（ORT 1.22.0、模型分发与真机门禁）

- 日期：2026-11-26
- 关联工单：`.scratch/ai-tools/issues/01-p0-ort-runtime-model-manager-and-device-gate.md`
- 关联规范：`.scratch/ai-tools/spec.md` §2、§7–§8
- 上游调研：`docs/research/2026-11-26-ort-android-and-matting-models.md`、`docs/research/2026-11-26-on-device-inpainting-and-sky-models.md`

> 本文只记录本次实际执行过的验证。凡是当前环境无法执行的项（MI-GAN 生成器-only 导出、真机延迟/RSS）都明确标为阻塞，不用 x86 数据冒充真机数据。

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

### 1.3 模型清单与完整性固定值（MODNet）

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

### 1.4 JVM 单元测试

```bash
./gradlew :editor:testDebugUnitTest --tests "cn.devcxl.photosync.editor.vision.*"
```

新增 32 个测试全部通过：

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| `AiModelDownloadManagerTest` | 16 | 主源失败切镜像、镜像返回损坏文件后切镜像、Range 续传、服务端忽略 Range 时从头下载、全镜像 SHA 失败删除文件、已安装复用、缓存哈希错误替换、SAF 导入成功/篡改/偏短/偏长、总空间与单模型空间、单模型删除、旧版本清理、未固化模型拒绝、`.onnx` 文件名校验 |
| `OrtSessionManagerTest` | 13 | 构造不加载、模型缺失、同模型复用、切换模型先关闭旧会话、标准档 30 s 闲置释放、未到 TTL 时重排、低端档推理后立即释放、推理 OOM / OrtException 可恢复失败、工厂 OrtException、内存压力不加载/释放、双线程串行 |
| `DeviceMemoryTierTest` | 3 | 4 GiB 边界、低端档、未知内存按低端档处理 |

### 1.5 静态质量门禁

```bash
./gradlew :app:lintDebug
```

通过（0 error）。Android Lint 只对既有代码给出警告（依赖可升级、未使用旧字符串等）。

---

## 2. 阻塞项（未验证，不伪造数据）

### 2.1 MI-GAN 生成器-only 512 ONNX 导出

当前环境：

```text
python3 --version                 → Python 3.14.10
python3 -c "import torch"         → ModuleNotFoundError: No module named 'torch'
python3 -c "import onnx"          → ModuleNotFoundError: No module named 'onnx'
python3 -c "import onnxruntime"   → ModuleNotFoundError: No module named 'onnxruntime'
find . -name "*.pt"               → 无结果
```

官方权重在 Google Drive，需要人工确认下载（见 MI-GAN 仓库 README）；本机与工作区内没有
`.pt` 权重，也没有 PyTorch/ONNX 工具链。因此**无法**完成“从官方 `.pt` 导出生成器-only
固定 512 ONNX、去掉动态裁切算子、固化字节数与 SHA-256”这一步，`AiModelCatalog.MI_GAN_GENERATOR`
保持 `isPinned = false`，设置页显示“待发布”，下载与导入按钮不提供。

不使用 HuggingFace 的 `migan.onnx` 反推：它的 I/O 与 Pipeline v2 相同（uint8 图像 + mask），
包含裁切/缩放/羽化/回贴，不是生成器-only；在没有 `.pt` 与对照输出的前提下做图手术无法证明
数值等价。

解除阻塞所需：

1. 在独立虚拟环境安装 PyTorch（CPU 即可）与 `onnx`；
2. 人工从官方渠道取得 `.pt` 权重并校验来源；
3. 按官方 `scripts/create_onnx_pipeline.py` 的裁切前逻辑导出生成器子图，固定 `[1,4,512,512]`
   float 输入、`[1,3,512,512]` float 输出；
4. 记录导出文件的精确字节数与 SHA-256，写入 `AiModelCatalog.MI_GAN_GENERATOR` 并上传到
   GitHub Releases（主源）与备用镜像；
5. 用同一张样图对比 `.pt` 与 ONNX 的输出误差，确认图手术/导出没有改变数值。

### 2.2 真机延迟与峰值 RSS

```text
adb devices
List of devices attached
（无设备）
```

当前没有可用真机，无法在“≥8 GB 标准机”和“≤4 GB 低端机”上记录 MODNet / MI-GAN 的推理耗时
与峰值 RSS。`docs/research/2026-11-26-ort-android-and-matting-models.md` §4.6 的 334 MB /
0.137 s 是 x86_64 桌面 CPU 数据，**不能**当作手机数据。

解除阻塞所需：

1. 连接一台 ≥8 GB 与一台 ≤4 GB 的 Android 真机（arm64），开启 USB 调试；
2. 通过 `connectedAndroidTest` 或独立 benchmark APK 分别加载 MODNet 512² 与 MI-GAN 512²，
   每模型独立进程，记录首帧/中位延迟与 `Debug.getMemoryInfo` / `dumpsys meminfo` 峰值 RSS；
3. 低端机额外断言推理结束后 `OrtSessionManager.activeSessionCount == 0`；
4. 把实测值补进本文与工单第 5 条复选框。

---

## 3. 结论

P0 的运行时接入、Manifest/R8 护栏、单活会话管理、多镜像下载器、SAF 导入与设置页均已实现并
通过 JVM 单测、Lint 与 release 构建验证。P0 准入门禁中“MI-GAN 生成器-only 产物固化”和
“标准机/低端机实测”两项因缺少 `.pt` 权重、PyTorch 工具链与真机而阻塞，须在具备上述前提的
机器上补齐后才能勾选工单第 5 条。
