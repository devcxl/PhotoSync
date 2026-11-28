# 0012 — 任意范围消除：去面积硬拒、预算分批与渐进提交

- 状态：已采纳
- 日期：2026-11-29
- 影响范围：`:editor/domain`（`InpaintBatchPlanner`、`InpaintTileEta`、`InpaintProgressiveCommit`）、`:editor/ui`（`EditorInpaintController`、`InpaintBrushController`、`InpaintProgressiveRunner`、`InpaintPanelView`）
- 上位规范：`.scratch/ai-usability/spec.md` §7、§10、§12（决策 #3）

## 背景

E1 已经把消除补丁改成"一个 tile = 一个 patch"，内存上界与涂抹总面积无关；但用户侧仍有三处
断裂：

1. 一笔稍大就弹琥珀色"请分次涂抹"（阈值 256 px），继续涂抹则抛 `InpaintPlanTooLargeException`，
   直接以"消除范围过大"结束且不产生任何修复。256 px 比规划器真实能力低约 8 倍，属于纯噪音。
2. 一次运行要么全部成功、要么全部丢弃；`EditorInpaintController` 只在整体成功时提交 `EditGraph`，
   取消一次运行会让已完成的部分白做。
3. 笔刷以"源宽百分比"显示（默认 4%，24 MP 上约 240 px），用户看不到像素直径，也无法判断工作量；
   首次运行前也没有耗时预估。

## 关键决策

### #3 消除不再有面积硬上限，改为预算分批 + 渐进提交

#### 预算分批

- 删除 `INPAINT_OVERSIZE_LIMIT_PX`、`showOversizeWarning`/`hideOversizeWarning` 契约方法、
  `editor_inpaint_panel.xml` 的 `inpaintOversizeWarning` 节点与对应字符串，不留空壳。规划器也不再
  抛面积异常。
- 单次运行的预算由 `InpaintCropSpec` 固定：单 tile 裁切 `STANDARD ≤ 1024` / `LOW ≤ 768`，单批 tile
  `96` / `24`。超过预算只是切出更多批次，**永不作为拒绝理由**。
- `InpaintBatchPlanner` 按 `batchTiles` 把 `InpaintCropPlan.tiles` 切成有序 `InpaintBatches`，
  批只是执行与提交的节奏单位；每个 tile 仍产出自己的 `InpaintPatchRef`。
- 面板的工作量估算 `InpaintTileEta` 只依赖 bbox 与档位，不栅格化掩码：
  - 优先读取 `EditorSettings.getMeasuredInpaintTileMs(tier, variant.name)` 的本地实测值；
  - 无实测值时回退 `LOW = 3000 ms/tile`、`STANDARD = 1200 ms/tile`；
  - 预估为 0 或无效时只显示"预计 N 块"，否则显示"预计 N 块 · 约 M 秒"；
  - `M > 15` 时追加非阻断提示"将分批处理，可随时停止"，确认按钮始终可用。

#### 渐进提交

- `InpaintProgressiveRunner` 逐批执行 `inpainter.inpaintTiles(...)`。每完成一批：
  - 把该批 patch 追加到 `graph.inpaint`，并提交一次独立撤销事务（**一批 = 一个撤销步**）；
  - 推理条文案更新为"第 n/N 批"，进度按已完成 tile 计算；
  - 把本批实测单 tile 均值耗时写回 `EditorSettings.setMeasuredInpaintTileMs(tier, variant.name, avg)`，
    作为下一次预估的本地基线。
- 全部完成时最后一批已经提交，不再额外产生撤销事务，只清理笔迹与收尾。
- **取消**：取消协程 → 已完成批次保留；`InpaintProgressiveRunner.committedBatches` 记录已提交批，
  `EditorInpaintController` 用 `InpaintProgressiveCommit.retainUncommitted` 收缩笔迹：清除完全落在已
  提交 tile 的笔迹，保留与未完成 tile core 相交的笔迹，避免重复涂抹。
- **单批 `OutOfMemoryError`**：`onMemoryPressure()` 清会话，按 `max(1, batch.size / 2)` 切半重试一次；
  重试仍失败则保留已完成批次，提示"第 n/N 批失败：内存不足，已完成部分已保留"。
- **`OrtException` / 其他可恢复失败**：清会话，保留已完成批次，提示"第 n/N 批失败：<原因>，已完成
  部分已保留，可重试剩余部分"，并同样收缩笔迹。
- **模型缺失**：沿用 `MODEL_MISSING` → 共享 `AiModelPromptDialog` 安装流程；已安装模型在运行中消失
  也按该路径处理。

#### 笔刷与档位

- 面板直接显示**源像素直径**，滑杆 `8..1200 px`，初始值读 `EditorSettings.inpaintBrushPx`（默认 `64`），
  另给 `小 24 / 中 64 / 大 160` 三个快捷档。
- `InpaintStroke` 仍以 `radiusNorm`（源宽比例）持久化，投影、栅格化与 `EditGraph` 契约不变；`px ↔ norm`
  只在 `InpaintBrushSize` 互转。源尺寸已知时按真实宽度换算，未知时按预览宽度近似，探测完成后自动
  重算。
- 面板增加**精细 / 快速**两档（`InpaintQualityMode`），初始值读
  `EditorSettings.inpaintDefaultMode`；切档只改变网格密度（24 MP 精细 `96/176`、快速 `48/96`），
  实时重算预估块数与秒数。
- 双指手势绝对优先、中止未完成单指笔迹、已提交笔迹保留均不变；一次"确认消除"仍是一个用户动作，
  不给用户增加"下一批"按钮。

**理由**：面积硬上限本质是"时间"约束，却伪装成"能力"约束，直接导致体验断裂。把大涂抹拆成设备
可承受的小批、每批立即提交，既保证内存上界（单 tile 裁切 ≤1024²），又让用户随时停止且不丢失已
完成成果。

**风险与缓解**：

- 批数与撤销步数增多 → 一批一个撤销步，撤销语义清晰；`EditGraph.inpaint` 本就有序，无需 schema 变更。
- 取消时笔迹只能按"整条笔迹"筛选 → 保留与未完成 tile 相交的整条笔迹，宁可少量重复推理也不留下
  未修复的洞；已完全落盘的笔迹直接清除。
- 本地实测值可能来自旧模型变体 → 测量键按 `(tier, variant)` 分开存；预估优先 pipeline、再 legacy。
- 真机耗时与桌面不同 → 首次运行后写回实测值；`>15 s` 只做提示，不阻塞。
- tile 粒度导致的 patch 数量增长沿用 ADR-0011 的 `TD-5`。

## 后果

- `EditorInpaintController` 不再把整次运行的 patch 一次性交给 brush；提交发生在控制器内部，
  `InpaintBrushController.Host` 去掉 `onInpaintPatchesReady` 与 `onInpaintBboxEstimated`。
- `InpaintBrushCanvasHost` 新增 `replaceStrokes`，用于渐进提交后的笔迹收缩。
- 面板新增工作量行与快捷尺寸行；旧的百分比显示与琥珀告警彻底移除。
- 新增 JVM 门禁：`InpaintBatchPlannerTest`、`InpaintTileEtaTest`、`InpaintTilePatchSeamTest`、
  `ProgressiveCommitTest`，并更新 `InpaintBrushControllerTest` / `InpaintBrushOverlayViewTest`。

## 验收覆盖

- `InpaintBatchPlannerTest`：预算切批、`STANDARD 96 / LOW 24`、24 MP `96/176/48/96`、与
  `InpaintCropPlanner` 的块数一致性。
- `InpaintTileEtaTest`：实测优先、保守常量、无有效估算只报块数、`>15 s` 标记。
- `InpaintTilePatchSeamTest`：两相邻/重叠 tile 顺序合成后接缝连续、未涂抹区域逐像素一致、
  重叠带 alpha 和不超一次完整写入。
- `ProgressiveCommitTest`：一批一次提交、取消保留已完成批次、OOM 清会话减半重试成功、重试仍失败
  保留已完成、`OrtException` 保留已完成、实测均值写回。
- `InpaintBrushControllerTest` / `InpaintBrushOverlayViewTest`：px 滑杆与快捷档、已知/未知源尺寸
  互转、精细/快速切档重算、`>15 s` 非阻断提示、部分失败后笔迹收缩。

## 参考

- `.scratch/ai-usability/spec.md` §2.4、§6.3、§7、§10、§11.1、§12 #3
- `docs/adr/0011-migan-pipeline-variant-and-tile-patch.md`
- `docs/research/2026-11-26-p0-ai-foundation-gate-record.md`（真机单 tile 实测 1,115–1,259 ms）
