# 0009 — 联机暗房：用户预设、`LiveTetherPreset`、跨图复制粘贴与批量烘焙队列

- 状态：已采纳
- 日期：2026-11-23
- 影响范围：`:editor/domain`（`BuiltinPresets`、`CopyMask`、`PresetGraphMerger`、`LiquifyMeshCopier`、`EditorUndoController`）、`:editor/contract`（`UserPresetRecord`、`EditStore`、`EditorHostRegistry`）、`:editor/render`（`BackgroundPreviewRenderer`、`PreviewCacheWriter`）、`:editor/export`（`BakeConcurrencyGate`）、`:editor/ui`（`EditorPresetSpecs`、`PresetPanelBinder`、`PresetPanelView`、`EditorActivity`、`EditorPanelController`）、`:app/data`（`UserPresetDao`、`AppDatabase`、`PhotoDao`、`RoomEditStore`、`TetheredPhotoIngestor`、`RoomTetheredPhotoCommitter`、`App`）、`:app/export`（`BatchBakeWorker`、`BatchBakeController`、`BatchBakeProgress`）、`:app/adapter`（`PhotoCompareGesture`、`PhotoPagerAdapter`、`FilmstripAdapter`）、`:app/activity`（`MainActivity`、`TetheredDarkroomCoordinator`、`TetheredDarkroomHost`、`TetheredQuickStripController`、`MainActivityCopyPasteHelper`、`BeforeAfterCompareController`）

## 背景与目标

单张编辑器只能针对已打开的单张照片调色，无法满足 USB 联机拍摄（Tethered Darkroom）现场快修闭环的四项核心需求：

1. 将当前非破坏性参数保存为可复用的纯参数 `Preset`（含内置参数预设）；
2. 开启 `LiveTetherPreset` 后，相机 `Sync` 下载的新照片自动套用该预设并生成预览缓存，在 `AutoFollow` 切换到新图时直接展现调色后观感（编辑态下则静默入库、不抢焦点）；
3. 支持按 `CopyMask` 跨照片复制/粘贴调色参数（默认排除单图构图/液化/面部美体，长按可自定义勾选）；
4. 支持主界面大图长按（`> 250ms`）实时对比未编辑原图（Before/After），以及多选照片进入后台 `WorkManager` 批量烘焙导出队列（RAW 并发固定为 1）。

## 关键决策

### 1. 纯参数 `Preset` 与统一 `CopyMask` 语义

- `CopyMask` 默认包含 `{baseLook, tone, color, detail, effects, portrait.skin}`，默认排除 `{geometry.*, portrait.reshape, portrait.body}`；
- `BuiltinPresets` 提供 5 组纯参数预设（`builtin_clean_portrait`、`builtin_film_chrome`、`builtin_warm_sunset`、`builtin_cool_nordic`、`builtin_mono_silver`），其本地化名称通过 `:editor` `strings.xml` 解析，保持 `domain/` 零 Android / `contract/` 依赖；
- 「保存为预设」「应用预设」「清除预设（`PresetGraphMerger.resetToOriginal`，把整张图重置回原始状态）」及跨图复制粘贴全部复用同一 `CopyMask` 与 `PresetGraphMerger` 契约，避免逻辑漂移；
- 长按「复制参数」勾选的自定义 `CopyMask` 随快照保存在 `EditClipboard.Entry` 中，`includeLiquify = true` 时通过 `LiquifyMeshCopier` 为目标照片复制独立的 `.bin` 网格副本。

### 2. `user_presets` 持久化与 SQL 层内置只读保护

- `UserPresetDao` 对 `rename` / `deleteById` / `updateUserRow` 均附带 `AND is_builtin = 0`，`upsertUserPreset` 在事务内拦截内置预设 ID，`RoomEditStore` 层同步校验 `isBuiltin`，防止任何重构误改内置预设库；
- `RoomEditStore.seedBuiltinPresets()` 以 `OnConflictStrategy.IGNORE` 幂等播种，并按当前系统语言刷新内置预设显示名称。

### 3. `LiveTetherPreset` 原子入库排序与预览并发控制

- `LiveTetherPreset` ID 持久化于 `SharedPreferences` 键 `pref_live_tether_preset_id`；
- `TetheredPhotoIngestor` 严格按「读取预设 → 在独立临时目录通过 `BackgroundPreviewRenderer.renderToDir` 预渲染预览图 → 在同一 Room `@Transaction` 内插入 `PhotoEntity`、移动预览文件并插入 `PhotoEditEntity`（若预渲染失败则回退为原图入库，绝不写入无预览的半绑定 `PhotoEditEntity`）」顺序执行；
- `BackgroundPreviewRenderer` 与 `BakeExporter` 共用进程级 `BakeConcurrencyGate.rawBakeMutex`，确保 RAW 后台预渲染与全分辨率烘焙串行，守住 512MB 内存红线。

### 4. `App` 持有 PTP 下载接收器与编辑态静默入库

- `UsbPtpConnectionController.setFileDownloadedListener` 由 `App` 统一注册，避免在 `EditorActivity` 前台时因 `MainActivity` 暂停而漏接新片；
- 新照片入库后，`:editor` 在 `RESUMED` 阶段收集 `EditorHostRegistry.tetheredPhotoEvents` 并更新顶部 `+N 新照片` 轻提示胶囊；`MainActivity` 通过 `dao.getAllFlow()` 增量识别新片，彻底消除事件流与 Room 失效通知的竞态。

### 5. `WorkManager` 批量烘焙与 Before/After 对比状态机

- `BatchBakeWorker` 在唯一队列 `photosync_batch_bake`（`ExistingWorkPolicy.APPEND_OR_REPLACE`）内顺序处理所选照片，结合 `BakeConcurrencyGate.rawBakeMutex` 保证 RAW 并发固定为 1；`BatchBakeController` 在初始化即订阅 `WorkManager` 进度并在 `FINISHED`/`CANCELLED` 后自动收起卡片；
- `HoldGestureState` + `PressAndHoldDetector`（260ms 阈值）在 `CompareAwareFrameLayout.onInterceptTouchEvent` 上识别单指长按对比原图，并在 `Dispatchers.IO` 解码原图至 `OriginalPreviewCache` 后通过 `PhotoPagerAdapter.showImageOverride` 瞬时切换 Before/After。

## 验收覆盖

- `:editor/test`：`CopyMaskTest`、`BuiltinPresetsTest`、`PresetToolCatalogTest`、`PassCClaritySharpenTest`；
- `:app/test`：`RoomEditStoreTest`、`RoomPresetStoreTest`、`TetheredPhotoIngestorTest`、`MainActivityCopyPasteHelperTest`、`PhotoPagerCompareDecisionTest`、`BatchBakeProgressTest`。
