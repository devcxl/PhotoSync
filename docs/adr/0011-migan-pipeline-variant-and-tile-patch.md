# 0011 — MI-GAN pipeline 变体接入与 tile 粒度补丁

- 状态：已采纳
- 日期：2026-11-28
- 影响范围：`:editor/vision`（`InpaintVariantDetector`、`MiganPipelineRunner`、`MiGanInpainter`、`AiModelCatalog`）、`:editor/domain`（`InpaintCropSpec`、`InpaintCropPlanner`、`InpaintTileWeight`、`InpaintTilePatch`）、`:editor/ui`（`EditorInpaintController` 的最小适配）
- 上位规范：`.scratch/ai-usability/spec.md` §6、§7.3、§12（决策 #1、#2、#6）

## 背景

P0–P4 的消除链路只认一种 MI-GAN 产物：P0 门禁里自产的 generator-only 导出
（28,026,303 B / `522048b4…`），输入 float32 `[1,4,512,512]`，输出 float32 RGB。该文件从未上传
到任何 Release，因此线上没有任何可下载的消除模型；旧实现还把"一个连通域 = 一个 patch"作为
合成单位，patch 是全部 tile 裁切的并集，内存与涂抹总面积成正比（24 MP 全覆盖时仅累加器就
约 288 MB），大面积消除必然 OOM。

上游社区实际可用的产物 `migan_pipeline_v2.onnx`（三源字节一致、MIT、28,079,181 B）已经验证：
输入 uint8 `image` + uint8 `mask`（H/W 动态），输出 uint8 `result`，无洞输入严格恒等，软掩码可
用，且内部自带裁切/缩放/回贴与掩码羽化。E1 采用它作为 v1 分发产物，同时保留旧 generator 的
识别能力。

## 关键决策

### 1. 采用上游社区导出的 `migan_pipeline_v2.onnx` 作为 v1 分发产物

- Catalog 条目 `AiModelCatalog.MI_GAN_PIPELINE`：`migan_pipeline_512_v1.onnx`、
  28,079,181 B、SHA-256
  `6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b`，镜像顺序
  `huggingface.co/andraniksargsyan/migan` → `hf-mirror.com/andraniksargsyan/migan` →
  `huggingface.co/anyisalin/migan-onnx`（第三兜底，字节一致但无许可元数据）。
- 旧 generator pin 不删除：保留为 `MI_GAN_GENERATOR_LEGACY` 的**只读**条目，没有 `mirrorUrls`，
  不出现在下载按钮上，但仍可识别本地已持有的字节。
- 变体由运行时签名探测决定（下表），目录 `variantHint` 只是提示，不参与运行判定。

| 输入集合 | 类型 | 形状 | 变体 |
|---|---|---|---|
| `{input}` | float32 | `[1,4,512,512]` | `GENERATOR_FIXED_512` |
| `{image, mask}` | uint8 | 4 维，H/W = `-1` | `PIPELINE_UINT8` |
| 其他 | — | — | 拒绝：`AiModelFailureReason.INCOMPATIBLE` |

**理由**：该文件真实存在、三源字节一致、MIT、无洞输入严格恒等、支持软掩码与动态尺寸，能让
消除功能立刻可用；等待自产 generator 重新导出与发布只会继续让功能不可用。

**风险与缓解**：依赖第三方仓库可用性 → 三源镜像 + SAF 本地导入 + `tools/verify-ai-model-catalog.sh`
发布护栏；第三方镜像无许可元数据 → 只作第三兜底，上游变动即移除（`TD-2`）；若未来自托管发布，
回填 `mirrorUrls` 并补真机金标（`TD-1`）。

### 2. Kotlin 侧保持 `255 = hole`，只在模型边界做硬掩码反相

- 领域语义不变：`InpaintMaskBuffer`、`EditGraph`、预览合成与既有测试仍以 `255 = 待修复`。
- `PIPELINE_UINT8` 的模型边界：`maskInput = if (coverage > 0) 0 else 255`（`0 = 待修复`,
  `255 = 保留`），uint8 `[1,1,H,W]`。
- **不做模型前预羽化**：把硬掩码直接交给上游 pipeline，由它内部的 `maxpool(3×3)` +
  `Gaussian(5, σ = 1)` 羽化；Kotlin 侧的高斯只用于生成 patch alpha。
- `GENERATOR_FIXED_512` 保持原契约：`m = known/255`、输入洞内 RGB 清零、float32
  `[1,4,512,512]`、输出 `clamp(out*0.5+0.5)`。

**理由**：全链路翻转会波及 `EditGraph`、预览合成、烘焙与测试断言，收益为零。双重羽化会把洞缘
修复带越推越宽（上游 pipeline 已经有自己的羽化）。

**风险与缓解**：边界反相是"最易错点"→ `MiganPipelineRunnerTest` 直接锁定反相结果、无洞恒等、
RGB 通道序与无预羽化；`InpaintVariantDetectorTest` 锁定两种签名与拒绝路径。

### 3. 补丁粒度从"每连通域一个 patch"改为"每 tile 一个 patch"

- 移除 `InpaintPlanTooLargeException` 与 patch/tile 数量硬拒；大面积涂抹变成更大的网格，由下游
  预算分批执行，永不作为拒绝理由。
- 网格参数由 `DeviceMemoryTier` + `InpaintQualityMode` 决定（`InpaintCropSpec`）：上下文 128 px、
  最低裁切 320 px、`STANDARD` 上限 1024 / `LOW` 上限 768；精细档 core 640/512、裁切 896/768、
  步进 512/384；快速档 core 768/512、裁切 1024/768、步进 768/512。24 MP 全覆盖块数 96/176
  （精细）与 48/96（快速）。
- 每个 tile 产出一个 `InpaintPatchRef`，单 patch 恒等于单 tile 裁切（≤1024²）；不再有跨 tile 的
  `IntArray`/`FloatArray` 累加缓冲，内存上界与涂抹总面积无关。
- 合成权重唯一实现 `InpaintTileWeight`：`alpha = feather(mask) × ramp`，并满足
  - `mask == 0` 处 alpha 恒 0（模型在洞外 ≤14 级的改动被拦住）；
  - `coreRect` 之外 alpha 恒 0；
  - ramp 只朝**实际重叠**的邻居启用，两侧互补；四 tile 交汇取两个 ramp 的**乘积**，权重和恒为 1，
    不会过曝；`floor` 取整保证 byte 权重和 ≤ 255。

**理由**：面积导向的粒度把内存与资产体积绑在涂抹总面积上，是大面积消除不可行的根因；tile 粒度
是常数内存，且 `EditGraph.inpaint` 本来就是有序列表，无需 schema 变更。

**风险与缓解**：patch 数量增多（24 MP 全覆盖 96/176 个，每个 ref 约 200 B JSON）；重叠带内相邻
patch 的 alpha 互补需专门用例锁定（`InpaintTileWeightTest`、后续 E2 `InpaintTilePatchSeamTest`）；
若实测 GC 或序列化成本明显，再考虑"按批合并 patch"（`TD-5`）。

## 后果

- 新装用户下载 pipeline 变体即可使用；只持有旧 generator 文件的用户仍能被探测并正常运行
  （`MiGanInpainter.resolveModel()` 先找 pipeline、再找 legacy）。
- 变体判定结果与 `(fileLength, sha256)` 缓存在模型目录旁的 `.<file>.variant.properties`；文件长度
  或期望 SHA-256 变化会强制重探测，命中缓存时不加载 native 会话、不重算哈希。
- `MiGanInpainter` 新增面向 E2 的 tile 级接口：`plan(mask)`、`resolveModel()`、
  `inpaintTiles(..., tiles, selection)`，返回每个 tile 的 patch 与单 tile 耗时。
- `InpaintPatch` 的语义（CONTEXT.md）补上"变体由签名探测决定、按单 tile 产出"。
- 真机金标仍未补测：上游 pipeline 变体的 `PIPELINE_UINT8` 执行路径需要 Redmi K20 Pro 实测各裁切
  规模的耗时与峰值 RSS；本文只记录桌面相对行为基线，见
  `docs/research/2026-11-27-migan-pipeline-variant-baseline.md`。

## 验收覆盖

- `MiganPipelineRunnerTest`：掩码反相、uint8 编码、RGB 通道序、输出解码、无洞恒等、无预羽化。
- `InpaintVariantDetectorTest`：两种签名、`INCOMPATIBLE` 拒绝、缓存命中与长度/SHA 失效。
- `InpaintCropPlannerTest`：无硬拒、24 MP 96/176/48/96、边距 128、最低 320、贴边裁剪、重叠标记。
- `InpaintTileWeightTest`：互补 ramp、四 tile 交汇不过曝、边界/无重叠不启用 ramp。
- `InpaintTilePatchTest`：core 外 alpha 恒 0、未涂抹处 alpha 恒 0、色度匹配只作用于 alpha > 0。
- `MiGanInpainterTest`：generator/pipeline 两条路径、legacy 发现、单 tile 单 patch、取消与持久化。

## 参考

- `.scratch/ai-usability/spec.md` §2.2、§2.3、§6、§7.3、§10、§12
- `docs/research/2026-11-26-p0-ai-foundation-gate-record.md` §1.3.2（旧 generator 导出）
- `docs/research/2026-11-27-migan-pipeline-variant-baseline.md`（pipeline 变体基线）
- `THIRD_PARTY_NOTICES.md`（MI-GAN 条目）
