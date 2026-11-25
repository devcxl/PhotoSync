# BYOK 云端生成式图像编辑：协议面、ComfyUI 接入与 Android 端密钥/隐私边界

- 日期：2026-11-26
- 关联工单：Wayfinder「BYOK 云端生成式图像编辑协议、ComfyUI 接入与客户端安全事实调研」
- 上游基线：`docs/research/2026-11-21-image-editor-pipeline.md`、`docs/research/2026-11-22-libjpeg-turbo-streaming-exif.md`
- 引用版本（抓取时间 2026-11-25/26 UTC）：
  - OpenAI OpenAPI `2.3.0`（`openapi.yaml`，4,338,319 B）
  - Gemini Interactions API `v1beta`，REST 示例固定 `Api-Revision: 2026-05-20`
  - ComfyUI `master` @ `a2103ae69d98d8ef94c150f5a472b89775eed929`
  - `androidx.security:security-crypto:1.1.0`（stable，2025-07-30）
  - `com.google.crypto.tink:tink-android:1.24.0`（3,508,938 B / 1,993 个 class）
  - `androidx.exifinterface:exifinterface:1.4.2`（AAR `minSdkVersion=21`）
  - AOSP `platform_frameworks_base` `main` @ `1cdfff555f4a21f71ccc978290e2e212e2f8b168`
- 仓库基线：`app` / `editor` 均为 `minSdk 24`、`compileSdk 36`、`targetSdk 36`；`android:allowBackup="false"`；HTTP 栈为 `HttpURLConnection`；未引入 OkHttp / Gson / Moshi / security-crypto。
- 引用约定：外部事实全部给一手文档或上游源码链接（附录 B 汇总）；源码引用尽量给 `文件:行号`。凡属推断、投影或未在真机验证的结论，正文标注「推断」「投影」「未验证」，并汇总在 §9。

> 本文不设计 UI，不调用真实付费 API，也不修改仓库代码。目标是把「客户端要面对的三类协议」压成一个可实现的最小抽象，并把 API Key 存储与图片外发清洗的边界钉死。

---

## 0. 结论摘要

1. **OpenAI 图像编辑的稳定公共面是 multipart `POST /v1/images/edits`**：`model`、`prompt`、`image` 必填；最多 16 张、每张 `<50 MB` 的 PNG/WebP/JPEG；`mask` 必须是 `<4 MB`、与首图同尺寸的 PNG，**全透明像素表示允许编辑的区域**。GPT Image 模型只返回 base64，`response_format` 已不适用于它们。[OPENAI-SPEC]
2. **OpenAI 的模型生命周期不能硬编码**：`gpt-image-1` 于 2026-10-23 关停，`dall-e-2` 于 2026-05-12 退役；当前示例模型是 `gpt-image-2.6-flare`。客户端必须把模型做成用户可配置项，并在 400/403/410 时给出「换模型/换端点」的可恢复错误。[OPENAI-SPEC]
3. **Gemini 在 2026 的图像编辑入口是 Interactions API**：`POST https://generativelanguage.googleapis.com/v1beta/interactions`，`x-goog-api-key` 认证，输入是 `{type:"text"}` / `{type:"image", mime_type, data|uri}` 数组；REST 响应里图片在 `steps[].content[]` 的 `type:"image"` 块（base64），SDK 才把它包装成 `interaction.output_image.data`。官方图像编辑 REST 示例带 `Api-Revision: 2026-05-20`。[GEMINI-IMG][GEMINI-INTERACTIONS]
4. **Gemini 没有二值 mask 参数**：官方把局部重绘叫「semantic masking」，靠提示词限定「只改某个元素，其余保持不变」。因此同一套「画笔 mask」在 Gemini 上只能算软约束（SOFT），不能像 OpenAI/ComfyUI 那样当硬约束（HARD）。[GEMINI-IMG]
5. **Gemini 原生支持异步**：`background: true` 立即返回 `status: in_progress` 的 interaction，随后 `GET /v1beta/interactions/{id}` 轮询到 `completed`；`store: false` 可显式退出服务端存储。多轮 `previous_interaction_id` 则需要服务端保留状态，二者是隐私/连续性的取舍。[GEMINI-GETSTARTED][GEMINI-INTERACTIONS]
6. **ComfyUI 的原始 Server API 是「可用的、但没有稳定性承诺的」自建面**：`/upload/image`、`/upload/mask`、`/prompt`、`/history/{prompt_id}`、`/view`、`/ws` 均在 `server.py` 中定义；官方明确说 raw Server API「no compatibility guarantee across releases」。[COMFY-ROUTES][COMFY-RUN]
7. **ComfyUI 默认没有任何鉴权**：官方原文「The ComfyUI server has no authentication by default. Only bind to a non-loopback address on a network you trust, or put a reverse proxy with authentication in front of it.」`--multi-user` / `comfy-user` 只是存储分区，不是认证，任何客户端都能伪造该 header。[COMFY-SELFHOST][COMFY-SRC-USER]
8. **ComfyUI 工作流的「占位符」不是服务端模板**：官方约定是导出 API 格式 JSON（`{"<node_id>": {"class_type", "inputs"}}`），客户端按 node id 改 `inputs`；官方示例就是 `prompt["6"]["inputs"]["text"] = ...`。`{{prompt}}` 之类只是客户端自己的替换约定，服务端只按 `/object_info` 校验节点输入。[COMFY-EXAMPLES][COMFY-SRC-SERVER]
9. **三类服务可以压成同一个最小抽象**：`capabilities`（mask 为 HARD/SOFT/NONE、最大上传、是否异步）+ `submit → poll → fetch` + 统一错误分类。OpenAI 是「同步 POST + base64 响应」，Gemini 是「同步或 background 轮询 + base64 响应」，ComfyUI 是「multipart 上传 + 队列 + `/history` + 二进制 `/view`」。[OPENAI-SPEC][GEMINI-GETSTARTED][COMFY-ROUTES]
10. **`androidx.security:security-crypto` 已经废弃，stable 也不例外**：所有 API 在 `1.1.0-alpha07`（2025-04-09）被标记 deprecated，`1.1.0`（2025-07-30）stable 仍保持 deprecated；官方参考页直接写「This class is deprecated. Use SharedPreferences instead.」，发行说明写「in favour of existing platform APIs and direct use of Android Keystore」。[SEC-RELEASE][ESP-REF]
11. **推荐的零服务端密钥存储是 AndroidKeyStore + AES-256-GCM**：不需要用户认证的密钥，`Cipher.init(ENCRYPT_MODE, key)` 让 Keystore 生成随机 IV，再把 `iv || ciphertext` 存到 `noBackupFilesDir`；默认的 `setRandomizedEncryptionRequired(true)` 会拒绝调用方自带 IV。[KEYGEN-BUILDER]
12. **必须为 Keystore 失效设计「破坏性恢复」**：锁屏被移除/重置、指纹重新录入会让 auth-bound key 抛 `KeyPermanentlyInvalidatedException`；密钥存在但不可用时 Tink 也明确抛 `KeystoreException`，并说明不能靠重新生成恢复，因为旧密文还在。可移植策略只有：删除 alias、丢弃密文、提示用户重新输入 Key。[KPI][TINK-KEYSET]
13. **`ExifInterface` 不能承诺「彻底剥离」**：它没有 `clearAttributes()`，`setAttribute(tag, null)` 只能逐个删已知 tag；`saveAttributes()` 会先把缩略图读进内存（`mThumbnailBytes = getThumbnail()`），再在重写 EXIF 段时把缩略图偏移/长度写回去。也就是说，**嵌入式缩略图无法通过公开 API 删除**。[EXIF-SRC]
14. **重编码是唯一「闭世界」的清洗路径**：AOSP 的 `Bitmap::compress` 对 JPEG 只构造 `SkJpegEncoder::Options { fQuality = quality }` 并调用 `SkJpegEncoder::Encode(stream, bitmap.pixmap(), options)`；输入只有像素，没有 EXIF/XMP/GPS 通道。重编码同时完成「缩放到长边上限」和「剥离元数据」两件事，且与仓库现有的 `BitmapFactory` + `ExifInterface` 依赖完全契合。[AOSP-BITMAP-HWUI][SKIA-JPEG]
15. **Android 侧真正的工程约束是内存与传输形态**：OpenAI/Gemini 都把结果放在 base64 JSON 里，峰值可达原始字节的 ~2.7 倍（base64 + JSON 字符串 + 解码副本，投影）；ComfyUI 的 `/view` 是唯一的纯二进制下载路径，最适合 `minSdk 24` 设备。仓库没有 JSON 库，可用平台 `org.json`；`java.util.Base64` 在 Android 上要 API 26，`minSdk 24` 必须用 `android.util.Base64`。[BASE64-REF]
16. **自建 ComfyUI 在 Android 上不是「填个 IP 就能用」**：`targetSdk 36` 默认禁止明文 HTTP、默认不信任用户安装的 CA；Android 17（API 37）起，`targetSdk >= 37` 的应用访问局域网还需要 `ACCESS_LOCAL_NETWORK` 运行时权限，未授权时 TCP 表现为超时。自建场景应要求 HTTPS（ComfyUI 自带 `--tls-keyfile/--tls-certfile`）或隧道/反代，而不是全局放开 cleartext。[NSC][LNP][COMFY-FLAGS]

---

## 1. 仓库基线与调研边界

| 项 | 值 | 证据 |
|---|---|---|
| `app` SDK | `minSdk 24` / `targetSdk 36` / `compileSdk 36` | `app/build.gradle:9-14` |
| `editor` SDK | `minSdk 24` / `compileSdk 36` | `editor/build.gradle:8-11` |
| 备份 | `android:allowBackup="false"`，未声明 `dataExtractionRules` / `fullBackupContent` | `app/src/main/AndroidManifest.xml:11` |
| HTTP 栈 | `HttpURLConnection`（带 `Range` 的模型下载器） | `editor/src/main/java/cn/devcxl/photosync/editor/vision/ModelDownloadManager.kt:45-53` |
| JSON | 无第三方 JSON 依赖（可用平台 `org.json`） | `gradle/libs.versions.toml` |
| EXIF | `androidx.exifinterface:exifinterface:1.4.2` | `gradle/libs.versions.toml` |
| 导出链路 | `BakeExporter` + `ExifCopier`（会把 GPS/序列号写回导出图） | `editor/.../export/ExifCopier.kt:46-104` |

调研边界：

- 不评估各家模型的实际画质、价格、内容审核策略与训练/保留政策；这些是产品与合规决策，不能从 API 契约推出。
- 不设计设置页、任务队列 UI 或提示词模板；只给协议抽象与安全边界。
- 不修改仓库；所有代码片段是「可直接落地的形状」，不是已编译实现。

---

## 2. OpenAI Images Edits：契约、模型生命周期与兼容网关

### 2.1 端点与认证

- `POST https://api.openai.com/v1/images/edits`，`operationId: createImageEdit`；OpenAPI 的全局安全方案是 `ApiKeyAuth: http bearer`，即 `Authorization: Bearer <API_KEY>`。[OPENAI-SPEC]
- 请求体支持两种形态：
  - `multipart/form-data`（官方 curl/Python/JS 示例都用它）；
  - `application/json`（2026 规范新增，`images: [{image_url|file_id}]`，`mask`/`markup` 用 `ImageRefParam`）。**兼容网关不一定实现 JSON 变体**，客户端最小公共面应只依赖 multipart。[OPENAI-SPEC]

### 2.2 multipart 请求字段（`CreateImageEditRequest`）

| 字段 | 必填 | 约束 | 说明 |
|---|---|---|---|
| `model` | 是 | 枚举/字符串 | GPT Image 系列；`gpt-image-1` 已关停，`dall-e-2` 已退役 |
| `prompt` | 是 | GPT Image 最长 32,000 字符 | 编辑指令 |
| `image` | 是 | 单文件或数组，最多 16 张；PNG/WebP/JPG，每张 `<50 MB` | 多图时用官方示例的 `image[]` 字段 |
| `mask` | 否 | PNG，`<4 MB`，与 `image` 同尺寸；**全透明像素 = 可编辑区域** | 多图时只作用于第一张 |
| `markup` | 否 | 透明 PNG，与首图同尺寸，支持 GPT Image 2.5 | 与 `mask` 互斥 |
| `n` | 否 | 1–10，默认 1 | 返回张数 |
| `size` | 否 | `auto` 或 `WIDTHxHEIGHT`；GPT Image 2+ 要求两边都是 16 的倍数、宽高比 1:3–3:1、最大 `3840x2160` | 高于 `2560x1440` 属实验档 |
| `quality` | 否 | `low` / `medium` / `high`（2.5 起另有 `xhigh` / `max` / `auto`） |  |
| `output_format` | 否 | `png` / `webp` / `jpeg`（规范里还有 `gif` / `svg`） | 默认 `png` |
| `output_compression` | 否 | 0–100，默认 100 | 仅 webp/jpeg 有意义 |
| `background` | 否 | `transparent` / `opaque` / `auto` | 透明背景需 png/webp |
| `input_fidelity` | 否 | `high` / `low` | 1.5 支持，2 系列要求省略 |
| `stream` / `partial_images` | 否 | 布尔 / 1–3 | SSE 增量预览，不是轮询 |
| `safety_identifier` | 否 | ≤128 字符 | 建议**不发送**，避免把可关联标识带给第三方 |
| `response_format` | — | 已 `deprecated` | GPT Image 模型不支持，始终返回 base64 |

> `image` 的数组形态在 OpenAPI 里是 `anyOf: string | array<string>`；官方 curl 示例用 `-F "image[]=@..."` 传多图。单图用 `image`，多图用 `image[]` 是当前最兼容的写法；客户端应对 400 做好「字段名不被网关接受」的降级提示。

### 2.3 响应与错误

成功响应（`ImagesResponse`）：

```json
{
  "created": 1713833628,
  "data": [{ "b64_json": "iVBORw0KGgo..." }],
  "background": "opaque",
  "output_format": "png",
  "size": "1024x1024",
  "quality": "high",
  "usage": {
    "total_tokens": 100,
    "input_tokens": 50,
    "output_tokens": 50,
    "input_tokens_details": { "text_tokens": 10, "image_tokens": 40 }
  }
}
```

- GPT Image 模型**只返回 `b64_json`**；`url` / `revised_prompt` 是给已退役模型或旧参数用的。[OPENAI-SPEC]
- 错误体是 `{"error": {"message", "type", "param", "code"}}`；规范列出 400（请求/图片非法）、403（项目无权访问模型）、410（DALL·E 端点已退役）、429（限流）、500、503。[OPENAI-SPEC]

### 2.4 模型生命周期与兼容网关

- 规范内嵌的退役信息：`gpt-image-1` 于 **2026-10-23** 关停；`dall-e-2` 于 **2026-05-12** 退役。[OPENAI-SPEC]
- Azure OpenAI / Microsoft Foundry 是典型的「OpenAI-compatible」面：
  - 传统路径：`POST https://{resource}.openai.azure.com/openai/deployments/{deployment}/images/edits?api-version=2025-04-01-preview`，认证头是 `api-key`，body 仍是 multipart；[AZURE-IMG]
  - Foundry v1 路径：`POST /openai/v1/images/edits`（Microsoft REST 参考）；[AZURE-REST]
  - 同一套 `model`/`prompt`/`image`/`mask` 语义，但 `response_format`、`output_format`、模型枚举的可用性随部署而变。
- **结论**：客户端不要把 `api.openai.com`、`/v1`、模型名、`response_format` 写死；应暴露「预设 OpenAI / 自定义兼容网关」两种配置，并把 `model` 作为用户可编辑字段。

### 2.5 对移动客户端的直接影响

| 影响 | 处理 |
|---|---|
| 响应是 base64 JSON | 读取时设上限；2K 输出通常 <10 MB，4K 可能 20 MB+；峰值内存见 §7.5 |
| 没有 idempotency key | POST 超时后**不要盲目重试**，可能重复计费；把「未知」作为独立状态交给用户 |
| 429/503 | 读 `Retry-After`（若有），指数退避 + 上限 3 次；401/403 不重试 |
| 请求可能持续 10–60 s | `readTimeout` 至少 120 s；离开页面即取消会浪费已发生的计费，需产品确认 |
| `mask` 与首图必须同尺寸 | mask 必须在**清洗后的同一分辨率**上生成，不能拿原图 mask 直接配缩略图 |

---

## 3. Gemini 原生图像编辑：Interactions API、语义遮罩与异步

### 3.1 端点、认证与模型

2026-11 的官方图像生成/编辑文档统一走 Interactions API（新接入应以该文档为准，不要照抄旧版 `models/{model}:generateContent` 示例）；图像编辑 REST 示例带 `Api-Revision: 2026-05-20`，客户端应固定该值并随文档升级复核：

```
POST https://generativelanguage.googleapis.com/v1beta/interactions
x-goog-api-key: $GEMINI_API_KEY
Content-Type: application/json
Api-Revision: 2026-05-20
```

模型（`model` 字段）：

| 模型 ID | 定位 |
|---|---|
| `gemini-nano-banana-2.1` | 当前推荐的图像生成/编辑主力（Nano Banana 2.1） |
| `gemini-3.1-flash-image` | 上一代高性价比（Nano Banana 2） |
| `gemini-3-pro-image` | 高质量/复杂指令（Nano Banana Pro） |
| `gemini-3.1-flash-lite-image` | 最快最便宜，不适合多参考图/多轮 |
| `gemini-2.5-flash-image` | legacy，官方建议迁移 |

> **Imagen 已关停**：官方文档明确写「Imagen models are shut down. Use Nano Banana models for all image generation tasks.」不要再把 Imagen 的 `:predict` / `:editImage` 作为备选路径。[GEMINI-IMG]

### 3.2 请求体

```json
{
  "model": "gemini-nano-banana-2.1",
  "input": [
    { "type": "text", "text": "Change only the blue sofa to a vintage brown leather chesterfield. Keep everything else unchanged." },
    { "type": "image", "mime_type": "image/jpeg", "data": "<BASE64>" }
  ],
  "response_format": {
    "type": "image",
    "mime_type": "image/jpeg",
    "aspect_ratio": "3:2",
    "image_size": "2K"
  },
  "store": false
}
```

- `input` 可以是字符串，也可以是 `Content` 数组；图片既可用 `data`（base64）也可用 `uri`（Files API）。[GEMINI-INTERACTIONS]
- 支持的图片 MIME：`image/png`、`image/jpeg`、`image/webp`、`image/heic`、`image/heif`（Interactions 参考还列出 gif/bmp/tiff）。[GEMINI-IMAGE-UNDERSTANDING][GEMINI-INTERACTIONS]
- `response_format` 可选；默认「匹配输入图尺寸，否则生成 1:1 方图」。`image_size` 支持 1K/2K/4K（0.5K 仅 3.1 Flash）。[GEMINI-IMG]
- 最多 14 张参考图；具体高保真张数按模型不同（Nano Banana 2.1：10 物体 + 4 角色；3 Pro：6 物体 + 5 角色 + 3 风格）。[GEMINI-IMG]

### 3.3 响应

成功响应是一个 `Interaction` 资源：

```json
{
  "id": "v1_...",
  "status": "completed",
  "object": "interaction",
  "model": "gemini-nano-banana-2.1",
  "steps": [
    {
      "type": "model_output",
      "content": [
        { "type": "image", "data": "<BASE64>", "mime_type": "image/png" }
      ]
    }
  ],
  "usage": { "total_tokens": 1234 }
}
```

注意 REST 与 SDK 的差异：原始 REST 响应**没有** `output_image` 顶层字段，必须遍历 `steps[].content[]` 找 `type == "image"` 的块；`interaction.output_image.data` 是官方 SDK 的便捷属性（取最后一张图）。Android 直接走 REST，应按前者解析。[GEMINI-IMG][GEMINI-INTERACTIONS]

### 3.4 关键限制：没有二值 mask

Interactions 的 `ImageContent` 只有 `type` / `data` / `uri` / `mime_type` / `resolution`，没有 mask 字段。官方把局部编辑定义为「semantic masking」：

> "Conversationally define a 'mask' to edit a specific part of an image while leaving the rest untouched." 模板：`Using the provided image, change only the [specific element] to [new element/description]. Keep everything else in the image exactly the same...`[GEMINI-IMG]

因此：

- 画笔 mask 不能作为硬约束传给 Gemini；只能转成提示词约束，或把 mask 作为第二张参考图并额外说明（仍然是概率性的）。
- 对产品而言，`MaskSupport` 必须是 `SOFT`：UI 要明确「Gemini 不保证只改选区」，并允许用户退回「全图指令编辑」。
- 反过来，OpenAI 和 ComfyUI 是 `HARD`：前者用 alpha 通道，后者用 alpha→mask 节点。三者不能共用同一套「mask 一定生效」的承诺。

### 3.5 异步、轮询与存储

- `background: true`：创建请求立即返回 `status: in_progress` 的 interaction；随后 `GET /v1beta/interactions/{id}` 轮询，直到 `completed` / `failed` / `cancelled` / `incomplete`。[GEMINI-GETSTARTED][GEMINI-INTERACTIONS]
- 状态枚举：`in_progress`、`requires_action`、`completed`、`failed`、`cancelled`、`incomplete`、`requires_continuation`。[GEMINI-INTERACTIONS]
- 取消：`POST /v1beta/interactions/{id}/cancel`；删除：`DELETE /v1beta/interactions/{id}`。[GEMINI-INTERACTIONS]
- `store: false`：官方明确写「Set `store=false` in your request to opt out of server-side storage.」；若用 `previous_interaction_id` 做多轮，则必须让服务端保留状态（stateful），这是隐私与连续性的取舍。[GEMINI-GETSTARTED]
- 对单次「上传一张图 + 改一处」的 BYOK 场景，建议 `store: false` + 同步请求；只有长任务才用 `background: true` + 轮询。

### 3.6 限额与隐私

| 项 | 值 | 来源 |
|---|---|---|
| 单次 inline 请求总大小 | 20 MB（文本 + 系统指令 + 内联字节） | [GEMINI-IMAGE-UNDERSTANDING] |
| 参考图数量 | 最多 14 张 | [GEMINI-IMG] |
| 输出尺寸 | 1K / 2K / 4K（0.5K 仅 3.1 Flash） | [GEMINI-IMG] |
| 输出水印 | 所有生成图包含 SynthID | [GEMINI-IMG] |
| 服务端存储 | `store: false` 可退出 | [GEMINI-GETSTARTED] |

> 20 MB 是**整个请求**的上限，不只是图片。2048 长边、q90 的 JPEG 通常 0.5–1.5 MB，留有充足余量；4K 原图 + 长提示词则可能逼近上限。

### 3.7 错误与限流

Gemini 的 REST 错误遵循 Google API 的 `{"error": {"code", "message", "status"}}` 形状（Interactions 资源另有 `errors` 数组用于任务内部错误）。[GEMINI-INTERACTIONS] 客户端按 §5.4 的统一分类处理即可；`429` 读 `Retry-After`，`400` 不重试，`401/403` 提示重新输入 Key。

---

## 4. ComfyUI 自建服务：路由、上传语义与鉴权现实

### 4.1 鉴权与部署现实

官方自托管文档的警告是结论性的：

> "The ComfyUI server has no authentication by default. Only bind to a non-loopback address on a network you trust, or put a reverse proxy with authentication in front of it."[COMFY-SELFHOST]

补充事实：

- `comfy/cli_args.py` 没有任何 `--auth` / `--password` / `--api-key` 参数；只有 `--listen`、`--port`、`--tls-keyfile`、`--tls-certfile`、`--enable-cors-header`、`--max-upload-size`（默认 100 MB）等。[COMFY-FLAGS]
- `--multi-user` 只启用「per-user storage」；`app/user_manager.py` 从 `comfy-user` header 取用户 id，**不校验密码/令牌**，且未知用户直接 `KeyError`。这不是认证。[COMFY-SRC-USER]
- `comfy-api-proxy`（Comfy API v2）可以配置可选静态 Bearer token；但原始 Server API（也就是本文要接的 `/prompt`、`/history`）没有这层。[COMFY-RUN]
- 自建 HTTPS 可用 `python main.py --tls-keyfile key.pem --tls-certfile cert.pem`；反向代理必须允许 WebSocket upgrade（若用 `/ws`）并放大 body/timeout。[COMFY-FLAGS]

**自建可用的鉴权模式**（按推荐度）：

| 模式 | 客户端发送 | 服务端 | 备注 |
|---|---|---|---|
| 反向代理 Basic | `Authorization: Basic base64(user:pass)` | nginx `auth_basic` | 最简单；必须 HTTPS |
| 反向代理 Bearer | `Authorization: Bearer <token>` | nginx `map`/`if` 或 API gateway | 适合多用户/可轮换 |
| 自定义 Header | `X-API-Key: ...` | nginx / Cloudflare Access | 避免与 `Authorization` 冲突 |
| 隧道身份 | `CF-Access-Client-Id` + `CF-Access-Client-Secret` | Cloudflare Tunnel + Access | 不暴露公网端口，推荐 |
| 私有网络 | 无额外头 | Tailscale/WireGuard | 依赖网络层，不是应用层鉴权 |
| `--multi-user` | `comfy-user: alice` | 仅存储分区 | **不是鉴权，不要用** |

### 4.2 路由总表（原始 Server API）

| 路径 | 方法 | 作用 | 源码 |
|---|---|---|---|
| `/upload/image` | POST | 上传输入图 | `server.py:536` |
| `/upload/mask` | POST | 上传 mask 并合成到原图 alpha | `server.py:553` |
| `/prompt` | POST | 提交 API 格式工作流 | `server.py:1164` |
| `/prompt` | GET | 当前队列/执行信息 | `server.py:839` |
| `/history` | GET | 历史（可分页） | `server.py:1137` |
| `/history/{prompt_id}` | GET | 单个任务历史 | `server.py:1151` |
| `/view` | GET | 取输出/输入图片字节 | `server.py:599` |
| `/ws` | WebSocket | 执行事件与预览帧 | `server.py:336` |
| `/object_info` | GET | 节点 schema（校验输入） | `server.py:892` |
| `/queue` | GET/POST | 队列状态/清空 | `server.py:1156/1248` |
| `/interrupt` | POST | 中断当前执行 | `server.py:1262` |
| `/system_stats` | GET | 设备/VRAM 信息 | `server.py:774` |

### 4.3 `/upload/image` 与 `/upload/mask`

`/upload/image` 的 multipart 字段：

| 字段 | 必填 | 值 | 行为 |
|---|---|---|---|
| `image` | 是 | 文件 | 原始文件名参与去重 |
| `type` | 否 | `input`（默认）/ `temp` / `output` | 决定落盘目录 |
| `subfolder` | 否 | 字符串 | 相对子目录；路径逃逸会被 400 |
| `overwrite` | 否 | `true` / `1` | 否则同名不同哈希会改名 `name (1).ext` |

响应：

```json
{ "name": "upload (1).jpg", "subfolder": "", "type": "input" }
```

- **必须使用响应里的 `name`**，不要假设上传文件名不变；同名冲突时服务端会追加 ` (n)`。[COMFY-SRC-SERVER]
- 新版还会返回可选 `asset` 字段；客户端应忽略未知字段。[COMFY-SRC-SERVER]

`/upload/mask` 的字段：

| 字段 | 必填 | 值 |
|---|---|---|
| `image` | 是 | mask PNG（alpha 承载选区） |
| `original_ref` | 是 | JSON 字符串：`{"filename":"upload.jpg","subfolder":"","type":"input"}` |
| `type` | 否 | 通常 `input`（mask 的落盘目录） |
| `subfolder` | 否 | 子目录 |

行为（源码）：

1. 解析 `original_ref`，在 `type` 对应目录找到原图；
2. 把原图 `convert('RGBA')`，把上传 mask 的 alpha 通道 `putalpha` 到原图上；
3. 以 PNG 保存到 `type/subfolder/<mask 文件名>`；
4. 返回 `{name, subfolder, type}`。

因此 `/upload/mask` 返回的不是「纯 mask 文件」，而是**原图 RGB + 新 alpha 的合成 PNG**。工作流里用 `LoadImage` 加载它，`LoadImage` 返回的 mask 是 `1.0 - alpha`（alpha 0 = mask 1 = 编辑区），正好与 OpenAI 的「透明 = 可编辑」约定一致。[COMFY-SRC-NODES]

> 失败模式（源码级）：如果 `original_ref` 指向不存在的文件，`image_save_function` 内部 `os.path.isfile(file)` 为假，**不会写任何文件**，但 `store_image_upload` 仍会返回 `{name, subfolder, type}` 的 200 响应。随后工作流的 `LoadImage` 会报 `Invalid image file`。客户端必须在调用前确认 `/upload/image` 已成功，并在 `/upload/mask` 后用 `GET /view?filename=<name>&type=input` 验证文件真的存在。

### 4.4 `/prompt`：请求、响应与 node_errors

请求（**形状示意**；实际工作流必须包含完整的 checkpoint/VAE 加载、采样器与保存节点，下面的 4 个节点只是为了展示 id→inputs 的改法；`["8", 0]` 里的 8 是省略的 `VAEDecode`）：

```json
{
  "prompt": {
    "6": { "class_type": "CLIPTextEncode", "inputs": { "text": "a vintage brown leather sofa", "clip": ["4", 1] } },
    "3": { "class_type": "KSampler", "inputs": { "seed": 12345, "steps": 20, "cfg": 7, "denoise": 1, "sampler_name": "euler", "scheduler": "normal", "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0] } },
    "10": { "class_type": "LoadImage", "inputs": { "image": "upload.jpg" } },
    "9": { "class_type": "SaveImage", "inputs": { "filename_prefix": "photosync", "images": ["8", 0] } }
  },
  "client_id": "3f0c...",
  "prompt_id": "0b0e8f2a-4b1c-4f9e-9d7a-2f6c1e8a9b3d"
}
```

- `prompt` 必填；服务端按 `class_type` 找节点类，按 `/object_info` 的 `INPUT_TYPES` 校验 `inputs`。[COMFY-SRC-SERVER]
- `client_id` 用于把 WebSocket 事件路由到对应连接；不传也能跑，但 `/ws` 收不到定向事件。[COMFY-SRC-SERVER]
- `prompt_id` 可选，客户端可自带 UUID；服务端只接受「canonical lowercase hyphenated」形式，否则 400 `invalid_prompt_id`。老版本可能忽略它并自行生成，**以响应为准**。[COMFY-SRC-SERVER]
- `number` / `front` 控制队列优先级；`extra_data` 可带 Partner Nodes 的 `api_key_comfy_org`（这是第二套 Key，和端点鉴权无关）。[COMFY-SRC-SERVER][COMFY-EXAMPLES]

成功响应：

```json
{ "prompt_id": "0b0e8f2a-...", "number": 7, "node_errors": {} }
```

校验失败（400）：

```json
{
  "error": {
    "type": "prompt_outputs_failed_validation",
    "message": "Prompt outputs failed validation",
    "details": "",
    "extra_info": {}
  },
  "node_errors": {
    "10": {
      "errors": [{ "type": "value_not_in_list", "message": "Value not in list", "details": "image: 'upload.jpg' not in []", "extra_info": {} }],
      "class_type": "LoadImage",
      "dependent_outputs": ["9"]
    }
  }
}
```

### 4.5 `/history/{prompt_id}` 与 `/view`

任务完成后：

```json
{
  "0b0e8f2a-...": {
    "prompt": [7, "0b0e8f2a-...", { "...": "..." }, {}, ["9"]],
    "outputs": {
      "9": {
        "images": [
          { "filename": "photosync_00001_.png", "subfolder": "", "type": "output" }
        ]
      }
    },
    "status": { "status_str": "success", "completed": true, "messages": [] }
  }
}
```

- 任务未完成或不存在时返回 `{}`；客户端应把「空对象」当作「继续轮询」，不要当失败。[COMFY-SRC-EXEC]
- `outputs` 的键是输出节点 id；每个节点可能返回多张图。`SaveImage` 的 `type` 是 `output`，`PreviewImage` 是 `temp`。[COMFY-SRC-NODES]
- 下载：

```
GET /view?filename=photosync_00001_.png&subfolder=&type=output
```

`/view` 还支持 `channel=rgb|a`、`preview=webp;90`；默认 `type=output`。文件名含 `/` 开头或 `..` 会被 400，`subfolder` 逃逸会被 403——客户端要 URL-encode。[COMFY-SRC-SERVER]

### 4.6 `/ws`：可选，不是 v1 必需

`/ws?clientId=...` 首帧发 `status`，随后按节点发 `execution_start`、`execution_cached`、`executing`（`node: null` 表示全部完成）、`progress`、`executed`、`execution_error`、`execution_success`、`execution_interrupted`；二进制帧是预览图。[COMFY-MESSAGES][COMFY-SRC-EXEC]

对 Android 客户端的含义：

- 仓库没有 WebSocket 客户端；`HttpURLConnection` 不支持 WS。v1 用 **1–2 s 轮询 `/history/{id}`** 足够，且跨版本更稳。
- WS 的价值是实时进度/预览；若后续要加，需引入 OkHttp（或手写 WS，不推荐），并处理重连、`clientId` 复用、二进制帧解析。
- `execution_error` 的 `data` 含 `prompt_id`、`node_id`、`node_type`、`exception_message` 等；轮询 `/history` 的 `status.status_str == "error"` 也能拿到失败，但细节较少。[COMFY-MESSAGES]

### 4.7 工作流 JSON 与「节点占位符」惯例

官方约定（`basic_api_example.py` / `websockets_api_example.py`）：

1. 在 ComfyUI 界面 `File → Export (API)` 导出 API 格式；
2. 载入 JSON，得到 `{"<node_id>": {"class_type": ..., "inputs": {...}}}`；
3. 按 node id 直接改 `inputs`：

```python
prompt["6"]["inputs"]["text"] = "masterpiece best quality man"
prompt["3"]["inputs"]["seed"] = 5
```

[COMFY-EXAMPLES]

关键事实：

- **服务端没有模板引擎**；`{{prompt}}`、`__PROMPT__` 等占位符只是客户端自己的约定，必须在 POST 前替换。
- 节点 id 是工作流作者定义的字符串；不要假设 `"6"` 永远是正向提示词。稳妥做法是随 APK 固定一份工作流 JSON，并在代码里用常量映射 node id；升级工作流时同步改常量。
- 可以在启动时 `GET /object_info` 校验节点是否存在、`inputs` 的必填项与类型；这比硬编码更稳，但增加一次网络请求。[COMFY-SRC-SERVER]
- `LoadImage` 的 `image` 值应是 `/upload/image` 返回的 `name`；若 `subfolder` 非空，按 ComfyUI 的注解路径约定拼成 `subfolder/name`（或使用 `[input]` 后缀形式）。[COMFY-SRC-NODES]
- 工作流必须以 `SaveImage`（或 `PreviewImage` / `SaveImageWebsocket`）结尾，否则 `/history` 的 `outputs` 为空。[COMFY-SRC-NODES]

### 4.8 稳定性、超时与代理

- 官方明确：raw Server API「no compatibility guarantee across releases」；生产接入应固定 ComfyUI 版本，或只使用稳定子集（本文列出的 6 个路由）。[COMFY-RUN]
- 反代必须放大 `client_max_body_size`（默认 1 MB 会直接挡掉照片）与 `proxy_read_timeout`（默认 60 s 对生成任务偏短）；若用 `/ws`，还要 `Upgrade`/`Connection` 头。
- `/prompt` 的 `prompt_id` 只是任务标识，不是幂等键；重复提交会重复排队。客户端应在重试前先查 `/history/{id}`。

---

## 5. 最小公共协议抽象

### 5.1 能力矩阵

| 能力 | OpenAI `/v1/images/edits` | Gemini Interactions | ComfyUI 原始 API |
|---|---|---|---|
| 传输 | multipart（或 JSON） | JSON + base64 | multipart + JSON |
| 认证 | `Authorization: Bearer` | `x-goog-api-key` | 无 / 反代 Basic/Bearer/自定义头 |
| mask | **HARD**（alpha，同尺寸） | **SOFT**（提示词语义遮罩） | **HARD**（工作流 alpha→mask） |
| 多图输入 | ≤16 | ≤14 | 工作流定义 |
| 同步/异步 | 同步（可选 SSE） | 同步，或 `background` + 轮询 | 队列 + 轮询/WS |
| 结果形态 | base64 JSON | base64 JSON | 二进制 `GET /view` |
| 幂等 | 无 | interaction id | 客户端 `prompt_id`（非幂等保证） |
| 上传上限 | 50 MB/图 | 20 MB/请求 | `--max-upload-size`（默认 100 MB） |
| 版本稳定性 | 商业 API，按版本演进 | v1beta，示例固定 Api-Revision | 无跨版本承诺 |

### 5.2 Kotlin 形状（不依赖新库）

```kotlin
enum class MaskSupport { HARD, SOFT, NONE }

data class ProviderCapabilities(
    val mask: MaskSupport,
    val maxImages: Int,
    val maxUploadBytes: Long,
    val supportsNegativePrompt: Boolean,
    val supportsSeed: Boolean,
    val blocking: Boolean // true = 同步返回，false = 需要 poll
)

data class SanitizedImage(
    val bytes: ByteArray,
    val mimeType: String, // image/jpeg | image/png
    val width: Int,
    val height: Int
)

data class CloudEditRequest(
    val image: SanitizedImage,
    val mask: SanitizedImage?,      // alpha=0 表示可编辑
    val prompt: String,
    val negativePrompt: String? = null,
    val model: String? = null,
    val size: String? = null,       // "1024x1024" / "3:2" 由 adapter 解释
    val seed: Long? = null
)

sealed interface CloudEditResult {
    data class Success(val image: ByteArray, val mimeType: String) : CloudEditResult
    data class Failure(val error: CloudEditError) : CloudEditResult
}

sealed class CloudEditError {
    object Auth : CloudEditError()
    data class Invalid(val message: String?) : CloudEditError()
    object TooLarge : CloudEditError()
    data class RateLimited(val retryAfterSeconds: Long?) : CloudEditError()
    data class Server(val status: Int) : CloudEditError()
    object Timeout : CloudEditError()
    object Cancelled : CloudEditError()
    data class Unknown(val cause: Throwable?) : CloudEditError()
}

interface CloudEditProvider {
    val capabilities: ProviderCapabilities
    suspend fun edit(request: CloudEditRequest): CloudEditResult
}
```

`edit()` 是唯一公开入口；需要异步的 adapter 在内部完成「提交 → 轮询 → 下载」，对 UI 暴露 `onProgress`（可选）与 `CancellationSignal`。这样 UI 不需要知道 OpenAI 是同步、Gemini 是 background、ComfyUI 是队列。

### 5.3 统一任务生命周期

```text
Idle → Preparing(清洗/缩放/mask) → Submitting → Queued/Running → Downloading → Done
                                     ↘ Failed(error) / Cancelled
```

- OpenAI：`Submitting` 覆盖整个同步请求；没有 `Queued`。
- Gemini：`Submitting` 返回 interaction id；`Queued/Running` 轮询 `GET /v1beta/interactions/{id}`。
- ComfyUI：`Submitting` 分三步（upload image → upload mask → prompt）；`Queued/Running` 轮询 `/history/{id}`；`Downloading` 是 `/view`。

### 5.4 错误分类与重试

| 分类 | HTTP/来源 | 重试 | 处理 |
|---|---|---|---|
| `Auth` | 401/403 | 否 | 提示重新输入 Key；不要清空已存 Key，除非用户确认 |
| `Invalid` | 400/422 | 否 | 展示 provider 的 `message`（做长度截断与脱敏） |
| `TooLarge` | 413 / 本地校验 | 一次 | 降一档长边/质量后重试 |
| `RateLimited` | 429 | 是 | 优先 `Retry-After`，否则指数退避；上限 3 次 |
| `Server` | 500/502/503/504 | 是 | 指数退避 + 抖动；上限 3 次 |
| `Timeout` | socket/read timeout | **谨慎** | OpenAI/Gemini 的 POST 可能已计费，默认不自动重试；ComfyUI 只重新 poll，不重新 submit |
| `Cancelled` | 用户取消 | 否 | 清理临时文件；ComfyUI 可选 `/interrupt` |
| `Unknown` | 其他 | 否 | 记录状态码与 request-id（若有），不要记录 body |

重试参数建议：初始 1 s，倍数 2，上限 30 s，抖动 ±20%；总 deadline 由 provider 配置（ComfyUI 10 min，OpenAI/Gemini 5 min）。

### 5.5 传输与安全规则

- **只允许 `https://`**；`http://` 在 release 构建直接拒绝（见 §6.7 的 Android 网络边界）。
- `HttpURLConnection`：`connectTimeout=15 s`、`readTimeout` 按 provider（OpenAI/Gemini 120 s，ComfyUI poll 30 s）；`instanceFollowRedirects=false`，3xx 当错误处理，避免把 `Authorization` 带到别的 host。
- **不要把 Key 放 query string**；OpenAI/Gemini 都支持 header。
- 不要记录 `Authorization` / `x-goog-api-key` / `comfy-user`；错误日志只留状态码、provider、request-id。
- `Accept-Encoding: identity` 或显式处理 `Content-Encoding: gzip`；`HttpURLConnection` 不会自动解压。
- multipart 用 `setFixedLengthStreamingMode(totalBytes)` 流式写，避免把整张图复制到 `ByteArrayOutputStream`。[HTTP-REF]

### 5.6 Provider 映射

| 抽象步骤 | OpenAI | Gemini | ComfyUI |
|---|---|---|---|
| 认证 | `Authorization: Bearer` | `x-goog-api-key` | 按反代配置 |
| 提交 | `POST {base}/v1/images/edits` multipart | `POST {base}/v1beta/interactions` JSON | `POST /upload/image` → `POST /upload/mask` → `POST /prompt` |
| 任务 id | 无（同步） | `interaction.id` | `prompt_id` |
| 轮询 | 不需要 | `GET /v1beta/interactions/{id}` | `GET /history/{id}` |
| 取图 | `data[0].b64_json` | REST：`steps[].content[]`（SDK：`output_image.data`） | `GET /view?filename=...` |
| 取消 | 无 | `POST /interactions/{id}/cancel` | `/interrupt`（全局）或 `/api/jobs/{id}/cancel`（新版本，未保证） |
| mask | multipart `mask` | 不支持硬 mask | `/upload/mask` + `LoadImage` alpha |

---

## 6. Android `minSdk 24` 上的零服务端 API Key 存储

### 6.1 `security-crypto` 的废弃时间线

| 日期 | 版本 | 事件 |
|---|---|---|
| 2025-04-09 | `1.1.0-alpha07` | **Deprecated all APIs** in favour of existing platform APIs and direct use of Android Keystore |
| 2025-07-02 | `1.1.0-rc01` | 进入 RC，deprecation 保持 |
| 2025-07-30 | `1.1.0` | stable 发布；参考页标注 **Deprecated in 1.1.0** |
| 2026-09-09 | Security 库文档 | 该组件的 stable 仍是 `1.1.0`，没有替代的 crypto artifact |

[SEC-RELEASE][ESP-REF]

参考页对 `EncryptedSharedPreferences` 的原文：

> **This class is deprecated.** Use `SharedPreferences` instead.
>
> **WARNING**: The preference file should not be backed up with Auto Backup. When restoring the file it is likely the key used to encrypt it will no longer be present. You should exclude all `EncryptedSharedPreference`s from backup using backup rules.[ESP-REF]

补充事实：

- `security-crypto:1.1.0` 的 AAR `minSdkVersion=21`，依赖 `tink-android:1.8.0`（2023 年的 Tink），POM 实测。[SEC-AAR]
- 也就是说：**即使只用 stable，也是在用一个已废弃的 API + 一个旧 Tink**。新代码没有理由再引入它。

### 6.2 官方替代口径

- Android 安全最佳实践页在「API key management → Strong key storage」写：

  > "For optimal key management security, use the Android Keystore, and encrypt stored keys using a robust tool such as Tink Java."[ANDROID-SECURITY]

- Keystore 页明确：key material 不进入应用进程；可以绑定 TEE/StrongBox；应用应避免在主线程做 Keystore 操作。[KEYSTORE]
- Tink Android「fully supported starting from API level 24」，且「requires no proguard configuration」；`tink-android:1.24.0` 是当前版本。[TINK-SETUP]

因此合法路径只有两条：

1. **直接 AndroidKeyStore + AES/GCM 封装**（约 100 行，零新依赖）；
2. **Tink `AndroidKeysetManager`**（成熟、可轮换，但引入 3.5 MB jar / 1,993 个 class，R8 后实际增量未测）。

对本仓库（只存 1–N 个 API Key、没有多租户/密钥轮换需求）推荐路径 1；把 Tink 作为「团队不想自己写 crypto」的备选。

**威胁模型（必须写进安全说明）**：

- 防：其他应用读取应用私有目录、Auto Backup/D2D 把密文带到新设备、日志/崩溃上报泄漏明文、普通文件系统读取。
- 不防：root、同 UID 的恶意代码/注入、屏幕录制、用户主动导出、provider 侧泄漏。Keystore 页的原文是「If the app's process is compromised, the attacker might be able to use the app's keys but can't extract their key material」——**不可提取 ≠ 不可使用**。[KEYSTORE]
- BYOK 的 Key 通常是长期有效的 provider key；泄露后只能到 provider 撤销。应用应提供「删除本地 Key」与「到 provider 撤销」的说明，而不是承诺「存进 Keystore 就绝对安全」。

### 6.3 推荐实现：AndroidKeyStore + AES-256-GCM

官方 `KeyGenParameterSpec` 的 AES/GCM 示例就是这条路径（API 23+）；`minSdk 24` 可直接使用。[KEYGEN]

```kotlin
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "photosync.byok.v1"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

private fun secretKey(): SecretKey {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (ks.containsAlias(KEY_ALIAS)) {
        val entry = ks.getEntry(KEY_ALIAS, null)
        require(entry is KeyStore.SecretKeyEntry) { "alias exists but is not an AES key" }
        return entry.secretKey
    }

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // 不设置 setUserAuthenticationRequired：后台重试/锁屏时仍可用
            .build()
    )
    return generator.generateKey()
}

fun encrypt(plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey()) // 不传 IV，让 Keystore 生成
    val iv = cipher.iv // 12 bytes
    val ciphertext = cipher.doFinal(plaintext)
    return byteArrayOf(1) + byteArrayOf(iv.size.toByte()) + iv + ciphertext
}

fun decrypt(blob: ByteArray): ByteArray {
    require(blob.size > 2 && blob[0].toInt() == 1)
    val ivLen = blob[1].toInt()
    val iv = blob.copyOfRange(2, 2 + ivLen)
    val ciphertext = blob.copyOfRange(2 + ivLen, blob.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    return cipher.doFinal(ciphertext)
}
```

存储位置：`context.noBackupFilesDir/byok/credentials.bin`，写入用 `tmp` + `rename` 原子替换；权限 `MODE_PRIVATE`。不要把密文放普通 `SharedPreferences`（会被 Auto Backup 纳入），也不要把 Key 放 `BuildConfig` / `local.properties` / 日志。

### 6.4 API 24–36 兼容性与坑点

| 坑 | 事实 | 处理 |
|---|---|---|
| **IV 由谁生成** | `setRandomizedEncryptionRequired(true)` 是默认；IND-CPA 要求下，GCM/CBC/CTR 的**调用方自带 IV 会被拒绝**；应 `init(ENCRYPT_MODE, key)` 后读 `Cipher.getIV()` | 不要 `init(ENCRYPT_MODE, key, spec)`；不要关闭 randomized 要求 |
| **锁屏/认证** | `setUserAuthenticationRequired(true)` 的 key 在锁屏凭据被移除/重置时抛 `KeyPermanentlyInvalidatedException`；未认证时抛 `UserNotAuthenticatedException`。[KPI][UNAUTH] | BYOK 默认**不要求**用户认证；若产品要求每次生成都验指纹，必须处理这两个异常并提示重新授权 |
| **锁屏后可用性** | `setUnlockedDeviceRequired(true)`（API 28+）让 key 仅在设备解锁时可用 | 若开启，后台/锁屏重试会失败；对「用户在场才生成」可用，对 WorkManager 重试不可用 |
| **KeyStore 损坏/丢失** | 官方有明确语义的异常是「永久失效」和「未认证」；其余 `KeyStoreException`/`UnrecoverableKeyException` 没有可移植恢复语义。Tink 对「master key 存在但不可用」明确抛 `KeystoreException`，并说明不能重新生成（旧密文仍在） | 捕获 `GeneralSecurityException`；删除 alias + 删除密文 + 提示重新输入 Key；绝不能让应用崩溃循环 |
| **备份/换机** | Keystore key 不随备份迁移；恢复密文到新设备会解密失败 | 密文放 `noBackupFilesDir`；`allowBackup=false` 已具备；targetSdk 31+ 再加 `dataExtractionRules` 排除 |
| **StrongBox** | API 28+；`setIsStrongBoxBacked(true)` 在不支持时抛 `StrongBoxUnavailableException` | 不要默认开；可作为「可用则用」的加分项，失败回退 TEE/软件 |
| **主线程** | Keystore 操作可能耗时；官方建议不要在主线程 | 全部放 `Dispatchers.IO` |
| **Base64** | `java.util.Base64` 在 Android 上 API 26+；`minSdk 24` 不可用 | 用 `android.util.Base64`（API 1+） |
| **多进程** | 同一 alias 在多进程并发生成可能 race | 应用单进程；如多进程，用文件锁/单独进程 |
| **Key 校验** | 保存时做一次 `GET /v1/models`（OpenAI）或 `GET /v1beta/models`（Gemini）可验证 Key 有效性，但会产生网络请求 | 可选；不要把校验结果当「Key 永久有效」 |

### 6.5 Tink 备选：能力与代价

```kotlin
val keysetHandle = AndroidKeysetManager.Builder()
    .withSharedPref(context, "byok_keyset", "byok_prefs")
    .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
    .withMasterKeyUri("android-keystore://photosync.byok.master")
    .build()
    .keysetHandle
val aead = keysetHandle.getPrimitive(Aead::class.java)
val ciphertext = aead.encrypt(plaintext, aad)
```

事实与代价：

- `tink-android:1.24.0` 是**普通 jar**（不是 AAR），Maven Central 上 3,508,938 B / 1,993 个 `.class`；R8 后实际 APK 增量未测（投影：数百 KB 量级，取决于保留的 primitive）。[TINK-AAR]
- `AndroidKeysetManager` 默认把**加密后的 keyset 写入 SharedPreferences**（`withSharedPref`），所以同样要排除备份。[TINK-KEYSET]
- 当 master key 存在但不可用时，Tink 抛 `KeystoreException("the master key ... exists but is unusable")`；源码注释明确说不能靠重新生成恢复，因为旧密文可能还在。应用层仍然要做「删 keyset + 重新输入」的破坏性恢复。[TINK-KEYSET]
- 优点：AEAD 原语、密钥轮换、格式演进由 Tink 管理；不需要自己写 GCM 封装。
- 代码里的 `AeadKeyTemplates` / `withKeyTemplate` 以 1.24.0 的签名为准；Tink 在 KeyTemplate/Parameters 之间有过迁移，升级大版本时要重新对照 release notes。

**建议**：v1 用 §6.3 的直接封装；如果未来要做多 Key、Key 轮换、或多 provider 凭证库，再评估 Tink。

### 6.6 备份与数据提取规则

仓库现状：`android:allowBackup="false"`（`AndroidManifest.xml:11`），所以 Google Drive 云备份默认关闭。但官方文档提醒：

> "For apps targeting Android 12 (API level 31) or higher, this behavior varies. On devices from some device manufacturers, specifying `android:allowBackup="false"` disables cloud-based backup and restore but doesn't disable device-to-device transfers for the app."[BACKUP]

因此：

- **首选**：密文放 `getNoBackupFilesDir()`；该目录「always excluded even if you try to include them」，与 OEM 行为无关。[BACKUP]
- **加固**：如果未来把密文放进普通 `filesDir`/`sharedpref`，在 `res/xml/data_extraction_rules.xml` 里按域排除，并在 manifest 声明 `android:dataExtractionRules`；API 30 及以下仍需 `android:fullBackupContent`。

```xml
<!-- res/xml/data_extraction_rules.xml（API 31+，仅示例；noBackupFilesDir 不需要） -->
<data-extraction-rules>
  <cloud-backup>
    <exclude domain="file" path="byok"/>
    <exclude domain="sharedpref" path="byok_prefs.xml"/>
  </cloud-backup>
  <device-transfer>
    <exclude domain="file" path="byok"/>
    <exclude domain="sharedpref" path="byok_prefs.xml"/>
  </device-transfer>
</data-extraction-rules>
```

- 普通偏好（provider、base URL、模型名）可以备份；只有 secret 必须排除。

### 6.7 网络边界：HTTPS、用户 CA 与 Android 17 局域网权限

| 事实 | 影响 |
|---|---|
| `targetSdk 28+` 默认 `cleartextTrafficPermitted=false` | `http://192.168.x.x:8188` 在 release 直接失败；不要为了自建 ComfyUI 全局放开 cleartext |
| `targetSdk 24+` 默认只信任 system CA，不信任 user CA | 自签名 ComfyUI 证书会握手失败；`network_security_config` 的 `src="user"` 只适合「用户自己装 CA」的场景，且会扩大信任面 |
| Android 17（API 37）起，`targetSdk >= 37` 访问局域网需要 `ACCESS_LOCAL_NETWORK` 运行时权限；未授权时 TCP 表现为 timeout | 仓库现在 `targetSdk 36` 不受影响；一旦升到 37，自建 ComfyUI 的 LAN 连接会断，必须声明并请求该权限（属于 `NEARBY_DEVICES` 组） |

[NSC][LNP]

**结论**：自建 ComfyUI 的推荐部署是「公网/隧道 HTTPS + 反向代理鉴权」或「私有网络 + 有效证书」；应用侧只接受 `https://`，不提供「忽略证书错误」开关。若必须支持自签名，应走「用户安装 CA + 仅对该域名 trust user」的显式配置，并在 UI 上说清风险——不要用 `TrustManager` 全放行。

### 6.8 测试矩阵

| 场景 | API | 预期 |
|---|---|---|
| 首次保存 Key | 24 | 生成 AES-256 key；`Cipher.getIV()` 12 B；重启后可解密 |
| 锁屏后读取 | 24/28/31/36 | 默认 key 可解密；若开 `setUnlockedDeviceRequired`，锁屏时失败并可恢复 |
| 移除锁屏密码 | 任何 | 默认 key 不受影响；auth-bound key 抛 `KeyPermanentlyInvalidatedException`，UI 要求重新输入 |
| 清除应用数据 | 任何 | alias 与密文都没了；重新输入即可 |
| 恢复备份到新机 | 31+ | `noBackupFilesDir` 不迁移；不应出现解密崩溃 |
| 删除 alias 后读取 | 任何 | 捕获 `KeyStoreException`/`UnrecoverableKeyException`，重建 key，提示重新输入 |
| 并发保存/读取 | 任何 | 串行化；无 `AEADBadTagException` |
| OEM D2D 传输 | 31+ | 密文不在传输集内；或传输后解密失败被优雅处理 |

---

## 7. 外发图像隐私清洗：EXIF、缩略图与重编码

### 7.1 会随照片外发的内容

一张相机/手机 JPEG 可能包含：

| 类别 | 典型 tag | 风险 |
|---|---|---|
| 位置 | `GPSLatitude`、`GPSLongitude`、`GPSAltitude`、`GPSTimeStamp` | 家庭/工作地址 |
| 设备 | `Make`、`Model`、`BodySerialNumber`、`LensSerialNumber`、`LensModel` | 设备指纹、保修/所有权关联 |
| 时间 | `DateTimeOriginal`、`DateTimeDigitized`、`OffsetTime*` | 作息/行踪 |
| 文本 | `ImageDescription`、`Artist`、`UserComment` | 姓名、备注 |
| 厂商私有 | `MakerNote` | 序列号、镜头信息、未知二进制 |
| 缩略图 | IFD1 JPEG thumbnail | 额外一张小图；可能含独立 metadata |
| 其他 | `Software`、`Orientation`、XMP/IPTC | 软件指纹、编辑历史 |

`androidx.exifinterface` 的 tag 字典包含上述全部（`TAG_GPS_LATITUDE`、`TAG_BODY_SERIAL_NUMBER`、`TAG_LENS_SERIAL_NUMBER`、`TAG_DATETIME_ORIGINAL`、`TAG_MAKER_NOTE` 等）。[EXIF-SRC]

### 7.2 为什么 `ExifInterface` 不能承诺「彻底剥离」

源码级事实（`androidx.exifinterface:1.4.2`，公开 API 见参考页 [EXIF-REF]）：

1. **没有 `clearAttributes()`**。公开 API 只有 `setAttribute` / `getAttribute` / `saveAttributes` 等；要删只能逐个 tag 调 `setAttribute(tag, null)`，而 `setAttribute` 的 null 分支最终是 `mAttributes[i].remove(tag)`。[EXIF-SRC]
2. **不知道全部 tag 就删不干净**。`mAttributes` 按 IFD 分组（PRIMARY / EXIF / GPS / INTEROPERABILITY / THUMBNAIL / ORF 等）；公开 API 不暴露「枚举所有 tag」的能力，厂商私有 IFD 更不可枚举。
3. **`saveAttributes()` 会保留缩略图**。它在重写前执行 `mThumbnailBytes = getThumbnail()`，`writeExifSegment()` 在 `mHasThumbnail` 为真时重新写入 `JPEGInterchangeFormat` / `JPEGInterchangeFormatLength` 并追加缩略图字节。**没有公开的 `setThumbnail(null)`**。[EXIF-SRC]
4. **`saveAttributes()` 是整文件重写**：注释原文「This is expensive because it involves copying all the data from one file to another and deleting the old file and renaming the other.」；实现是 `File.createTempFile("temp","tmp")` + 全量 copy。它适合「在导出图上补写 EXIF」，不适合「上传前清洗」。[EXIF-SRC]
5. `ExifInterface` 只处理 EXIF（以及部分 XMP/PNG/WebP 容器），不理解 JPEG 里的任意 APPn 段；「删了 EXIF 就没隐私」是错误假设。

**结论**：`ExifInterface` 是「元数据编辑器」，不是「隐私擦除器」。对本工单的「彻底剥离」要求，它不满足。

### 7.3 重编码为什么是闭世界

AOSP 的调用链：

```text
Bitmap.compress(JPEG, quality, out)
  → Bitmap_compress (JNI)
    → android::Bitmap::compress(JavaCompressFormat::Jpeg, quality, stream)
      → SkJpegEncoder::Options options;
        options.fQuality = quality;
        SkJpegEncoder::Encode(stream, bitmap.pixmap(), options);
```

[AOSP-BITMAP-JNI][AOSP-BITMAP-HWUI]

`SkJpegEncoder::Options` 的字段只有：`fQuality`、`fDownsample`、`fAlphaOption`、`xmpMetadata`、`fHdrMetadata`、`fOrigin`。没有「传入任意 EXIF」的入口；Android 的 `Bitmap::compress` 也没有设置 `xmpMetadata` 或 `fOrigin`。[SKIA-JPEG][AOSP-BITMAP-HWUI]

因此：

- 输出 JPEG 的像素来自 `Bitmap`，**不可能携带原图的 GPS/序列号/拍摄时间/缩略图**。
- Skia 可能写入 ICC profile（色彩空间）和 JFIF APP0；这些不是个人元数据。
- 如果 Bitmap 带 HDR gainmap，Android 会走 `SkJpegGainmapEncoder`；gainmap 是另一张亮度图，不含 EXIF。若合规要求「单张 SDR」，需在解码时转 SDR（本项目当前不涉及 HDR 导出，可忽略）。
- 重编码同时完成「缩放」：`Bitmap.createScaledBitmap(..., filter=true)` 使用双线性过滤，质量优于 `inSampleSize` 的整数降采样；两者组合是官方推荐的「大图加载」路径。[AOSP-BITMAP][LOAD-BITMAP]

### 7.4 推荐的上传清洗管线

```text
1. 读 bounds: BitmapFactory.Options(inJustDecodeBounds = true)
2. 读 orientation: ExifInterface(path).getAttributeInt(TAG_ORIENTATION)
3. 计算 inSampleSize: 使解码结果 >= 目标长边（2 的幂）
4. 解码: BitmapFactory.decodeFile(path, opts)  // ARGB_8888
5. 按 orientation 旋转/镜像: Matrix + Bitmap.createBitmap
6. 精确缩放: Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
7. 编码: Bitmap.compress(JPEG, 90, FileOutputStream(tmp))
8. 校验: 重新打开 tmp，确认无 GPS/MAKE/MODEL/DATETIME/缩略图，尺寸正确
9. 原子 rename 到 cacheDir/upload/<uuid>.jpg；删除中间 Bitmap/临时文件
```

关键点：

- **先读 orientation 再解码**；`BitmapFactory` 本身不应用 EXIF orientation（源码/文档没有该行为），仓库的 `BakeSourceProbe.isQuarterTurned()` 已经在做同样的事，可复用/抽公共。[AOSP-BITMAPFACTORY][REPO-PROBE]
- **`inSampleSize` 只保证「不小于目标」**，最后必须 `createScaledBitmap` 精确到目标长边；`inSampleSize` 会把非 2 的幂向下取整。[AOSP-BITMAPFACTORY][LOAD-BITMAP]
- **JPEG 质量 90** 是工程取值：对编辑输入足够，文件通常 0.5–1.5 MB（2048 长边）；没有官方标准，属于经验参数。
- **不要复用 `ExifCopier` / `BakeExifWriter`**：它们的设计目标是把拍摄 EXIF（含 GPS、机身/镜头序列号）写回导出图，和上传清洗的方向相反。[REPO-EXIFCOPIER]
- **输出文件名用随机 UUID**，不要用原文件名（可能含日期、相机编号、地点）。上传 multipart 的 `filename` 同样用 `upload.jpg`，不要带原路径。

### 7.5 内存测算（投影）

以 ARGB_8888（4 B/px）计：

| 源 | 全尺寸解码 | `inSampleSize` | 采样后 | 目标长边 2048 | 峰值（采样 + 目标 + 编码缓冲） |
|---|---:|---:|---:|---:|---:|
| 24 MP（6000×4000） | 91.6 MiB | 2 | 3000×2000 = 22.9 MiB | 2048×1365 = 10.7 MiB | ≈ 36 MiB |
| 50 MP（8192×6144） | 192.0 MiB | 4 | 2048×1536 = 12.0 MiB | 2048×1536 = 12.0 MiB | ≈ 26 MiB |
| 100 MP（11648×8736） | 388.2 MiB | 4 | 2912×2184 = 24.3 MiB | 2048×1536 = 12.0 MiB | ≈ 39 MiB |

- 全尺寸解码在 100 MP 上会直接 OOM（388 MiB），所以 `inJustDecodeBounds` + `inSampleSize` 不是优化，是必需。
- 峰值还受 `BitmapFactory` 内部临时缓冲、Skia 编码缓冲影响；上表是像素缓冲下界，不是硬上限。
- 若走 ComfyUI，输入尺寸还要和模型/工作流对齐（SDXL/Flux 常见 1024–2048），避免服务端二次缩放。

### 7.6 Mask 的生成与尺寸

- 统一约定：**alpha=0 表示可编辑**（与 OpenAI mask 语义一致；ComfyUI `LoadImage` 返回 `1 - alpha`，也一致）。
- 画笔通常产生「白色=编辑」的灰度图；生成 PNG 时要做 `alpha = 255 - mask`，RGB 可以填 0 或白，只要 alpha 正确。
- OpenAI 要求 mask 与首图**同尺寸**；ComfyUI 的 `/upload/mask` 也按原图尺寸合成。因此 mask 必须在清洗后的同一分辨率上生成，不能拿原图 mask 配缩略图。
- Gemini 没有硬 mask；UI 应把画笔结果转成提示词描述（或作为第二张参考图 + 明确说明），并标注「不保证只改选区」。

### 7.7 EXIF 之外的泄漏面

| 泄漏面 | 事实 | 处理 |
|---|---|---|
| 文件名 | multipart `filename`、ComfyUI `name` 会带原文件名 | 随机 UUID；不要带路径/日期/相机编号 |
| ComfyUI 输出 PNG | `SaveImage` 默认把 `prompt` 与 `extra_pnginfo` 写进 PNG 文本块（除非服务端 `--disable-metadata`） | 下载后不要直接存 PNG；用 `Bitmap` 重编码为 JPEG，顺带丢掉 PNG text chunks |
| 日志 | `Timber` 若打印 URL/header/body | 只打印 provider、状态码、request-id；不打印 Key、prompt、base64 |
| 崩溃上报 | 异常 message 可能含 URL/Key | 自定义 `CloudEditError`，不要把原始异常 message 直接上报 |
| 备份 | 上传临时文件是否进入备份 | `cacheDir` 被 Auto Backup 排除；也可以直接用 `noBackupFilesDir` |
| 提示词 | 可能含人名、地点 | 产品层提示；不要在日志/分析事件中采集 |
| IP/网络元数据 | 任何云端调用都会暴露 IP | 在隐私政策中披露；BYOK 场景由用户选择 provider |

### 7.8 验收测试（可直接写成单测/仪器测试）

1. **无 GPS**：给一张带 GPS 的样图，走清洗管线，`ExifInterface(out).getLatLong()` 为 null。
2. **无设备/序列号**：`TAG_MAKE`、`TAG_MODEL`、`TAG_BODY_SERIAL_NUMBER`、`TAG_LENS_SERIAL_NUMBER` 均为 null。
3. **无拍摄时间**：`TAG_DATETIME_ORIGINAL` 为 null。
4. **无缩略图**：`ExifInterface(out).hasThumbnail()` 为 false。
5. **无 XMP/APP1 残留**：用 `ExifInterface` 逐项断言敏感 tag 为 null，并检查 `hasThumbnail()==false`；可选的字节级启发式是搜索 `Exif\0\0` 与 `http://ns.adobe.com/xap` 标记（不要用 `"GPS"` 这种短字符串，压缩数据里可能误命中）。
6. **方向正确**：带 `Orientation=6` 的样图，输出宽高与视觉方向一致。
7. **尺寸上限**：长边 ≤ 配置值；mask 与 image 尺寸一致。
8. **文件名**：上传文件名是 UUID，不含原文件名。
9. **内存**：在 100 MP 样图上不 OOM（可用仪器测试的 `Debug.MemoryInfo` 观察峰值）。

### 7.9 与仓库的契合度

| 仓库现状 | 结论 |
|---|---|
| `androidx.exifinterface:1.4.2` | 只用于**读 orientation**；不要用于清洗 |
| `BitmapFactory` / `Bitmap` | 清洗管线的主路径，API 24 全可用 |
| `HttpURLConnection` | 足够；multipart 需自己写边界，`setFixedLengthStreamingMode` 流式上传 |
| 无 JSON 库 | 用平台 `org.json`；注意大响应内存 |
| 无 OkHttp | ComfyUI 用轮询而非 WS；OpenAI/Gemini 用 `HttpURLConnection` |
| `BakeSourceProbe` | 已有 orientation 读取逻辑，可抽取复用 |
| `ExifCopier` / `BakeExifWriter` | **不要复用**；它们会复制 GPS/序列号 |
| `GallerySink` | 云端结果应先重编码为 JPEG，再走现有保存链路，避免 PNG text chunks |
| `allowBackup=false` | 已有；密文再放 `noBackupFilesDir` 即可双保险 |

---

## 8. 推荐决策（可执行清单）

1. **先做协议抽象，不做三套 UI**：`CloudEditProvider` + `ProviderCapabilities`；v1 支持 OpenAI 与 ComfyUI（两者都是 HARD mask），Gemini 作为 `SOFT` mask 的第二阶段。
2. **OpenAI**：只依赖 multipart `POST /v1/images/edits`；`model` 用户可配置；`size` 默认 `auto`，需要严格保持构图时显式传同宽高比；不发送 `safety_identifier`；超时不自动重试 POST。
3. **Gemini**：用 Interactions API，`store:false`；`response_format` 指定 `image/jpeg` + 2K；不承诺 mask 硬约束；长任务才用 `background:true` + 轮询。
4. **ComfyUI**：随 APK 固定一份 API 格式工作流，node id 常量化；上传原图 + mask（`/upload/mask`），`/prompt` 后用 1–2 s 轮询 `/history`，`/view` 下载；v1 不引入 WebSocket。
5. **鉴权**：OpenAI `Bearer`；Gemini `x-goog-api-key`；ComfyUI 只支持「反代 Basic/Bearer/自定义头」，不把 `--multi-user` 当鉴权。
6. **Key 存储**：直接 AndroidKeyStore AES-256-GCM，密文放 `noBackupFilesDir`；不引入 `security-crypto`；Tink 仅作为后续多凭证/轮换的备选。
7. **隐私**：所有上传图先走「bounds → inSampleSize → 解码 → 按 EXIF 旋转 → 长边 2048 → JPEG q90 → 校验」；不复用导出链路的 `ExifCopier`；上传文件名用 UUID。
8. **网络**：只允许 `https://`；`targetSdk 37` 前补 `ACCESS_LOCAL_NETWORK` 的声明与运行时请求；不提供「忽略证书错误」。
9. **可观测性**：只记录 provider、状态码、耗时、request-id；不记录 Key、prompt、图片、base64。
10. **验收**：§6.8 的 Keystore 矩阵 + §7.8 的隐私测试；至少覆盖 API 24、28、31、36 各一台真机/模拟器。

---

## 9. 未验证项与风险

| 项 | 状态 | 影响 |
|---|---|---|
| 真机 EXIF 字节级验证 | **未验证**（仅源码推导 + 计划中的测试） | Skia 可能写 ICC/JFIF；不影响 GPS/序列号结论，但需要测试兜底 |
| OpenAI / Gemini 真实调用 | **未验证**（无 Key，不产生费用） | 字段兼容性、429 行为、后台轮询时延需实测 |
| ComfyUI 真实实例 | **未验证** | `/upload/mask` 的 `original_ref` 失败模式、代理超时、版本差异需实测 |
| Tink APK 增量 | **未测**（只有 jar 3.5 MB / 1,993 class） | R8 后可能是数百 KB；若选 Tink 需单独测 |
| Gemini `store` 默认值 | 文档描述为「set store=false to opt out」，未明确默认 | 若默认 true，单次编辑也可能被服务端保留；建议显式传 `false` |
| OEM D2D 备份行为 | 官方说「some device manufacturers」 | `noBackupFilesDir` 可规避；仍需在目标机型验证 |
| `setUnlockedDeviceRequired` 的具体异常类型 | 未在真机验证 | 统一捕获 `GeneralSecurityException` 即可 |
| Android 17 LNP 行为 | 文档确认 API 37 强制；未在 37 设备验证 | `targetSdk 37` 时必须加权限，否则 LAN ComfyUI 超时 |
| OpenAI 兼容网关的能力差异 | 未逐一验证 | JSON 变体、`response_format`、模型枚举可能不同；只依赖 multipart 最小面 |
| 供应商保留/训练政策 | **不在本文范围** | 需要法务/隐私政策单独确认；BYOK 不等于「数据不出设备」 |

---

## 附录 A：复现命令

```bash
# OpenAI 规范（4.3 MB，含 /images/edits 与所有 schema）
curl -sSL -o openai_openapi.yaml \
  https://raw.githubusercontent.com/openai/openai-openapi/master/openapi.yaml
grep -n "/images/edits:" openai_openapi.yaml

# Gemini 文档（Interactions / 图像编辑 / 限额）
curl -sSL https://ai.google.dev/gemini-api/docs/image-generation
curl -sSL https://ai.google.dev/api/interactions
curl -sSL https://ai.google.dev/gemini-api/docs/image-understanding

# ComfyUI 源码（固定 commit）
BASE=https://raw.githubusercontent.com/comfyanonymous/ComfyUI/a2103ae69d98d8ef94c150f5a472b89775eed929
curl -sSL -o server.py   $BASE/server.py
curl -sSL -o execution.py $BASE/execution.py
curl -sSL -o nodes.py     $BASE/nodes.py
curl -sSL -o user_manager.py $BASE/app/user_manager.py

# security-crypto 1.1.0 的 minSdk 与依赖
curl -sSLO https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/1.1.0/security-crypto-1.1.0.aar
unzip -p security-crypto-1.1.0.aar AndroidManifest.xml | strings | grep minSdk
curl -sSL https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/1.1.0/security-crypto-1.1.0.pom | grep -A2 tink

# Tink 1.24.0 的体积/类数
curl -sSLO https://repo1.maven.org/maven2/com/google/crypto/tink/tink-android/1.24.0/tink-android-1.24.0.jar
ls -l tink-android-1.24.0.jar
unzip -l tink-android-1.24.0.jar | grep -c '\.class'

# ExifInterface 1.4.2 源码（看 saveAttributes / thumbnail）
curl -sSLO https://dl.google.com/dl/android/maven2/androidx/exifinterface/exifinterface/1.4.2/exifinterface-1.4.2-sources.jar
unzip -o exifinterface-1.4.2-sources.jar -d exif-src
grep -n "mThumbnailBytes = getThumbnail\|writeExifSegment\|mAttributes\[i\].remove" \
  exif-src/androidx/exifinterface/media/ExifInterface.java
```

---

## 附录 B：一手来源

| ID | 主题 | 链接 |
|---|---|---|
| OPENAI-SPEC | OpenAI OpenAPI 2.3.0（`/images/edits`、schema、模型退役） | https://github.com/openai/openai-openapi/blob/master/openapi.yaml |
| AZURE-IMG | Azure/Foundry 图像编辑路径与 `api-key` 头 | https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/dall-e |
| AZURE-REST | Foundry REST `POST /openai/v1/images/edits` | https://learn.microsoft.com/en-us/azure/foundry/reference/rest/images-create-image-edit-multipart-images-create-image-edit-json |
| GEMINI-IMG | Gemini 图像生成/编辑（模型、语义遮罩、尺寸、Imagen 关停） | https://ai.google.dev/gemini-api/docs/image-generation |
| GEMINI-INTERACTIONS | Interactions API（资源、状态、`background`、`store`、取消） | https://ai.google.dev/api/interactions |
| GEMINI-GETSTARTED | Interactions 快速开始（`store:false`、background 轮询） | https://ai.google.dev/gemini-api/docs/get-started |
| GEMINI-IMAGE-UNDERSTANDING | 图片输入 MIME、20 MB inline 限额、token 计算 | https://ai.google.dev/gemini-api/docs/image-understanding |
| COMFY-SELFHOST | 自托管与「默认无鉴权」警告 | https://docs.comfy.org/development/deploy/self-hosting |
| COMFY-FLAGS | 启动参数（TLS、`--max-upload-size`、CORS） | https://docs.comfy.org/development/comfyui-server/startup-flags |
| COMFY-ROUTES | 路由总表 | https://docs.comfy.org/development/comfyui-server/comms_routes |
| COMFY-MESSAGES | WebSocket 消息与字段 | https://docs.comfy.org/development/comfyui-server/comms_messages |
| COMFY-EXAMPLES | 三种调用方式与 node id 修改示例 | https://docs.comfy.org/development/comfyui-server/api-examples |
| COMFY-RUN | raw Server API「无跨版本兼容承诺」与鉴权矩阵 | https://docs.comfy.org/development/run-workflows/overview |
| COMFY-SRC-SERVER | `server.py`（upload/prompt/history/view） | https://github.com/comfyanonymous/ComfyUI/blob/a2103ae69d98d8ef94c150f5a472b89775eed929/server.py |
| COMFY-SRC-EXEC | `execution.py`（history 结构） | https://github.com/comfyanonymous/ComfyUI/blob/a2103ae69d98d8ef94c150f5a472b89775eed929/execution.py |
| COMFY-SRC-NODES | `nodes.py`（SaveImage / LoadImage alpha） | https://github.com/comfyanonymous/ComfyUI/blob/a2103ae69d98d8ef94c150f5a472b89775eed929/nodes.py |
| COMFY-SRC-USER | `app/user_manager.py`（`comfy-user` 不是认证） | https://github.com/comfyanonymous/ComfyUI/blob/a2103ae69d98d8ef94c150f5a472b89775eed929/app/user_manager.py |
| SEC-RELEASE | AndroidX Security 发行说明（deprecation 时间线） | https://developer.android.com/jetpack/androidx/releases/security |
| ESP-REF | `EncryptedSharedPreferences` 参考（Deprecated in 1.1.0 / 备份警告） | https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences |
| SEC-AAR | `security-crypto:1.1.0` AAR / POM（minSdk 21、Tink 1.8.0） | https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/1.1.0/security-crypto-1.1.0.pom |
| ANDROID-SECURITY | Android 安全最佳实践（Keystore + Tink 建议） | https://developer.android.com/privacy-and-security/security-tips |
| KEYSTORE | Android Keystore 系统（TEE/StrongBox、线程） | https://developer.android.com/privacy-and-security/keystore |
| KEYGEN | `KeyGenParameterSpec`（AES/GCM 示例、API 23） | https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec |
| KEYGEN-BUILDER | `setRandomizedEncryptionRequired`（默认 IND-CPA、拒绝调用方 IV） | https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder |
| KPI | `KeyPermanentlyInvalidatedException`（锁屏移除/指纹重录） | https://developer.android.com/reference/android/security/keystore/KeyPermanentlyInvalidatedException |
| UNAUTH | `UserNotAuthenticatedException` | https://developer.android.com/reference/android/security/keystore/UserNotAuthenticatedException |
| BACKUP | Auto Backup（默认包含项、`allowBackup=false` 的 OEM D2D 例外） | https://developer.android.com/identity/data/autobackup |
| NSC | Network Security Config（cleartext 默认、user CA 信任） | https://developer.android.com/privacy-and-security/security-config |
| LNP | Android 17 本地网络权限 | https://developer.android.com/privacy-and-security/local-network-permission |
| TINK-SETUP | Tink Java/Android 安装（API 24、no proguard） | https://developers.google.com/tink/setup/java |
| TINK-KEYSET | `AndroidKeysetManager`（SharedPreferences、`KeystoreException`） | https://github.com/tink-crypto/tink-java/blob/33fcdc22ada0fc4ed6f7cc0703ffaf55bac783f1/src/main/java/com/google/crypto/tink/integration/android/AndroidKeysetManager.java |
| TINK-AAR | `tink-android:1.24.0` jar（3,508,938 B / 1,993 class） | https://repo1.maven.org/maven2/com/google/crypto/tink/tink-android/1.24.0/tink-android-1.24.0.jar |
| EXIF-SRC | `ExifInterface` 1.4.2 源码（setAttribute/remove、saveAttributes、thumbnail）；行号取自 1.4.2 sources jar，`androidx-main` 可能漂移 | https://github.com/androidx/androidx/blob/androidx-main/exifinterface/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java |
| EXIF-REF | `ExifInterface` 参考 | https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface |
| AOSP-BITMAP-JNI | `Bitmap_compress` JNI | https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/libs/hwui/jni/Bitmap.cpp#L503-L517 |
| AOSP-BITMAP-HWUI | `Bitmap::compress`（只传 quality 给 SkJpegEncoder） | https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/libs/hwui/hwui/Bitmap.cpp#L596-L600 |
| AOSP-BITMAPFACTORY | `BitmapFactory.Options`（inSampleSize / inJustDecodeBounds） | https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/graphics/java/android/graphics/BitmapFactory.java#L120-L138 |
| AOSP-BITMAP | `Bitmap.createScaledBitmap`（filter） | https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/graphics/java/android/graphics/Bitmap.java#L841-L865 |
| SKIA-JPEG | `SkJpegEncoder::Options`（无任意 EXIF 入口） | https://github.com/google/skia/blob/main/include/encode/SkJpegEncoder.h |
| LOAD-BITMAP | 官方大图加载（inSampleSize 计算） | https://developer.android.com/topic/performance/graphics/load-bitmap |
| BASE64-REF | `java.util.Base64`（API 26） | https://developer.android.com/reference/java/util/Base64 |
| HTTP-REF | `HttpURLConnection`（streaming mode） | https://developer.android.com/reference/java/net/HttpURLConnection |
| REPO-PROBE | 仓库内 orientation 读取 | `editor/src/main/java/cn/devcxl/photosync/editor/export/BakeSourceProbe.kt:23-56` |
| REPO-EXIFCOPIER | 仓库内导出 EXIF 白名单（含 GPS/序列号） | `editor/src/main/java/cn/devcxl/photosync/editor/export/ExifCopier.kt:46-104` |
