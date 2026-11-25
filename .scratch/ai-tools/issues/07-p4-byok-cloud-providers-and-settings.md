# 07: P4 云端 BYOK 协议引擎（OpenAI-compatible / Gemini / ComfyUI）与配置页切片

**What to build:** 基于零重依赖网络栈（标准库 HTTP 与 JSON）实现 `CloudEditProvider` 抽象（含能力声明 `maskMode: HARD|SOFT|NONE`、上传上限、异步标志及可取消的 `submit → poll → fetch` 生命周期）与首批 3 类适配器：1) **OpenAI-compatible**（multipart `/v1/images/edits`，支持自定义 Base URL 与用户填写模型名，严禁硬编码已退役模型，支持 `alpha=0` 可编辑的硬 Mask）；2) **Gemini Interactions API**（`/v1beta/interactions`，`x-goog-api-key`，支持 `background: true` 异步轮询与默认 `store: false` 退出服务端留存，声明 `SOFT` 语义局部编辑）；3) **自建 ComfyUI**（纯 HTTP 轮询 `/upload/image → /upload/mask → /prompt → /history/{id} → /view`，校验 `/upload/mask` 返回非空路径防静默 200，支持用户导入自定义 workflow JSON 并按 node id 绑定图像/掩码/提示词输入及可选反代 Auth Header）。在应用设置页交付默认关闭的「云端生成式增强（BYOK）」总开关、Provider 增删改查配置与「连通性测试」按钮，并确保 OpenAI / Gemini POST 超时绝不自动重试（防重复扣费）。

**Blocked by:** 04: P4 BYOK AndroidKeyStore 密钥库与闭世界 EXIF 隐私清洗管线

**Status:** resolved

- [x] 应用默认安装后「云端生成式增强（BYOK）」总开关处于关闭状态，未主动开启前绝不向任何外部端点发起请求。（`CloudNetworkPolicyTest` / `CloudProviderConfigStoreTest` 锁定默认关闭与短路）
- [x] 设置页支持新增、编辑、切换与删除 OpenAI-compatible、Gemini 与 ComfyUI 配置，密钥经 AndroidKeyStore 加密保存，表单回显脱敏且支持一键连通性测试与清晰错误反馈。（配置存储与脱敏由 `CloudProviderConfigStoreTest` / `ByokSecretStoreTest` 覆盖；设置页 UI 仅编译与 lint 通过，真机交互未跑）
- [x] 三类适配器通过本地回环桩服务器（Mock HTTP）单测：验证 OpenAI multipart 编码与 `alpha=0` 硬 Mask 转换；验证 Gemini `store: false`、`background: true` 轮询与 base64 图片提取；验证 ComfyUI 上传/节点注入/轮询/拉取全链路、`/upload/mask` 静默 200 异常检测以及路径穿越文件名拦截。（`OpenAiEditProviderTest` / `GeminiEditProviderTest` / `ComfyUiEditProviderTest` 跑本地回环服务）
- [x] 超时与取消测试通过：OpenAI 与 Gemini 读超时立即返回可恢复错误且发包计数恒为 1（禁止自动重试）；ComfyUI 超时重试前先查询 `/history/{prompt_id}`；取消协程立即中断传输并清理临时上传文件。
