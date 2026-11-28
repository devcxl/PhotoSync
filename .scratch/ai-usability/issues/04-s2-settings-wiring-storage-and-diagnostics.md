# 04: S2 设置项全量接线、存储统计清理与诊断页

**What to build:** 让统一设置页里的每一项都真正作用到运行时，并补齐存储与诊断：把联机与相册相关设置（新照片自动跟随持久化、相机删除联动、浏览时屏幕常亮、联机预设自动套用总开关以及是否把 AI 参数交给入库预处理）、编辑器相关设置（预览长边与天空预览按比例同步、导出 JPEG 质量与预览缓存 JPEG 质量、撤销深度、LUT 库的导入/删除/占用）与 AI 运行时相关设置（设备内存档位覆盖、推理线程数、会话闲置释放时长、天空素材管理）全部接入真实生效点，消除"代码常量与设置值双真相"；对每项明确标注"即时生效"还是"下次打开生效"（预览分辨率、线程数、缓存质量属于后者），需要重建推理会话的项在下一次取用时才换新，不打断正在进行的操作。实现存储占用统计：按模型、AI 编辑资产、预览缓存、LUT、天空五类分别汇总，提供分类清理与一键清理，清理前后统计一致，且删除模型或缓存不破坏既有编辑图记录与外置资产引用。补齐"关于与诊断"只读页：应用版本、设备内存档位、GLES Tier、推理运行时版本、各模型安装与最近一次校验状态、MediaPipe GPU 降级状态以及当前生效设置汇总，全部只读展示，不可在此处修改；网络权限来自传递依赖这一观感落差也如实呈现，不隐藏。

**Blocked by:** 01: D1 模型分发修复与发布校验, 02: S1 统一设置页基底、类型化存储与顶栏齿轮入口

**Status:** resolved

- [x] 联机与相册、编辑器及 AI 运行时全部可调设置项接入真实运行路径（消除专属默认档/笔刷键归 05 接线），并明确区分即时生效与下次打开生效。
  - 证据：`EditorSettingsTest`（默认值、越界 clamp、生效快照、与 app 侧键不重叠）、`EditorRenderSettingsTest`（预览长边驱动天空按比例同步、线程默认值对齐 `OrtSessionRuntimeConfig`、屏幕常亮键读取）、`AppSettingsTest`（相机删除镜像到旧键、屏幕常亮共享键名、迁移幂等）、`TetheredPhotoIngestorTest`（联机预设总开关关闭时跳过两个 AI 预处理、预设缺失/渲染失败降级为未调色导入）、`RoomEditStoreTest`（预设总开关关/开后隐藏与恢复）、`OrtSessionManagerTest`（线程数变化重建会话、配置化闲置释放、越界 clamp）、`EditorPreviewCapsWiringTest`、`PreviewQualityWiringTest`、`BakeRequestQualityTest`、`EditorUndoDepthWiringTest`、`SettingsActivityTest`。即时/下次生效的区分落在 UI hint 上：`strings_settings.xml` 对预览分辨率、导出质量、撤销深度、推理线程数、闲置释放、消除档位/笔刷均标注「下次…生效」，其余以 `settings_value_immediate` 标为立即。
- [x] 存储统计器准确汇总五类目录占用并支持分类清理与一键清理，删除模型或缓存不破坏既有编辑图记录。
  - 证据：`StorageUsageReporterTest`（五类精确求和 155/10/20/30/40 → 总 255、缺失目录为 0、分类清理只清自身且他类不变、清理后统计归零、缺失分类清理为 no-op、一键清理覆盖全部、清理不触碰 `files/databases`）、`SettingsActivityTest`（实时占用渲染、单类清理确认后数字刷新）。
- [x] 关于与诊断分组只读展示版本、设备档位、GLES Tier、ORT 版本、各模型安装与最近校验状态、GPU 降级状态及当前生效设置汇总。
  - 证据：`SettingsDiagnosticsSection` 以只读视图拼装应用版本、内存档位、GLES Tier、`OnnxRuntimeInfo.VERSION`、模型探测日志、MediaPipe GPU 降级与当前生效设置汇总，不发起网络请求；`SettingsActivityTest#shouldRenderTheCompleteDiagnostics_whenOpened`；`GlCapabilityReportTest`；`OnnxRuntimeInfoTest`（版本与 `libs.versions.toml` 漂移即失败）。网络权限按 manifest 合并结果如实展示（`SettingsDiagnosticsSection` 读取 `INTERNET`/`ACCESS_NETWORK_STATE`），不隐藏传递依赖带来的权限。
- [x] 每个接线点与存储统计清理均具备单元测试覆盖（默认值、自定义值、越界 clamp、清理前后统计校验）。
  - 证据：上述接线点测试均同时覆盖默认值/自定义值/越界 clamp 三元；`StorageUsageReporterTest` 覆盖清理前后统计一致；全量 `:editor:testDebugUnitTest` 146 类 1008 tests、`:app:testDebugUnitTest` 54 类 417 tests 全绿。

**Verification:** 合入 `feat/ai-tools` 为 merge commit `e6be9e7`（父 `ee74ab0` + `a723c80`），`git merge a723c80` 无冲突：唯一交叉文件 `MiGanInpainterTest.kt` 两侧改动落在不同区块（03 新增 pipeline 用例 + 04 的 `FakeSessionFactory` 两参签名），git 自动合并后 `MiGanInpainterTest` 10 tests 全绿，03 的 `shouldRunThePipelineVariant_whenTheSignatureSaysUint8`、`shouldEmitOnePatchPerTile_whenTwoHolesAreFarApart` 保留通过。集成验证：`:editor:testDebugUnitTest --tests "*Settings*" --tests "*OrtSessionManager*" --tests "*StorageUsage*" --tests "*MiGan*" --tests "*Migan*" --tests "*Inpaint*"` 140 tests 0 failures；`:app:testDebugUnitTest --tests "*Settings*" --tests "*StorageUsage*" --tests "*RoomEditStore*" --tests "*TetheredPhotoIngestor*"` 48 tests 0 failures；`:editor:compileDebugKotlin`、`:app:compileDebugKotlin`、`:editor:compileDebugAndroidTestKotlin`、`:app:compileDebugAndroidTestKotlin` 均 BUILD SUCCESSFUL。
