# PhotoSync 端侧与可选云端 AI 工具（P0–P4）完整技术规范

- **状态**：已定稿（Wayfinder 地图 [`.scratch/ai-tools/map.md`](map.md) 全部 9 张工单零待决收敛）
- **日期**：2026-11-26
- **目标定位**：开源、离线优先的 AI 相机伴侣（联机 + 非破坏性修图 + 实用端侧 AI 工具 + 默认关闭的纯 BYOK 云端增强）
- **硬性底线**：
  1. 严守 MIT / Apache-2.0 可商用开源合规（明确排除 NC 许可权重及版权存疑模型）；
  2. 新增端侧 AI 模型 100% 按需下载（绝不内置进 APK/AAB）；
  3. 原始 RAW / JPEG 永不改写，所有 AI 产物纳入非破坏性 `EditGraph` Schema v2 与 Before / After 对比；
  4. P4 云端能力默认关闭、纯 BYOK 零官方后端、外发强制剥离全部 EXIF 与嵌入式缩略图。
- **一手调研与交互原型溯源**：
  - ORT Android 与抠图模型实测：分支 `research/ort-matting`（`547eb20`）→ `docs/research/2026-11-26-ort-android-and-matting-models.md`
  - 端侧消除与天空分割实测：分支 `research/inpainting-sky`（`d3013fd`）→ `docs/research/2026-11-26-on-device-inpainting-and-sky-models.md`
  - BYOK 云端协议与安全基线：分支 `research/byok-cloud`（`551bcaa`）→ `docs/research/2026-11-26-byok-cloud-edit-protocols-and-security.md`
  - 编辑器交互与状态机原型：[`.scratch/ai-tools/prototype-ai-tools-interaction.html`](prototype-ai-tools-interaction.html)

---

## 1. 领域词汇增量（实施 P1–P4 时同步写入 `CONTEXT.md`）

| 规范术语 | 定义 | 避免混用 |
|---|---|---|
| **AiModel（按需 AI 模型）** | 经多镜像下载器或 SAF 本地导入、通过字节数与 SHA-256 校验落盘于 `<filesDir>/models/` 的端侧推理权重包，绝不随安装包内置。 | 内置插件、缓存文件 |
| **SubjectMask（主体蒙版）** | 单张照片的主体前景概率图及其关联参数（原背景 / 透明去背 / 纯色换底 / 背景虚化 / 前后景独立调色）；端侧 v1 由 MODNet + 分块 `GuidedFilter` + FMGE 驱动。 | 人物贴纸、皮肤遮罩（`SkinMask` 专指美肤区域） |
| **InpaintPatch（消除补丁）** | 记录于原始图像空间（pre-crop）的有序局部涂抹修复资产（归一化外接框 + 无损局部 RGB 补丁 + 羽化掩码），独立于后续几何与调色。 | 破坏性涂改、液化网格 |
| **SkyLayer（换天空层）** | 天空区域概率图、天空素材引用（程序化基准 / 用户 SAF 导入 / CC0 下载包）以及前景色彩协调与地平线融合参数的组合。 | 天空滤镜 |
| **CloudEditLayer（云端编辑层）** | 由 P4 BYOK 云端端点返回的非破坏性像素结果层，挂载于 Post-Pass A / Pre-Pass B 空间并绑定 `geometryDigest`，支持不透明度与局部掩码羽化。 | 云端原图、派生副本 |

---

## 2. P0：统一端侧 AI 底座、多镜像分发与内存预算

### 2.1 运行时选型与构建护栏
- **依赖版本**：`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`（通过 `gradle/libs.versions.toml` 管理；arm64 净增 **+18.3 MB**，arm64 APK 约 43 MB；避开 1.32.0 的 +34.8 MB 暴涨及 `onnxruntime-mobile:1.18.0` 停更与 `.ort` 锁死）。
- **与现有 MediaPipe 边界**：保留现有 MediaPipe Tasks（`face_landmarker.task` + `selfie_segmenter.tflite`）专职人脸 478 点关键点与离线美肤兜底；ORT 1.22.0 专职全部新增按需下载视觉模型。近期不用 ORT 替代 MediaPipe。
- **Manifest 与 R8 护栏**：
  - Release 沿用 `packaging.jniLibs.excludes += ["lib/x86/**", "lib/x86_64/**"]` 与 `ndk.debugSymbolLevel = "none"`；
  - `app/proguard-rules.pro` 追加 `-keep class ai.onnxruntime.** { *; }`；
  - `AndroidManifest.xml` 使用 `tools:node="remove"` 移除 `ai.onnxruntime.TelemetryInitializer` ContentProvider，杜绝冷启动前置加载与微软遥测端点通信。
- **Execution Provider**：默认固定 **CPU / XNNPACK**（全量兼容 `minSdk 24`）；**禁用 QNN**（高通专有许可不合规），**不默认启用 NNAPI**（Android 15 已废弃且跨 SoC 回退崩溃风险高）。

### 2.2 务实端侧模型矩阵（100% MIT / Apache-2.0，100% 按需下载）

| 能力 | 端侧 v1 实现 | 许可证 | 体积 / 精度 | 端侧性能与内存基线 | 排除项与热插拔插槽 |
|---|---|---|---|---|---|
| **P1 主体蒙版** | **MODNet** + 分块 `GuidedFilter` + **FMGE** 去色溢 | Apache-2.0（含权重） | **25.9 MB**（FP32 ONNX，禁动态 INT8） | 512² 约 0.14 s / **334 MB** 峰值；band 精修 63 MB + FMGE 52 MB | 排除 BiRefNet-lite（6.8–12 GB 必 OOM）与 ISNet（DIS5K 禁商用）；保留通用 `SubjectSegmenter` 插槽，静物/宠物由 P4 云端或未来 <500 MB 合规模型补齐 |
| **P2 智能消除** | **MI-GAN-512 生成器-only** + Kotlin 侧 128 px 上下文裁切 & 3–5 px 羽化 | 双 MIT（代码 + `LICENSE-WEIGHTS`） | **~28 MB**（FP32，禁 INT8 QDQ） | 512² 0.54 s（旗舰）～5.4 s（低端）；预裁 ≤1024² 防 24MP 内存暴涨 | 排除 LaMa-Dilated（183.6 MB CelebA-HQ 人脸权重）；**P0 前置任务**：从官方 `.pt` 导出生成器-only 固定 512 ONNX（去掉 20+ 动态裁切算子）并锁定 SHA-256 |
| **P3 换天空** | **零模型经典天空分割** + 分块 `GuidedFilter` | 项目自研（MIT） | **0 MB** | 分块 2048² + 32 px 重叠（~200 MB）或 2048 代理上采样 | 排除 SegFormer-B0（NVIDIA 非商用）与社区 176 MB SkySeg（版权存疑）；预留 `SkySegmenter` 按需模型插槽（未来热插拔 2–5 MB 合规权重） |

### 2.3 多镜像按需分发与空间管理
- 扩展现有 `ModelDownloadManager`：
  - `AiModelSpec` 支持有序镜像列表 `mirrorUrls: List<String>`（GitHub Releases 主源 → 国内可达镜像）；任一源失败/超时自动切下一源；
  - 保持 `<filesDir>/models/<model-id>/` 下 `.part` Range 断点续传 → 精确字节数 + SHA-256 强校验 → 原子 `renameTo` 契约；
  - 新增 **SAF 本地 `.onnx` 导入**兜底（复用同一字节数 + SHA-256 强校验）；
  - 设置页新增「AI 模型管理」：展示总占空间、单模型状态/体积/许可证，支持单独删除、重下、一键清理；升级模型版本自动清旧版防孤儿文件；同步维护 `THIRD_PARTY_NOTICES.md`。

### 2.4 内存预算与会话生命周期红线
- **严禁后台预热（回应 issue #33）**：绝不在 `App.onCreate`、主界面浏览或预设条展开时预加载/预热 ORT 模型；仅在用户进入对应 AI 工具并触发操作（或 `LiveTetherPreset` 标准档串行入库）时按需创建 `OrtSession`。
- **单活互斥 + 及时释放**：进程内互斥锁串行 ORT 推理，同一时刻内存最多驻留 **1 个** `OrtSession`；标准档（>4 GB RAM）闲置 **30 s** 或退出 `EditorActivity` / 收到 `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` 立即 `close()`；低端档（≤4 GB RAM）**推理结束立刻 `close()`**。

---

## 3. 非破坏性 `EditGraph` Schema v2、渲染挂载点与透明导出

### 3.1 `EditGraph` Schema v2 扩展与内容寻址外置存储
- Room 维持 **v4 表结构不变**（`photo_edits.schema_version = 2` + `graph_json`），解码器向后兼容 v1 JSON（缺失字段解码为中性默认）。
- 新增顶层可选节点（均支持零开销 `isIdentity()` 短路）：
  - `subject: SubjectOps`（`maskRef`, `bgMode: ORIGINAL|TRANSPARENT|SOLID`, `bgColorArgb`, `blur: 0..100`, `fgExposure/fgTemp/fgSat`, `bgExposure/bgTemp/bgSat`）
  - `inpaint: List<InpaintPatchRef>`（有序列表，每项含归一化 pre-crop `bbox`、`patchRelPath`、`maskRelPath`、`sha256`）
  - `sky: SkyOps`（`maskRef`, `assetType: NONE|PROCEDURAL|CUSTOM|PACK`, `assetIdOrRelPath`, `assetSha256`, `relight: 0..100`, `horizonOffset: -0.3..0.3`, `horizonFeather: 0..100`, `edgeShift: -2..2`）
  - `cloudLayer: CloudEditLayerRef?`（`imageRelPath`, `maskRelPath?`, `sha256`, `opacity: 0..100`, `featherPx`, `geometryDigest`, `providerTag`）
- **外置文件存储与回收**：
  - 沿用 `LiquifyMeshStore` 范式，内容寻址写于 `filesDir/edits/<photoId>/<kind>_<sha256>.<ext>`（唯一临时文件 → `fsync` → 原子 `renameTo` → 读取校验 SHA-256）；
  - 照片执行 `Delete` 时级联删除 `filesDir/edits/<photoId>/` 整目录；
  - 应用冷启动/闲时对无活跃编辑和导出任务的照片目录执行离线 GC，清理数据库 `photo_edits` 未引用的历史废弃资产。

### 3.2 Pass A–D 渲染管线精确顺序

```text
RAW 16-bit / JPEG 源采样
  ├─▶ [Pre-Pass A] InpaintPatch 有序叠加（pre-crop 原图空间，支持后续几何/调色全联动）
  ├─▶ [Pass A] 几何变换 + 横向色差 + uWarpTex（SubjectMask / SkyMask 同步经同一几何映射）
  ├─▶ [Post-Pass A / Pre-Pass B] CloudEditLayer 合成（整图不透明度或局部掩码羽化；绑定 geometryDigest）
  ├─▶ [Pass B] Linear 影调 + Display 色彩
  │      ├─ SubjectMask 前景 / 背景独立相对调色偏移
  │      └─ SkyLayer 前景受光色彩协调（Pre-pass 一次性统计原/新天空 OKLab 均值差）
  ├─▶ [Pass C] 美肤 → 降噪 → 去雾 → 清晰度/纹理 → 锐化 → [末端] SubjectMask 背景虚化
  └─▶ [Pass D] 效果与输出
         ├─ SkyLayer 天空合成（地平线偏移/柔化 + 边缘去白边）或 去背/换纯色底（分块 GuidedFilter + FMGE）
         ├─ 3D LUT → Grain → Vignette（统一作用于合成后整帧）
         └─ 量化输出（透明去背保 RGBA8；其余 RGB）
```

- **几何与 `CloudEditLayer` 一致性守卫**：修改 Pass A 几何导致 `geometryDigest` 失配时，弹出非破坏性确认单（「移除云端层以应用新几何」vs「取消几何改动」）。

### 3.3 跨图工作流与全分辨率透明导出
- **Preset / CopyMask / LiveTetherPreset 边界**：
  - `InpaintPatch` 与 `CloudEditLayer` **绝不**进入 `Preset`、`LiveTetherPreset` 与 `CopyMask`；
  - `SubjectOps` 与 `SkyOps` 参数允许进入 `Preset`（含 `PresetGraphMerger.PRESET_MASK`）与 `CopyMask` 可选轴（仅复制参数，目标图按自身画面计算掩码）；
  - `LiveTetherPreset` 含非零主体/换天参数时，在 `TetheredPhotoIngestor` 串行队列中自动生成掩码与预览；**≤4 GB 低端机或未下载模型时静默降级**为仅套传统调色，不阻塞 USB 相机同步。
- **撤销与对比**：单次涂抹消除、应用/清除云端层、切换换底/换天各为独立撤销事务；长按 **Before / After** 绕过含全部 AI 层的整张 `EditGraph` 直接展示原始相机底片。
- **全分辨率流式透明 PNG 导出（`PngStripSink`）**：
  - 复用 `BakeBandEncoder` 与 `StripSink` 接口，新增零额外原生依赖的 `PngStripSink`（JDK `java.util.zip.Deflater` + `IHDR` + Sub/Paeth 行滤波 + 流式 `IDAT` + `IEND`，O(宽度) 内存，轻松支撑 24–60MP 透明 PNG）；
  - 仅当 `subject.bgMode == TRANSPARENT` 时输出 `image/png`（API 29+ `MediaStore` `IS_PENDING` 原子提交 + 基础 `eXIf` 块），其余路径继续走 `JpegStripSink`（`libjpeg-turbo` + EXIF 写回）；`BatchBake` 按单张 `EditGraph` 自动分流 `.jpg`/`.png`。

---

## 4. P1 / P2 / P3 算法细节冻结

### 4.1 P1 主体发丝精修与去色溢（24–60MP）
- **分块 Guided Filter**：512² 模型输出上采样到 band（2048×640，单 band 仅 **63 MB**），严禁全帧运行 `GuidedFilter.smooth`（24MP 需 1.15 GB）；Tier 1 GPU 走 GLSL 6×Box，Tier 2 退回 CPU band fast guided filter；参数 `r = 12–16 px @1024`、`eps = 1e-4..1e-3`。
- **FMGE 去色溢**（Fast Multi-Level Foreground Estimation, arXiv:2006.14970，`εr = 5e-3`, `ω = 0.1`）：
  1. 在 1024 代理上跑完整 FMGE 初始化低频 `F_lo, B_lo`（28 MB）；
  2. 每个 bake band 双线性放大初值后跑 **2 轮**局部迭代（52 MB），仅写回 `α ∈ (0.02, 0.98)` 过渡带 ±16 px；
  3. 换底合成使用 `out = α·F + (1 - α)·B_new`。

### 4.2 P2 涂抹消除 Crop-Inpaint-Blend
- Kotlin 侧按连通域外接框向外扩充 **128 px 上下文边距**（总裁切块最小 512×512），预裁至 ≤1024² 后缩至 512×512 送入 MI-GAN 生成器（注意掩码约定与训练一致：生成器输入 `float32 [1,4,512,512]`）；
- 推荐单笔外接框 **≤ 256×256 px**（1:1 保细节）；超限时 UI 给琥珀色提醒，用户仍确认时后台按 256 px 网格 + 128 px 重叠分块串行推理；
- 回贴融合默认 **3–5 px 高斯羽化** + 边缘亮度/色度轻匹配（不引入 OpenCV）。

### 4.3 P3 换天空光影协调与零膨胀素材
- **经典天空分割**：顶部种子区域成长 + OKLCH 色度/高亮度连通性 + 垂直空间先验 + 分块 `GuidedFilter(r=8, eps=1e-3)`；天空占比 `< 3%` 给温和提示。
- **前景重光照**：Pre-pass 一次性算好 `(ΔL, Δa, Δb)` 避免分块拼缝；Pass B 按 `relight × (1 - skyMask) × smoothstep(0.08, 0.75, L_fg)` 叠加色度迁移与限幅亮度 `clamp(ΔL, -0.12, +0.12)`；Pass D 提供 `horizonOffset`、`horizonFeather`、`edgeShift` 与过渡带边缘溢色抑制。
- **素材分发**：Shader 内置 **4 组 0 KB 程序化大气基准**（晴空、落日、蓝调、暮云）+ 用户 SAF 导入自拍天空（内容寻址 4096 px 存于 `filesDir/skies/`）+ 预留按需下载 CC0 高清云彩包。

---

## 5. 编辑器交互规范（对应原型 `prototype-ai-tools-interaction.html`）

- **7 Tab 一级底栏**：`activity_editor.xml` Tier 3 扩为 7 等宽 Tab：`预设 | 几何 | 影调 | 色彩 | 细节 | 人像 | AI`（横屏保持右侧 `320dp`）；Tier 2 收纳 `主体 | 消除 | 天空`，P4 关闭时彻底隐藏 `云端`。
- **消除触发节奏与手势优先级**：采用「多笔涂抹 + 橡皮修边 → 点击『确认消除』」模式；任何时刻双指触碰强制中止并丢弃未完成单指画笔笔迹、优先缩放平移画布。
- **异步推理与模型管理**：推理条非阻塞停靠底栏顶部（含模型名、进度与「取消」按钮）；首次触发未下载模型弹出确认底单（含 MB 体积、多镜像说明与 SAF 本地导入入口）；设置页提供统一「AI 模型管理」。

---

## 6. P4：纯 BYOK 云端生成式能力与安全/隐私契约

- **最小接口与 3 类适配器**：基于 JDK `HttpURLConnection` + `org.json` + `android.util.Base64` 实现 `CloudEditProvider`（含 `capabilities` 与 `submit → poll → fetch`）：
  1. **OpenAI-compatible**（`POST {baseUrl}/v1/images/edits`，multipart，硬 Mask `alpha=0` 可编辑，**禁止硬编码模型名**）；
  2. **Gemini Interactions API**（`POST /v1beta/interactions`，`x-goog-api-key`，默认 `store: false`，`maskMode = SOFT` 且 UI 明确提示语义局部编辑不保证严格边界）；
  3. **自建 ComfyUI**（纯 HTTP 轮询：`/upload/image → /upload/mask`（校验返回路径防静默 200）→ 按 node id 注入用户导入的 workflow JSON → `/prompt → /history/{id} → /view`；支持反代 Bearer/Basic/自定义 Auth Header）。
- **防重复扣费**：OpenAI / Gemini POST 超时**禁止自动重试**；ComfyUI 超时先查 `/history/{id}`。
- **密钥与网络安全**：
  - 禁用已废弃的 `security-crypto`；直连 **`AndroidKeyStore` AES-256-GCM**（不绑锁屏/生物认证，Keystore 随机 IV），密文存于 `noBackupFilesDir` 防 OEM D2D 迁移；损坏时清 alias 引导重填；
  - **公网 100% 强制 HTTPS**，不信任用户 CA；仅在用户为自建 ComfyUI 显式开启时允许**应用层二次校验后的字面量私有 IPv4（RFC1918 / `127.0.0.1`）+ 解析后 `isSiteLocalAddress || isLoopbackAddress`** 走明文 HTTP；
  - 对携带认证头的请求**关闭自动重定向（`instanceFollowRedirects = false`）**防凭据跨源泄露；严禁 Timber/Toast/崩溃日志输出密钥或完整请求头；响应体设硬上限 `≤ 32 MB` 且解码前查 bounds；未来升 `targetSdk 37` 前补 `ACCESS_LOCAL_NETWORK`。
- **闭世界外发清洗**：绝不复用 `ExifCopier`；强制走 `inJustDecodeBounds → inSampleSize → 解码 → 按 EXIF 旋正 → 缩放至长边 ≤2048 px → Bitmap.compress(JPEG, 90)` + 随机文件名 `upload.jpg` + 编码后校验零 GPS/机型/序列号/时间/缩略图 + 请求结束 `finally` 删临时文件。

---

## 7. 安全降级矩阵

| 维度 / 触发条件 | 标准档（>4 GB RAM） | 低端档（≤4 GB RAM） | 异常 / 失败兜底 |
|---|---|---|---|
| **ORT 会话常驻** | 单活互斥，闲置 30 s 释放 | 单活互斥，**推理完立即 `close()`** | 捕获 `OutOfMemoryError` / `OrtException`，清会话并返回可恢复提示 |
| **P1 主体精修（24–60MP 烘焙）** | Tier 1 GPU 走 GLSL `GuidedFilter` + FMGE；Tier 2 走 CPU band `GuidedFilter` + FMGE | 同左（均在单 band 63+52 MB 内） | 烘焙期 OOM 自动降级为双线性上采样 alpha + 边缘羽化，**不中断导出** |
| **P1/P3 `LiveTetherPreset` 联机套用** | 模型已就绪时在入库串行队列自动执行 | **静默降级**：仅套传统调色，点开/导出时再按需计算 | 模型未下载时静默降级不阻塞 USB 同步 |
| **GPU 降级档（沿用既有 Tier 1–3）** | Tier 1 `RGBA16F` 全能力 | Tier 2 `RGBA8` 退 CPU band `GuidedFilter` | Tier 3 最小 Shader：禁用背景虚化与复杂天空合成，仅保基础调色 |
| **网络 / 下载 / P4 超时** | 主源 → 镜像自动回退 | 同左 | 保留 `.part` 切 SAF 本地 `.onnx` 导入；P4 超时不自动重试 |

---

## 8. 分阶段交付切分（P0 → P4）与验收门禁

### 交付波次
1. **Wave P0（AI 底座与模型基础设施）**：
   - 接入 `onnxruntime-android:1.22.0`（含 R8 keep、`TelemetryInitializer` 移除、ABI/符号护栏）；
   - 扩展 `ModelDownloadManager`（多镜像回退 + SAF 本地导入 + 设置页模型管理）；
   - **P0 准入门禁**：从官方 `.pt` 导出并托管 MI-GAN 生成器-only 512 ONNX（固化字节数与 SHA-256）；在 1 台标准机（≥8 GB）与 1 台低端机（≤4 GB）实测 MODNet & MI-GAN 延迟和峰值 RSS，验证通过再开 P1。
2. **Wave P1（主体蒙版一模多用 + 透明流式 PNG 导出）**：
   - `EditGraph` Schema v2 编解码（兼容 v1）+ 内容寻址外置存储 + 离线 GC；
   - MODNet 接入 + 分块 `GuidedFilter` + FMGE 去色溢 + Pass B/C/D 挂载；
   - 7 Tab 底栏（新增「AI」→「主体」）+ `PngStripSink` 流式导出 + `Preset`/`LiveTetherPreset` 联动。
3. **Wave P2（智能消除）**：
   - MI-GAN-512 生成器接入 + Kotlin 128 px 裁切/256 px 网格切块/羽化融合；
   - Pre-Pass A `InpaintPatch` 挂载 + 涂抹/橡皮画笔（确认消除模式 + 双指让路 + 超限预警）。
4. **Wave P3（换天空）**：
   - 零模型经典天空分割 + `SkySegmenter` 插槽 + Pre-pass OKLab 统计 + Pass B 重光照 + Pass D 地平线与去白边；
   - 4 组 0 KB 程序化大气基准 + SAF 自定义天空导入 + CC0 按需天空包。
5. **Wave P4（可选纯 BYOK 云端能力）**：
   - `AndroidKeyStore` AES-256-GCM 凭据库 + `noBackupFilesDir` + 闭世界 EXIF 重编码清洗器；
   - `CloudEditProvider` + OpenAI-compatible / Gemini Interactions / ComfyUI 三类适配器 + 局域网私有 IP 守卫；
   - Post-Pass A `CloudEditLayer` + 几何失配守卫 + 默认关闭总开关与首次知情告知。

### 自动化金标验收门禁（每波次合并前必过）
- **JVM 单元测试**：
  - `EditGraph` v1→v2 向后兼容、NaN 校验、`CopyMask` / `PresetGraphMerger`（验证 `InpaintPatch`/`CloudEditLayer` 永不被复制）；
  - `ModelDownloadManager` 多镜像回退、Range 续传、SAF 导入 SHA-256 篡改拒绝；
  - `PngStripSink` 流式编码合法性（标准 PNG 解码比对逐像素无损）；
  - `CloudUrlPolicy` 拦截所有公网 `http://` 与跨源重定向、ComfyUI `/upload/mask` 静默 200 检测、超时不自动重试验证；
  - 外发图片清洗单测：输入含 GPS/Make/Model/DateTime/IFD1 缩略图的测试图，断言清洗后输出零 EXIF 且缩略图为空。
- **Android Instrumented 真机回归**：
  - 24MP RAW/JPEG 透明去背流式导出无 OOM 且输出有效带 Alpha PNG；
  - 低端档（模拟 ≤4 GB）触发 MODNet/MI-GAN 推理后 `OrtSession` 计数归零；
  - `assembleRelease`（含 R8）检查 APK 不含 `x86`/`x86_64`、不含 `TelemetryInitializer` provider、保留 `ai.onnxruntime.**`。
- **文档与合规同步**：
  - 更新 `CONTEXT.md`（§1 领域词汇增量）；
  - 更新 `THIRD_PARTY_NOTICES.md`（ONNX Runtime MIT、MODNet Apache-2.0、MI-GAN MIT + `LICENSE-WEIGHTS`、CC0 天空素材清单）。
