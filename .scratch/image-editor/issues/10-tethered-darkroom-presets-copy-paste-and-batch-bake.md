# 10: 联机暗房闭环：用户预设、LiveTetherPreset 自动套用、跨图复制粘贴与批量烘焙队列

**What to build:** 将单张编辑器升级为完整的 USB 联机暗房（Tethered Darkroom）工作流：支持用户在编辑器内将当前调色保存为 `Preset`（含内置参数预设），在主界面底部胶片条上方提供可折叠的「快捷预设与联机操作栏」（零跳转一键切换 `Preset`、开启/关闭 `LiveTetherPreset` 联机自动套用、跨照片按 `CopyMask` 复制/粘贴修改、主界面按住大图实时对比未编辑原图）；当 `LiveTetherPreset` 开启时，USB PTP 每同步（`Sync`）入库一张新照片即自动绑定该预设并后台生成预览缓存；当用户在 `EditorActivity` 内修图时，后台新同步照片静默入库绝不抢焦点（仅顶栏轻提示 `+N 新照片`）；支持多选照片加入后台批量烘焙导出队列并在主界面右上角展示进度。

**Blocked by:** 09: NDK libjpeg-turbo 三档源图驻留与列带流式分块烘焙导出切片（Bake Pipeline）

**Status:** resolved

- [x] 支持将当前 `EditGraph` 保存到 `user_presets` 表、重命名与删除，并提供主界面底部「快捷预设与联机操作栏」与编辑器「预设」分组面板。
- [x] 打通 `LiveTetherPreset`：开启后持久化于 `SharedPreferences`，相机 `Sync` 新照片入库时自动为其创建绑定该预设的 `PhotoEditEntity` 并生成预览缓存，`AutoFollow` 切换过去时直接显示套好预设的画面。
- [x] 打通编辑态联机免打扰保护：当 `EditorActivity` 处于前台时，新照片后台静默入库并套用 `LiveTetherPreset`，不触发主界面焦点抢夺，仅在编辑器顶部更新 `+N 新照片` 胶囊。
- [x] 实现 `CopyMask` 复制/粘贴参数：短按「复制参数」默认排除 `{geometry.*, portrait.reshape, portrait.body}`，长按可勾选包含项；支持粘贴至当前照片或多选照片。
- [x] 实现主界面大图长按（`> 250ms`）实时对比未编辑原图（Before/After），以及基于 `WorkManager`（RAW 并发固定为 1）的后台批量 `Bake` 导出队列与右上角进度卡片。
