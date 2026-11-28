# P4 云端生成式能力（BYOK / ComfyUI）接口契约、安全存储与隐私边界规范

Type: grilling
Status: resolved
Assignee: devcxl/wayfinder-session
Blocked by:
- [BYOK 云端生成式图像编辑协议、ComfyUI 接入与客户端安全事实调研](03-byok-cloud-edit-protocols-and-security-research.md)
- [非破坏性 AI 资产层、EditGraph 扩展与渲染/导出管线挂载点规范](05-non-destructive-ai-assets-and-editgraph-v2-spec.md)

## Question

基于云端协议调研与非破坏性 AI 资产层规范，锁定 P4 可选云端生成式能力的完整契约，确保离线优先、零后端与零敏感信息泄露：

1. **Provider 抽象与首批适配器**：
   - 定义 `CloudEditProvider` 最小接口（能力声明、任务提交、进度/取消、结果回传与错误码映射），以及首批内置适配器范围（如 OpenAI-compatible、Gemini Image Edit、自定义 ComfyUI Endpoint + 可导入工作流 JSON 模板）。
2. **默认关闭与显式隐私告知**：
   - 锁定全局开关（默认关闭）、首次开启的隐私与费用提示（明确告知照片将发往用户配置的第三方/自建端点，PhotoSync 不经手不存储）、以及每次云端请求前自动剥离 GPS/EXIF 与长边分辨率封顶策略。
3. **凭据与网络安全基线**：
   - 锁定 API Key / 自定义 Auth Header 在 `minSdk 24` 上的 `AndroidKeyStore` AES-GCM 加密存储、`allowBackup` 排除规则、仅允许 HTTPS（仅对明确勾选的局域网/自建 ComfyUI 放开 HTTP 明文提示）以及严禁在 Timber 日志或崩溃报告中输出密钥或完整请求头。
4. **多模式编辑与回写闭环**：
   - 明确如何将端侧已有资产无缝喂给云端（当前编辑态合成图 + 可选 `SubjectMask` 或涂抹 Mask + 自然语言指令），以及云端回传图像如何落盘为非破坏性 `CloudEditLayer`（支持不透明度/边缘羽化融合、撤销与 Before/After 原图对比）。

## Answer

基于 [BYOK 云端生成式图像编辑协议、ComfyUI 接入与客户端安全事实调研](03-byok-cloud-edit-protocols-and-security-research.md) 与 [非破坏性 AI 资产层、EditGraph 扩展与渲染/导出管线挂载点规范](05-non-destructive-ai-assets-and-editgraph-v2-spec.md)，锁定 P4 纯 BYOK 云端能力规范如下：

### 1. `CloudEditProvider` 最小抽象与首批 3 类适配器
- **公共抽象（零新增重型网络/JSON 依赖，复用 `HttpURLConnection` + `org.json` + `android.util.Base64`）**：
  - `CloudProviderCapabilities`：`maskMode: MaskMode (HARD | SOFT | NONE)`、`maxUploadLongEdgePx`、`maxUploadBytes`、`supportsAsyncPoll`、`supportsOptOutStorage`。
  - `suspend fun edit(request: CloudEditRequest, onProgress: (CloudJobStage) -> Unit): Result<CloudEditResult>`（内部封装 `submit → poll → fetch`，严格响应协程取消）。
- **首批内置 3 类适配器**：
  1. **OpenAI-compatible**（`POST {baseUrl}/v1/images/edits`，multipart）：`model` 必填且**绝不硬编码**（由用户在配置页填写/选择）；支持同尺寸 PNG 硬 Mask（**alpha = 0 表示可编辑**）；仅支持 `b64_json` / URL 安全读取。
  2. **Gemini Interactions API**（`POST /v1beta/interactions`，`x-goog-api-key` + `Api-Revision`）：支持 `background: true` 异步轮询与默认传 `store: false` 退出服务端留存；`maskMode = SOFT`（无二值硬 mask，选区通过提示词约束，UI 须显式提示「不保证仅修改选区」）。
  3. **自建 ComfyUI**（纯 HTTP 轮询，无需 WebSocket 依赖）：`/upload/image` → `/upload/mask`（`original_ref` 必填，上传后须二次校验返回路径非空以防静默 200 陷阱）→ 按用户绑定的 node id 注入 `inputs` 并提交 `/prompt` → 轮询 `/history/{prompt_id}` → `/view` 拉取结果；支持导入用户自定义 workflow JSON 与可选反代 Auth Header（Bearer / Basic / 自定义头）。
- **防重复扣费策略**：OpenAI 与 Gemini 的 POST 编辑接口无幂等键，网络读超时**严禁自动重试**（直接上报可恢复错误由用户决定）；ComfyUI 超时后先查 `/history/{prompt_id}` 确认状态再决定是否重提。

### 2. 默认关闭、首次告知与隐私清洗管线
- **默认关闭**：全局 P4 开关默认 `false`；关闭状态下编辑器的「云端」子工具完全不可见，绝不发起任何云端请求。
- **首次开启显式告知**：用户首次发起云端请求前弹出不可静默跳过的知情底单，明示照片将在清洗后直发用户自配端点、PhotoSync 零服务端不中转不留存，并提示第三方服务可能产生费用。
- **闭世界外发清洗（绝不复用 `ExifCopier`）**：
  - 管线：`inJustDecodeBounds` → 读取 EXIF 方向 → 计算 2 的幂 `inSampleSize` → `BitmapFactory.decodeFile` → 按方向旋正 → 精确双线性缩放至长边 `≤ 2048 px`（可按 Provider 上限收紧）→ `Bitmap.compress(JPEG, 90)` 写入临时文件 → 编码后验证无 GPS/Make/Model/DateTime/Thumbnail。
  - 上传文件名固定使用随机无语义名（如 `upload.jpg`），严禁携带本地原路径或相机原始文件名；请求完成或取消后在 `finally` 中立即删除临时上传文件。
  - 若附带局部 Mask，按同一目标分辨率生成 PNG（白色笔迹转 `alpha = 255 - mask`，保证 `alpha = 0` 为可编辑区）。

### 3. 凭据安全存储与网络安全边界
- **API Key 加密存储**：不引入已废弃的 `androidx.security:security-crypto`；直连 **`AndroidKeyStore`（`AES/GCM/NoPadding` 256-bit）**，不绑定锁屏/生物认证（避免改锁屏触发 `KeyPermanentlyInvalidatedException`），由 Keystore 生成随机 IV，将 `version | ivLen | iv | ciphertext` 密文存于 `context.noBackupFilesDir`（防止 OEM D2D 迁移泄漏）；遇 Keystore 损坏时清除 alias、丢弃密文并提示用户重填。
- **HTTPS 强制与局域网 ComfyUI 例外守卫**：
  - 公网端点**100% 强制 HTTPS**，不信任用户 CA。
  - 仅在用户为自建 ComfyUI 显式勾选「允许局域网明文 HTTP」时放行，且应用层在建立连接前强制校验目标为**字面量私有 IPv4（RFC1918 / `127.0.0.1`）且解析后 `InetAddress.isSiteLocalAddress || isLoopbackAddress`**；任何指向公网域名或公网 IP 的 `http://` 请求在发包前直接硬拒绝。
  - **跨源重定向禁令**：对携带认证头的请求关闭自动重定向（`instanceFollowRedirects = false`），防止重定向将 API Key 泄漏给非目标主机。
  - **未来升级注记**：升级至 `targetSdk 37`（Android 17）前须补充 `ACCESS_LOCAL_NETWORK` 权限声明以维持局域网 ComfyUI 连通性。
- **零日志泄露与响应体边界**：严禁在 Timber 日志、异常堆栈或 Toast 中输出 API Key、Auth Header 或完整请求/响应体；拉取云端结果设置硬性响应字节上限（`≤ 32 MB`）及连接/读取超时，解码前用 `inJustDecodeBounds` 校验宽高防恶意大图 OOM。

### 4. 多模式输入与回写 `CloudEditLayer` 闭环
- **输入组合**：以当前 Post-Pass A 画面（剥离 EXIF 并缩至 `≤ 2048 px`）为基础，支持三种编辑形态——「整图自然语言改图」「带涂抹 Mask 局部重绘」「带 `SubjectMask` 反选背景替换」。
- **回写与融合**：云端返回像素经内容寻址写入 `filesDir/edits/<photoId>/cloud_<sha256>.png`，挂载到 `EditGraph` 的 Post-Pass A / Pre-Pass B `CloudEditLayer`（记录当前 `geometryDigest`、不透明度默认 `100%`、局部模式记录羽化掩码），支持继续本地调色/套 3D LUT、单步撤销与长按 Before/After 对比原片。
