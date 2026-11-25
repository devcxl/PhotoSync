# BYOK 云端生成式图像编辑协议、ComfyUI 接入与客户端安全事实调研

Type: research
Status: resolved
Assignee: subagent/research-byok-cloud
Blocked by: none
Research branch: `research/byok-cloud` (`551bcaa`) → `docs/research/2026-11-26-byok-cloud-edit-protocols-and-security.md`

## Question

围绕 P4 可选云端生成式能力（默认关闭、纯 BYOK 零服务端、支持离线工具云端增强与自然语言局部改图），需要通过一手来源查清以下事实：

1. **主流 BYOK 图像编辑 HTTP API 与自建 ComfyUI 协议面**：
   - 核实当前（2026）主流支持图像编辑/局部重绘（image + optional mask + prompt）的 HTTP API 请求/响应契约：OpenAI Images Edits API（`POST /v1/images/edits` 及 OpenAI-compatible 兼容网关）、Google Gemini 原生图像编辑 API，以及通用 REST 异步轮询模式，提炼最小公共协议抽象。
   - 核实自建 **ComfyUI** 局域网/公网端点的标准 HTTP/WebSocket 工作流执行协议（`/upload/image`、`/upload/mask`、`/prompt`、`/ws` 或 `/history/{prompt_id}`、`/view`），以及用户自定义 workflow JSON 中占位符注入的通用做法与鉴权方式（如反向代理 Bearer/Basic Auth）。
2. **Android `minSdk 24` 上零服务端 API Key 安全存储**：
   - 鉴于 Jetpack `androidx.security:security-crypto`（`EncryptedSharedPreferences`）已被官方标记为废弃（deprecated），核实 Android 官方推荐的替代基线：直接使用 `AndroidKeyStore` + AES-256-GCM 自行封装（或轻量替代方案）在 API 24–36 上的已知坑点（如 KeyStore 损坏/恢复、`allowBackup` 排除、锁屏/设备迁移行为）。
3. **外发图像隐私清洗与传输边界**：
   - Android 端在将照片上传至用户配置的云端端点前，如何彻底剥离 EXIF（GPS、相机序列号、拍摄时间、嵌入式缩略图）并按长边上限重新编码压缩，避免意外泄露敏感元数据或上传超大 RAW 原片？

## Answer

完整一手调研与源码出处见分支 `research/byok-cloud`（commit `551bcaa`）下的 `docs/research/2026-11-26-byok-cloud-edit-protocols-and-security.md`。关键结论：

1. **主流协议面与最小抽象**：
   - **OpenAI**：`POST /v1/images/edits`（multipart，`model`/`prompt`/`image` 必填，`mask` <4 MB 同尺寸 PNG 且**全透明=可编辑**）；`gpt-image-1`（2026-10-23）与 `dall-e-2`（2026-05-12）均已退役，**绝不能硬编码模型名**。
   - **Gemini**：Interactions API `POST /v1beta/interactions`（`x-goog-api-key` + `Api-Revision`），支持 `background:true` 异步轮询与 `store:false` 退出服务端存储，inline 上限 20 MB；**无二值 mask**，局部重绘仅支持语义提示词（`SOFT`）。
   - **ComfyUI**：`/upload/image` → `/upload/mask`（`original_ref` 失效时**静默不写文件但仍返回 200**，需显式防呆）→ `/prompt` → `/history/{prompt_id}` 轮询 → `/view`；无服务端占位符，官方按 node id 替换 `inputs`；默认无鉴权，靠反代加头（`--multi-user` 非认证）。
   - 公共抽象为 `CloudEditProvider` + `capabilities`（`maskMode: HARD/SOFT/NONE`、上传上限、异步标志）+ `submit → poll → fetch`；OpenAI/Gemini POST 无幂等键，超时禁止盲目重试。
2. **Android `minSdk 24` API Key 安全存储**：
   - `androidx.security:security-crypto`（`EncryptedSharedPreferences`）已于 2025 年正式标记 deprecated。
   - 推荐零重依赖方案：直接用 `AndroidKeyStore` AES-256-GCM（不设用户认证以避开锁屏/指纹变更导致的 `KeyPermanentlyInvalidatedException`），由 Keystore 生成随机 IV，密文（`version|ivLen|iv|ciphertext`）存入 **`noBackupFilesDir`**（防 OEM D2D 迁移）；遇 Keystore 损坏则删 alias + 丢密文 + 引导重输。
3. **外发图像隐私清洗与传输边界**：
   - `ExifInterface` **不能彻底剥离**：无 `clearAttributes()` 且 `saveAttributes()` 会将嵌入式缩略图重新写回。
   - **必须通过 `Bitmap::compress` 重新编码**（Skia `SkJpegEncoder` 无 EXIF/XMP/GPS 通道）：bounds → `inSampleSize` → 解码 → 按 EXIF 旋正 → 长边 ≤2048 → JPEG q90（50–100 MP 源图峰值约 26–39 MiB）。
   - `targetSdk 36` 默认禁明文与用户 CA；后续升级至 API 37 前需补 `ACCESS_LOCAL_NETWORK` 权限以兼容局域网 ComfyUI。
