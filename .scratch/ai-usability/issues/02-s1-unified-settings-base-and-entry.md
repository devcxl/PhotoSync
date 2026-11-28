# 02: S1 统一设置页基底、类型化存储与顶栏齿轮入口

**What to build:** 建立统一设置页的基底，让用户在一个地方就能找到全部可调配项：新增一套类型化设置存储，单一持久化文件承载两套门面——编辑器与 AI 设置门面（预览、导出与缓存质量、撤销、推理线程与闲置释放、设备档位覆盖、笔刷与消除档）与应用与联机设置门面（自动跟随、相机删除联动、屏幕常亮、联机预设开关）——两套门面键集合严格互斥、各写各的，共享同一文件只为便于备份与"重置全部"，依赖方向不得反转。每个键都声明类型、默认值、合法范围与越界 clamp 行为、设备档位覆盖的解析契约，并持久化单 tile 实测耗时供后续工作量估算使用。旧相机删除联动键做幂等迁移：首次启动时若新键为空且旧键存在则复制一次，旧键继续双写以支持回滚，其余旧键（云端开关、联机预设 id、GPU 黑名单）不迁移，只在设置页或诊断页做只读镜像。把主界面顶栏原来的"AI 模型"图标直接替换为齿轮设置入口，不保留两个入口并存；交付一个新的、不对外导出的统一设置页，按六个一级 `SettingsGroup` 组织——联机与相册、编辑器、AI 工具、云端增强、存储与缓存、关于与诊断——按"用户想找什么"划分而不是按代码模块划分；原 AI 模型管理与云端配置降级为该页内的二级页，保留各自独立页面以降低一次性大重构风险。明确排除相册排序、导出命名规则、云端默认开启与 GPU 黑名单这类不应成为用户选项的项。记录一条 ADR：两套门面共用单一偏好文件而不新建独立设置模块，以及顶栏入口由 AI 模型收敛为设置齿轮。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 编辑器/AI 设置门面与应用/联机设置门面共用同一持久化存储但键集合严格互斥，单测验证全部默认值、越界 clamp、键集合无重叠及相机删除联动键幂等迁移与旧键双写。（`EditorSettingsTest` 13/13 + `AppSettingsTest` 10/10 通过：默认值、越界 clamp、`shouldNeverOverlapEditorKeys…`、`shouldCopyLegacyValueOnce…`、`shouldRemainIdempotent…`、`shouldMirrorToLegacyKey…`。）
- [x] 主界面顶栏「AI 模型」图标替换为齿轮设置入口，点击进入非导出的统一设置页；不保留双入口。（`MainActivity` 顶栏 `settingsButton` → `SettingsActivity`；`activity_main.xml` 仅保留 `ic_settings_24` 齿轮入口；manifest 中 `SettingsActivity` 为 `exported=false`。）
- [x] 统一设置页完整渲染 6 个 `SettingsGroup` 分组，且可跳转至 AI 模型管理与云端增强二级页并通过冒烟测试。（`SettingsActivityTest` 4/4 通过：6 个分组标题与卡片渲染、AI 模型行与云端行点击跳转。）
- [x] 新增 ADR 记录双门面共用单文件及顶栏入口收敛决策。（`docs/adr/0010-unified-settings-store-and-entry.md`。）
