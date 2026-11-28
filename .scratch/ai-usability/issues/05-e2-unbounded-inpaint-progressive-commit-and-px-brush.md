# 05: E2 任意范围涂抹消除、预算分批、渐进提交与像素笔刷

**What to build:** 把涂抹消除从"小块勉强可用"变成任意范围可用：彻底移除 256 px 琥珀色超限告警及其相关契约方法与布局节点，不留空壳；消除面板改为直接展示源像素直径的笔刷滑杆（8–1200 px，默认 64 px，另加小/中/大快捷档）与精细/快速两档，笔刷仍以归一化半径随笔迹持久化，面板在已知源尺寸时做像素直径与归一化半径互转、未知时按预览宽度显示近似值；实时显示"预计 N 块 · 约 M 秒"，超过 15 秒只给非阻断的分批提示（"将分批处理，可随时停止"）而不阻塞确认，耗时优先取本地实测的单 tile 均值、缺失时回退按档位的保守常量、无法估算时只报块数。落地按设备档位推导的 `PaintBudget` 预算分批（STANDARD 96 / LOW 24 tile 每批）与 `ProgressiveCommit` 渐进提交：每完成一批即落盘并提交一次独立撤销事务，进度文案显示"第 n/N 批"，整次消除仍是用户的一个动作、不给额外的"下一批"按钮；用户取消、单批内存不足减半重试一次仍失败、或推理运行时抛出异常时，保留已完成批次并同步收缩未提交的笔迹，失败文案改为"第 N/M 批失败：具体原因，已完成部分已保留"。快速档核心不重叠，相邻补丁只在上下文边距处相遇、该处掩码无覆盖因而不启用权重 ramp，也不会双重写入。把消除默认档、默认笔刷直径、设备档位覆盖与实测单 tile 耗时写回统一设置，供下次运行与估算使用。最后补齐三语 README（统一设置页与任意范围消除的描述）、领域词汇与对应 ADR 收尾。

**Blocked by:** 02: S1 统一设置页基底、类型化存储与顶栏齿轮入口, 03: E1 MI-GAN pipeline 变体接入、tile 粒度分块合成与合规文档

**Status:** resolved

**Implementation:** `009f43b` feat(inpaint): add unbounded budgeted batching, progressive commit, pixel brush, and docs（合入 `feat/ai-tools`，merge commit `91502a8`）

- [x] 256 px 超限告警及相关契约/布局节点彻底移除；面板以源像素直径（8–1200 px + 小/中/大快捷档）展示并正确在已知/未知源尺寸下完成 px 与归一化半径互转。
  - 证据：`editor_inpaint_panel.xml` 不再含 limit/warning/alert 节点（grep 无命中），`strings_ai_inpaint.xml`（values / values-en）已无 256 或"超限"文案；`InpaintBrushContract.kt:33` `MAX_DIAMETER_PX = 1200`，`radiusNormForDiameter(diameterPx, sourceWidthPx)` 与 `diameterPxForRadius` 成对存在，滑杆 8–1200 默认 64 + 小 24/中 64/大 160 快捷档；单测 `InpaintBrushControllerTest.shouldApplyQuickPresetsAndClampTheRange_whenTheUserChangesTheDiameter`、`shouldConvertTheDiameterAgainstThePreviewWidth_whenTheSourceSizeIsUnknown`、`shouldRescaleTheBrush_whenTheTrueSourceSizeArrives`、`InpaintBrushOverlayViewTest.shouldKeepAPaintedStroke_whenTheBboxExceedsTheOld256PxAdvice`、`shouldEstimateAWorkloadInsteadOfAnOversizeWarning_whenTheStrokeIsWide` 全绿。
- [x] 预算分批规划单测覆盖 STANDARD/LOW 差异与精细/快速块数；相邻单 tile patch 顺序合成单测锁定接缝连续、未涂抹区逐像素恒等与无双重写入。
  - 证据：`InpaintBatchPlannerTest` 7/7 通过（`shouldCutTilesIntoOrderedBudgetSizedBatches_whenThePlanIsLongerThanTheBudget`、`shouldUseTheDeviceBatchBudget_whenThePlanCarriesItsOwnSpec`、`shouldMatchThe24MegapixelTable_whenTheWholeFrameIsPainted`）；`PaintBudget` 常量 STANDARD 96 tile/批、LOW 24 tile/批；`InpaintTilePatchSeamTest` 5/5 通过，含 `shouldCrossFadeTheOverlapBand_whenTheTwoPatchesAreAppliedInOrder`、`shouldKeepEveryUnpaintedPixelByteIdentical_whenTwoTilesAreComposed`、`shouldWriteEachCoreOnlyOnce_whenTheTilesDoNotOverlapOutsideTheirRamps`、`shouldKeepTheContextMarginUntouched_whenOnlyTheCoreHasAlpha`；快速档减半由 `InpaintBrushControllerTest.shouldHalveTheTileCount_whenTheQualityModeSwitchesToFast` 锁定。
- [x] 渐进提交单测覆盖逐批落盘与单批一撤销事务、运行中取消保留已完成批次并收缩剩余笔迹、单批 OOM 减半重试一次及失败保留已完成批次。
  - 证据：`ProgressiveCommitTest` 8/8 通过，含 `shouldCommitOncePerBatchAndReportProgress_whenTheRunSucceeds`、`shouldKeepTheCommittedBatch_whenTheRunIsCancelled`、`shouldKeepOnlyTheStrokesOverUncommittedCores_whenTheRunStopsEarly`、`shouldHalveTheBatchAndRetryOnce_whenTheFirstChunkRunsOutOfMemory`、`shouldKeepTheEarlierCommit_whenTheHalvedRetryStillRunsOutOfMemory`、`shouldKeepTheEarlierCommit_whenAnOrtExceptionHitsALaterBatch`、`shouldWriteBackTheAverageTileTime_whenABatchCommits`；进度文案 `editor_inpaint_batch_progress = 第 %1$d/%2$d 批`，无"下一批"按钮（layout/strings grep 无命中）；`InpaintProgressiveRunner` 中 `OutOfMemoryError` 只减半一次且 `chunk.size > 1` 才重试。
- [x] 耗时估算单测覆盖本地实测值优先、分档保守常量兜底及不可估算时仅报块数；消除默认档、笔刷直径与档位覆盖从统一设置生效。
  - 证据：`InpaintTileEtaTest` 7/7 通过（`shouldPreferTheMeasuredTileTime_whenThePreviousRunPersistedOne`、`shouldUseTheStandardConservativeConstant_whenNothingWasMeasured`、`shouldUseTheLowConservativeConstant_whenNothingWasMeasured`、`shouldReportTilesOnly_whenNoTileWillRun`、`shouldRoundSecondsUpAndMarkTheLongGate_whenTheTotalIsJustAbove15Seconds`）；`InpaintTileEta` 常量 `STANDARD_FALLBACK_MS_PER_TILE = 1_200`、`LOW_FALLBACK_MS_PER_TILE = 3_000`；`EditorSettings` 提供 `inpaintDefaultMode`（默认 FINE）、`inpaintBrushPx`、`resolveMemoryTier`（含档位覆盖）与 `getMeasuredInpaintTileMs`/`setMeasuredInpaintTileMs`，`EditorInpaintController` 在 295–296 行每批写回实测均值；`shouldShowTheNonBlockingBatchNote_whenTheEstimateExceeds15Seconds` 锁定 >15 s 仅给非阻断提示。
- [x] 更新三语 README（统一设置页与任意范围消除）、领域词汇与对应 ADR。
  - 证据：`README.md` / `README.en.md` / `README.ru.md` 均更新 Smart erase 与统一设置页描述（"Smart erase — MI-GAN repair for any painted area… progressive batch commits…"）；`CONTEXT.md` 新增 `PaintBudget（涂抹预算）` 与 `ProgressiveCommit（渐进提交）` 领域词条；新增 `docs/adr/0012-unbounded-inpaint-progressive-commit.md`（状态：已采纳）。

**Verification (whole-repo gate, merge commit `91502a8`):**

- `./gradlew testDebugUnitTest` — BUILD SUCCESSFUL，**1475 tests / 0 failures / 0 errors**（app 417、editor 1044、libraw 14）。
- `./gradlew assembleDebug` — BUILD SUCCESSFUL，`app/build/outputs/apk/debug/app-debug.apk` 生成。
- `./gradlew lintDebug` — BUILD SUCCESSFUL（app / editor / libraw 三模块，0 errors；warning 合计 144 条：app 68 / editor 70 / libraw 6，全部为仓库既有类型的非阻断告警。落在本工单改写文件上的 8 条为：`editor_inpaint_panel.xml` 4 条 `SmallSp`（沿用面板既有 10sp 小字排版，且与 `editor_preset_panel.xml` 等既有面板一致）、`values-en/strings_ai_inpaint.xml` 4 条 `PluralsCandidate`（`About %1$d tiles` 等，与仓库既有同类告警一致）；改 11sp 或 plurals 会改变设计/文案契约，故按存量告警保留，见下方待验证项）。
- `./gradlew :editor:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL。

**Residual warnings / real-device to-verify:**

- Lint 存量的 8 条告警（4 `SmallSp` + 4 `PluralsCandidate`）落在本工单改写的布局与英文串上，均非阻断；若产品要求归零，需单独立项改 11sp 或转 plurals。
- 真机未验证：实机 OOM 减半路径（`OutOfMemoryError` 只在低内存设备上自然触发）、实测单 tile 均值写回后的二次估算精度、1200 px 大笔刷在 24 MP RAW 上的触控与分批进度体验、取消/失败后画布笔迹收缩的视觉连续性。
