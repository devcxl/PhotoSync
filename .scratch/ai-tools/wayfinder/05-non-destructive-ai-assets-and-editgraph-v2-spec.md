# 非破坏性 AI 资产层、EditGraph 扩展与渲染/导出管线挂载点规范

Type: grilling
Status: resolved
Assignee: devcxl/wayfinder-session
Blocked by:
- [ONNX Runtime Android 运行时体积、硬件加速与通用抠图/发丝模型事实调研](01-onnx-runtime-mobile-and-matting-models-research.md)
- [端侧智能消除（MI-GAN / LaMa-Dilated）与天空分割模型事实调研](02-on-device-inpainting-and-sky-models-research.md)
- [端侧 AI 底座、多镜像按需分发与低内存预算规范](04-ai-runtime-distribution-and-memory-budget-spec.md)

## Question

锁定 P1–P4 各类 AI 产物如何无违和地融入 PhotoSync 现有的非破坏性 `EditGraph`、GLES 3.0 `Pass A–D` 渲染链与 `BakeExporter` 全分辨率列带导出体系：

1. **领域概念与外置资产模型（同步更新 `CONTEXT.md`）**：
   - 定义 `SubjectMask`（主体蒙版）、`InpaintPatch`（局部消除补丁）、`SkyLayer`（换天空层）与 `CloudEditLayer`（云端回传图层）的精准领域术语与存储格式（仿照现有 `LiquifyMesh` 外置二进制文件模式，避免撑爆 Room 2MB `CursorWindow`，并定义照片删除/重置时的孤儿文件垃圾回收机制）。
2. **Pass A–D 渲染管线挂载顺序与一模多用契约**：
   - 锁定 `InpaintPatch` 与 `CloudEditLayer` 挂在几何（Pass A）与影调色彩（Pass B）之间的精确位置，使消除/云端修改后叠加调色与预设自然生效；
   - 锁定 `SubjectMask` 如何同时服务于「背景去除/换纯色底」「模拟背景虚化」「前景/背景独立调色」三种用法，以及 `GuidedFilter` 高分边缘对齐和发丝去色溢在实时预览与全分辨率烘焙中的一致实现。
3. **工作流边界（Preset / LiveTetherPreset / CopyMask / Undo / Before-After）**：
   - 明确哪些 AI 调节参数可以随 `Preset` / `LiveTetherPreset` / `CopyMask` 跨图迁移（例如背景虚化强度、前景/背景相对调色偏移），哪些单图像素资产绝不跨图复制（例如 `InpaintPatch` 涂抹补丁、`CloudEditLayer` 像素结果）。
   - 明确各 AI 操作的撤销事务粒度与 Before / After 原图对比行为。
4. **透明导出扩展**：
   - 现有 `BakeExporter` 仅经 `libjpeg-turbo` 导出 JPEG；锁定「背景去除」导出透明图时采用的编码格式（PNG / 无损或有损 WebP）、流式/内存控制策略与 `MediaStore` / EXIF 写回契约。

## Answer

基于现有 `EditGraph`、`LiquifyMeshStore`、`CopyMask` / `PresetGraphMerger` 与 `BakeBandEncoder` / `StripSink` 架构，锁定非破坏性 AI 资产层与渲染/导出挂载点规范如下：

### 1. 领域模型与外置资产存储（`EditGraph` Schema v2，待落地同步更新 `CONTEXT.md`）
- **保持 Room v4 表结构不变**：继续复用 `photo_edits`（`schema_version = 2` + `graph_json`），向后兼容读取 v1 JSON（缺失的 AI 节点全部解码为中性默认/空）。
- **四大领域实体（纯概念定义将同步写入 `CONTEXT.md`）**：
  1. **`SubjectMask`（主体蒙版）**：外置内容寻址单通道掩码缓存（512–1024 分辨率，16-bit/8-bit 压缩文件 + SHA-256），配套 `SubjectOps` 参数节点（背景模式：原图 / 透明去背 / 纯色底 / 模拟背景虚化强度；以及前景与背景独立的相对曝光/色温/饱和度偏移）。
  2. **`InpaintPatch`（消除补丁）**：存于原始图像空间（pre-crop）的有序局部修复列表；每条记录包含归一化外接框、外置无损局部 RGB 补丁（PNG/Deflate）与羽化 alpha 掩码的相对路径及 SHA-256。
  3. **`SkyLayer`（换天空层）**：外置天空概率掩码缓存 + `SkyOps` 参数节点（选中天空素材 ID/路径、地平线偏移、边缘羽化、前景色彩协调强度等；具体融合算法与素材分发由下游新毕业工单细化）。
  4. **`CloudEditLayer`（云端编辑层）**：P4 BYOK 云端返回的外置无损/高质像素结果层（存于 Post-Pass A 空间，记录外置图像路径、可选局部掩码路径、SHA-256、不透明度 `0..100`、羽化像素、以及生成时绑定的几何签名 `geometryDigest`）。
- **存储与回收契约**：
  - 全部外置资产统一沿用 `LiquifyMeshStore` 范式，落于 `filesDir/edits/<photoId>/<kind>_<sha256>.<ext>`，写盘走「唯一临时文件 → `fsync` → 原子 `renameTo`」，读取前校验 SHA-256，绝不就地覆盖已提交文件。
  - **回收机制**：单张照片执行 `Delete` 时级联删除 `filesDir/edits/<photoId>/` 整目录；另设应用冷启动/闲时离线扫描（仅对当前无活跃编辑会话与导出任务的照片目录），清理未被数据库 `photo_edits` 引用的过期历史资产，防止大图像素层长期累积占满存储。

### 2. Pass A–D 渲染管线精确挂载顺序

```
原图源采样（RAW 16-bit / JPEG）
  └─▶ [Pre-Pass A] InpaintPatch 有序叠合（原图 pre-crop 空间）
        └─▶ [Pass A] 几何变换（裁剪 / 旋转 / 拉直 / 透视 / 横向色差 / uWarpTex）
              │      ※ SubjectMask 与 SkyMask 同步经同一几何映射
              └─▶ [Post-Pass A / Pre-Pass B] CloudEditLayer 合成（整图不透明度或局部掩码羽化）
                    └─▶ [Pass B] Linear 影调 + Display 色彩
                          │      ※ 挂载 SubjectMask 前景/背景独立相对调色偏移
                          │      ※ 挂载 SkyLayer 前景色彩协调（P3）
                          └─▶ [Pass C] 空间细节（美肤 → 降噪 → 去雾 → 清晰度/纹理 → 锐化）
                                │      ※ 末端挂载 SubjectMask 驱动的背景虚化（保持前景锐利）
                                └─▶ [Pass D] 效果与输出
                                       ├─ 天空合成（SkyLayer）或 去背/换纯色底（分块 GuidedFilter + FMGE 去色溢）
                                       ├─ 3D LUT → Grain → Vignette（统一作用于合成后整帧，保证观感一致）
                                       └─ 量化输出（不透明走 RGB，透明去背保留 Alpha 通道）
```

- **几何与云端层一致性守卫**：因为 `CloudEditLayer` 绑定在 Post-Pass A 空间（确保云端改图后仍可自由调色/套 LUT/切预设），若用户事后修改 Pass A 几何（裁剪/旋转/透视/液化），导致 `geometryDigest` 失配，UI 弹出非破坏性提示，由用户选择「隐藏/移除云端层以应用新几何」或「取消几何改动」。
- **零开销短路**：当照片未启用对应 AI 层时，相关阶段保持 0 次额外纹理采样与 0 Draw Call 短路，不影响普通照片性能。

### 3. 工作流边界（Preset / LiveTetherPreset / CopyMask / Undo / Before-After）
- **绝不跨图复制的单图资产**：`InpaintPatch` 与 `CloudEditLayer` 永远不进入 `Preset`、`LiveTetherPreset` 与 `CopyMask`（避免把一张照片的涂抹补丁或云端画面张冠李戴到另一张）。
- **可跨图复制的 AI 参数轴**：
  - `SubjectOps`（去背/纯色底、背景虚化、前后景相对调色）与 `SkyOps`（换天模板与融合参数）纳入 `Preset`（含 `PresetGraphMerger.PRESET_MASK`）与 `CopyMask` 可选勾选轴（注意：复制的是参数而非源图掩码，目标图按自身画面计算掩码）。
  - **支持 `LiveTetherPreset` 联机串行自动套用**：当激活的 `LiveTetherPreset` 包含非零 `SubjectOps` 或 `SkyOps` 时，`TetheredPhotoIngestor` 在现有串行入库队列中自动为新照片生成对应掩码与预览；若设备处于 **≤4 GB 低内存档** 或 **所需 MODNet 模型未下载**，则静默降级为仅套用传统调色参数并记录降级标记，绝不阻塞相机 USB 同步。
- **撤销与对比**：
  - 每次涂抹消除、生成/移除云端层、切换换底/换天各作为一个独立撤销事务；参数滑杆沿用 300 ms 撤销合并。
  - 长按 **Before / After** 始终绕过包括全部 AI 层在内的整张 `EditGraph`，直接展示原始未编辑相机画面；主界面选择「无预设」重置仍按既有语义重置整张图（并清除外置 AI 层引用）。

### 4. 透明去背全分辨率流式导出（`BakeExporter` 扩展）
- **编码格式**：启用「透明去背」导出时采用无损 **PNG（RGBA8）**；未启用透明去背时（含换纯色底、背景虚化、消除、换天、云端改图）继续走现有 `JpegStripSink`（`libjpeg-turbo` + EXIF 白名单写回）。
- **流式实现（零额外原生库）**：基于现有 `BakeBandEncoder` 的 `StripSink` 接口新增 **`PngStripSink`**，使用 JDK 内置 `java.util.zip.Deflater` 与标准 PNG 分块流（`IHDR` + Sub/Paeth 行滤波 + 流式 `IDAT` + `IEND`），内存复杂度维持 **O(图像宽度)**，轻松支撑 24MP–60MP 透明 PNG 导出而不整图进 `Bitmap`。
- **相册落盘与元数据**：经 `MediaStore` 写入 `image/png`（API 29+ 沿用 `IS_PENDING` 原子提交）；PNG 写入基础 `eXIf` 块（拍摄时间/机型，剥离或置 `Orientation=1`），并在 `BatchBake` 批量队列中按单张 `EditGraph` 自动分流 `.jpg` / `.png`。
