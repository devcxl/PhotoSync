# 01: D1 模型分发修复与发布校验

**What to build:** 修正按需 AI 模型的分发链路，让用户能真正下载到可用模型，并且看懂失败原因：MODNet 人像抠图移除已经返回 404 的失效主源，只保留经字节数与 SHA-256 双校验的两条镜像（HuggingFace 上游与同域镜像）；MI-GAN 智能消除整体切换为上游社区以 MIT 许可发布的 pipeline 变体（三源字节一致：HuggingFace 上游仓库为主源、同域镜像为第二源、字节一致的第三方镜像为第三兜底），旧 generator 条目降级为只读的本地导入识别记录，不再出现在下载按钮上，只用于识别用户已持有的旧文件，并附带可读的消除模型变体提示（实际判据仍是运行时签名探测）。下载链路把 HTTP 状态码透传给上层，把失败细分为八类原因——目录未发布、源已下线、源拒绝访问、传输中断、字节数不符、校验摘要不符、模型签名不兼容、存储不可用——每类原因绑定可执行动作（换镜像、本地导入、继续下载、清理存储后再试），不再把"源不存在"误报成"网络问题"。AI 模型管理页与编辑器内首次安装弹窗共用同一套状态机与同一套失败文案；模型状态只取最近一次真实探测结果：从未探测即"未安装"，探测全部失败且最近失败为源已下线时显示"源已下线"、主按钮切为本地导入，下载按钮保留但标注"可能不可用"，探测只在用户显式触发下载时发生，不做后台轮询。下载中允许取消并保留断点文件（`.part`）以便续传，进度展示"已下载 / 总大小"与百分比，"重新下载"需二次确认且确认前不删除已安装模型。最后新增目录校验脚本作为发布护栏：遍历每个模型的全部镜像做 HEAD 与定长 Range 探针（要求可访问且支持 Range），对至少一个源完整下载并比对字节数与 SHA-256，任一模型的全部源不可达即非零退出；CI 增加只跑该脚本的目录门禁任务，防止"pin 指向空发布"重演。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] MODNet 仅保留经校验的双镜像，MI-GAN 切换至经三源校验的 MIT pipeline 条目，旧 generator 保留为只读兼容识别条目且不出现在下载按钮上。（`AiModelCatalogTest` 6/6 通过：`shouldPinTheVerifiedModnetMirrors…`、`shouldPinTheVerifiedMiganPipeline…`、`shouldKeepTheLegacyGeneratorReadOnly…`；`AiModelDownloadManagerTest#shouldRejectDownload_whenTheReadOnlyLegacyEntryHasNoMirrors` 锁定旧条目无下载镜像。）
- [x] 下载层透传 HTTP 状态码并完整覆盖 8 类失败原因；所有镜像 404/410 归类为 `SourceGone`（主动作切为本地导入），401/403 归类为 `SourceDenied`，网络中断归类为 `Transport` 并保留断点。（`AiModelDownloadManagerTest` 33/33 通过：404/410→`SourceGone`、401/403→`SourceDenied`、超时/重置→`Transport`、字节数/摘要/签名/存储各类原因与 `.part` 保留续传各有专测。）
- [x] AI 模型管理页与编辑器首次安装弹窗共用同一状态机与失败文案；下载中可取消并保留 `.part`，进度展示已下载/总大小与百分比，重新下载需二次确认且确认前不删除已安装模型。（`AiModelInstallUiTest` 14/14 通过（8 类文案与动作、断点续传、进度字节、重下载确认）；`AiModelSettingsTextTest` 2/2 与 `AiModelPromptDialogTest` 1/1 锁定两处共用同一失败文案。）
- [x] 目录校验脚本可探针 Range 与校验至少一个源的字节数与 SHA-256，CI 新增 `catalog` 门禁任务，且单元测试锁定目录定义与脚本清单完全一致。（`tools/verify-ai-model-catalog.sh` 做 HEAD + 定长 Range 探针并完整下载比对字节数与 SHA-256；`.github/workflows/ci.yml` 新增 `catalog` job；`AiModelCatalogTest#shouldMatchTheVerifyScriptManifest_whenTheCatalogChanges` 锁定两者清单一致。）
