# 0010 — 统一设置存储与顶栏入口收敛

- 状态：已采纳
- 日期：2026-11-28
- 影响范围：`:editor/settings`（新增 `SettingsStorage`、`EditorSettings`）、`:app/data/settings`（新增 `AppSettings`）、`:app/activity`（新增 `SettingsActivity`、`MainActivity` 顶栏入口）、`:app/res`（`activity_settings.xml`、`ic_settings_24`、`strings_settings.xml`）

## 背景与目标

设置项此前散落在 5 个 `SharedPreferences` 文件与多处内存变量中（见调研规范 §2.5）：相机删除联动在 `photosync_prefs`，联机预设 id 在 `photosync_editor_prefs`，GPU 黑名单在 `mediapipe_delegate`，云端状态在 `photosync_cloud_providers`，而自动跳转、导出质量、预览长边、撤销深度、ORT 线程与闲置释放要么不持久化，要么写死在代码常量里。用户找不到统一入口，也无法从界面判断某项改动何时生效。

本 ADR 记录统一设置基线（S1）的两项结构性决策。逐项接线、存储统计与完整诊断由后续工单（S2）负责。

## 关键决策

### 1. 两个类型化门面共用一个 prefs 文件，不新建 `:core-settings` 模块

- 单一文件 `photosync_settings`（`SettingsStorage.FILE_NAME`）。
- `:editor` 模块的 `EditorSettings` 拥有编辑器与 AI 键：`editor_preview_max_edge`、`export_jpeg_quality`、`preview_cache_jpeg_quality`、`editor_undo_depth`、`ai_tier_override`、`ai_intra_op_threads`、`ai_idle_release`、`ai_inpaint_default_mode`、`ai_inpaint_brush_px`，以及以 `ai_inpaint_tile_ms_<tier>_<variant>` 为前缀的单 tile 实测耗时。
- `:app` 模块的 `AppSettings` 拥有联机与相册键：`tether_auto_follow`、`tether_delete_on_camera`、`display_keep_screen_on`、`tether_live_preset_enabled`、`tether_live_preset_apply_ai`。
- 键集合严格互斥，各自导出 `ALL_KEYS`，由 `AppSettingsTest` 断言交集为空、动态 tile 前缀不与 `AppSettings.ALL_KEYS` 冲突。
- 依赖方向保持 `:app → :editor` 单向：`:editor` 只提供 `SettingsStorage` 常量与编辑器门面，`:app` 引用它打开同一文件并追加自己的键。

**理由**：20 个键不值得新增一个 Gradle 模块、一套构建配置与一层 API 复制；共用文件让备份、诊断汇总和"重置全部"天然只看一个 artifact。共享文件不等于共享状态：每个门面只读写自己的命名空间。

**风险与缓解**：键名冲突会导致一个门面覆盖另一个。缓解是 `ALL_KEYS` 互斥测试与统一前缀（编辑器侧全部以 `editor_` / `export_` / `preview_` / `ai_` 开头，联机侧全部以 `tether_` / `display_` 开头），并且任何新增键必须同时更新互斥测试。另一个风险是从旧安装带过来的同名异义值；`EditorSettings` 对枚举与整数都做合法性回退与 clamp，遇到不认识的持久化值退回默认。

### 2. 顶栏"AI 模型"图标直接替换为齿轮设置入口，不保留两个入口

- `activity_main.xml` 的 `aiModelsButton` 删除，替换为 `settingsButton`（`ic_settings_24`，contentDescription「设置」）。
- 点击进入 `SettingsActivity`（`android:exported="false"`）。
- `AiModelSettingsActivity` 与 `CloudSettingsActivity` 保留为独立 Activity，但在设置页内降级为二级入口，页面深度只增加一层。

**理由**：用户反馈"找不到设置"。并存的 AI 模型入口会继续把注意力吸引到一个二级功能上，且让"设置"这个概念没有唯一的归宿。直接替换是成本最低、信息架构最清晰的做法；保留二级页避免一次性把两个大页面折进统一页，降低回归风险。

**风险与缓解**：主界面少了一个"一步直达 AI 模型"的快捷方式。缓解是设置页把「AI 模型管理」放在第三个分组顶部，从主界面进入只需多一次点击；`AiModelSettingsActivity` 自身的下载/导入/删除逻辑完全不变。

## 迁移

仅迁移 `photosync_prefs.delete_on_camera → tether_delete_on_camera`：

- 首次读取 `AppSettings.deleteOnCamera` 时，若新键不存在且旧键存在，复制一次（`migrateLegacyDeleteOnCameraIfNeeded`）；新键一旦存在，后续读取不再覆盖，保证幂等。
- 每次写入新键同时双写旧键，回滚到旧版本时用户的勾选不丢失。
- 云端开关、联机预设 id、GPU 黑名单不迁移：云端总开关只做只读镜像（密钥与状态必须同源）；预设 id 仍由 `RoomEditStore` 拥有；GPU 黑名单只在诊断页展示。
- `AppSettings.resetAll()` 同时移除旧键，避免重置后被下一次读取重新迁移。

## 生效时机约定

每个键在 KDoc 与设置页文案中标注"即时生效"或"下次生效"：

- 即时：联机自动跳转、相机删除联动、屏幕常亮、联机预设开关、导出质量（下一次导出）、消除默认档与笔刷（下一次打开面板）。
- 下次打开编辑器生效：预览分辨率（天空预览按 1024/768/512 同步）、撤销深度、预览缓存质量、推理线程数（下一次创建会话）。
- 设备档位覆盖只改变运行策略与内存预算，不改变 `EditGraph` 语义或像素结果；`resolveMemoryTier(detected)` 是所有 AI 调用点的唯一解析入口。

## 不进入设置页的项

相册排序（无实现）、导出文件命名规则（规则稳定）、云端默认开启（安全默认必须关闭）、MediaPipe GPU 黑名单（内部降级结果，只在诊断页只读展示）。

## 验收覆盖

- `:editor/test`：`EditorSettingsTest`（全部键默认值、读写、越界 clamp、坏值回退、tier override 解析、单 tile 耗时按档位/变体存取与非法值清理、`resetAll`）。
- `:app/test`：`AppSettingsTest`（默认值、读写、`delete_on_camera` 幂等迁移与双写、`resetAll`、与 `EditorSettings` 键集合互斥）；`SettingsActivityTest`（Robolectric 六组渲染与两个二级页跳转）。
