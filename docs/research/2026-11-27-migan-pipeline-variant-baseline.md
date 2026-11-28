# MI-GAN pipeline 变体接入基线（2026-11-27）

- 状态：桌面相对行为基线已实测；真机金标**待补测**
- 日期：2026-11-27（记录上游 pipeline 变体与旧 generator 导出的关系；2026-11-28 随 E1 落库）
- 关联规范：`.scratch/ai-usability/spec.md` §2.2、§2.3、§6、§7.3、§10、§12
- 关联工单：`.scratch/ai-usability/issues/03-e1-migan-pipeline-variant.md`
- 关联 ADR：[0011 — MI-GAN pipeline 变体接入与 tile 粒度补丁](../adr/0011-migan-pipeline-variant-and-tile-patch.md)

> 本文的数值全部来自本机桌面实测（Linux x86_64 / ONNX Runtime 1.32.0 / CPU 2 线程），只用于
> 判定**相对行为**（掩码语义、恒等性、裁切半径敏感性、内存量级与线性度）。移动端耗时与峰值 RSS
> 是另一道门禁，见 §8；不把桌面毫秒数当作移动端性能结论。

---

## 1. 结论摘要

| 结论 | 依据 |
|---|---|
| 旧 generator 导出（28,026,303 B / `522048b4…`）**从未发布**，其"20 样图 bit-exact"只对那一份文件成立 | P0 门禁记录 §1.3.2；GitHub Release `ai-models-v1` 不存在 |
| 新分发产物是上游社区 pipeline 导出 `migan_pipeline_v2.onnx`，三源字节一致 | §3 三源 size/SHA 表 |
| 它接受 uint8 `image` + uint8 `mask`（H/W 动态），输出 uint8 RGB；掩码语义为 `255 = 保留, 0 = 待修复` | §3、§4 |
| 无洞输入输出与原图 **maxdiff = 0**，不会无故改图 | §4 |
| 原生支持软掩码，洞内改动随 coverage 单调变化 | §4 |
| 内存随输入边长增长，必须限制单次裁切；关 arena/pattern 后增量可控 | §5 |
| 上下文边距 ≈128 px、单次裁切 384–640 px 细节最佳；洞外仍有 ≤14 级改动 | §6 |
| 结论落到 E1：签名探测 + 硬掩码边界反相 + 128 px 上下文 + tile 粒度 patch | §7 |

---

## 2. 旧 generator 导出（仅识别，不发布）

P0 门禁在 2026-11-26 用官方 `migan_512_places2.pt`（29,553,256 B /
`1d6087eee0aac8923ad2606be5d8caeb4824d3e4de331995e420c74e124a466a`）导出了 generator-only 图：

```text
bytes        28,026,303
sha256       522048b4fc3fae7adff3af48859cd8d19412d02a8a9420614f3d4474a0901bfe
input        input   float32 [1, 4, 512, 512]
output       output  float32 [1, 3, 512, 512]
节点         798（无 NonZero/ScatterND/GatherND/Where/Range，无 MaxPool/合成算子）
```

数值门禁对 `examples/places2_512_object` 的 20 张样图做了逐字节比较：探针生成器输出与 pipeline
内部生成器输出、最终回贴图与 pipeline 结果在全帧 / 洞内 / 已知区域均为 **MAE = 0 / MaxAbsDiff = 0 /
100% 一致**。

**这份"bit-exact"不能外推到 pipeline 变体**：

1. 该文件从未上传到 Release。`gh release list` 没有 `ai-models-v1`，
   `releases/download/ai-models-v1/migan_generator_512.onnx` 返回 HTTP 404。
2. 20 样图比较的对象是 **这一份 28,026,303 B 的 generator 导出**，不是上游 pipeline 图。换成
   `migan_pipeline_v2.onnx` 后，生成器边界仍等价，但整图裁切/缩放/回贴与掩码羽化由上游图内部执行，
   Kotlin 侧不再拥有比特级一致的路径。
3. 因此 E1 保留旧文件为 `MI_GAN_GENERATOR_LEGACY` 只读条目：**识别能力**（本地导入 + 签名探测
   为 `GENERATOR_FIXED_512`）保留，下载按钮不再暴露它；若未来自托管发布，回填 `mirrorUrls`
   并补真机金标（`TD-1`）。

---

## 3. 新 pipeline 产物身份

| 源 | 大小 | SHA-256 | 许可元数据 |
|---|---|---|---|
| `huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx` | 28,079,181 B | `6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b` | `license: mit` |
| `hf-mirror.com/andraniksargsyan/migan/…`（同文件） | 同 | 同 | — |
| `huggingface.co/anyisalin/migan-onnx/resolve/main/onnx/model.onnx` | 同 | 同 | 无 `license` 字段（仅 tags） |

- Catalog 固化为 `AiModelCatalog.MI_GAN_PIPELINE`：`migan_pipeline_512_v1.onnx`、
  28,079,181 B、上述 SHA-256；`anyisalin/migan-onnx` 只作第三兜底（字节一致但无许可元数据）。
- ONNX 签名（`onnx.load`）：

  | 项 | 值 |
  |---|---|
  | 输入 | `image` `tensor(uint8)` `[batch,3,H,W]`；`mask` `tensor(uint8)` `[batch,1,H,W]`（**H/W 动态**） |
  | 输出 | `result` `tensor(uint8)` `[batch,3,H,W]` |
  | 节点数 | 1218；含 `Resize`×17、`NonZero`×2、`ScatterND`×1、`GatherND`×2、`Range`×6、`Where`×4 |
  | 支持尺寸 | 512²、256²、64²、777×513、1024²、2048×640、2048²、3072×2048、4096²、6000×4000、贴边洞 400²：全部成功 |

内部裁切/缩放/回贴的控制流无法静态折叠，这也是它必须按 **单次输入边长上限** 使用的原因（§5）。

---

## 4. 行为实测

| 用例 | 结果 |
|---|---|
| 无洞（掩码全 255） | 输出与原图 **maxdiff = 0**（严格恒等，不会无故改图） |
| 软掩码 0/64/128/192 | 洞内平均改动 39.3 / 22.3 / 13.5 / 5.5 → **原生支持半透明掩码**（自带渐变羽化） |
| 通道序 | 输入 `R,G,B = 200,40,10` → 洞外输出通道均值 `200,40,10`（**RGB，非 BGR**） |
| 耗时（桌面） | 512² → 6000×4000 全部 0.70–1.21 s（内部统一降到 512 推理，成本近常数） |

要点：

1. **恒等性是硬承诺**：无洞输入不产生任何像素改动，所以"用户没涂的地方不能变"由模型本身也不会
   被破坏；但洞外邻域仍可能有 ≤14 级改动（§6），Kotlin 侧必须用 alpha 合成兜住。
2. **掩码语义与领域相反**：Kotlin `InpaintMaskBuffer` 是 `255 = hole`，模型是 `255 = keep`。边界处必须
   反相，且**不做预羽化**，否则与模型内部的 `maxpool(3×3)+Gaussian(5,σ=1)` 形成双重羽化。
3. **通道序是 RGB**：`InpaintTilePatch` / `MiganPipelineCodec` 按 R、G、B 三个平面编码与解码，
   不得沿用 BGR 假设。

---

## 5. 内存曲线

峰值 RSS（关 `cpu-mem-arena` + `memory-pattern`）：

| 输入 | 峰值 RSS |
|---|---|
| 512² | 413 MB |
| 1024² | 414 MB |
| 1536² | 422 MB |
| 2048² | 434 MB |
| 4096² | 941 MB |
| 6000×4000 | 1,247 MB |
| 基线 | 116 MB |

默认 arena/pattern 开启时：512² 479 MB、2048² 1,080 MB、4096² 1,680 MB、6000×4000 2,095 MB。

结论：

- 内存随输入边长增长（内部要把整幅图像搬进去再降采样），**仍必须限制单次输入边长**；
- 关闭 arena/pattern 后 2048² 以内的增量可控（≈ +320 MB），因此 E1 采用
  `STANDARD maxCropSide = 1024` / `LOW maxCropSide = 768`（`InpaintCropSpec`），并把
  `OrtSessionCachePolicy.DISABLED` 保持为生产默认。

---

## 6. 裁切半径与细节保真

同一幅 4 px 棋盘测试图、128×128 洞，只改变喂给模型的裁切边长：

| 裁切 | 洞内 std（原图 110） | 洞外 maxdiff |
|---|---|---|
| 192²（边距 32） | 57 | 11 |
| 256²（边距 64） | 53 | 10 |
| 320²（边距 96） | 70 | 14 |
| **384²（边距 128）** | **81（最佳）** | 14 |
| 512²（边距 192） | 75 | 10 |
| 640²（边距 256） | 75 | — |
| 1600²（整幅） | 明显糊化 | — |

结论：

1. **上下文边距 ≈128 px、单次裁切 384–640 px 时细节最佳**（与上游 pipeline 自身默认一致）；
   裁切越大越糊，因为模型内部固定 512 推理；
2. 洞外仍会有 ≤14 级的轻微改动，**必须保留 Kotlin 侧的 alpha 合成**兜住洞外像素；
3. 贴边洞可用，不需要额外补边。

E1 落库的参数（`InpaintCropSpec`）：

| 档位 / 模式 | core | crop | 步进 | 24 MP 块数 |
|---|---|---|---|---|
| `STANDARD` 精细 | 640 | 896 | 512（重叠 128） | 96 |
| `STANDARD` 快速 | 768 | 1024 | 768（core 不重叠） | 48 |
| `LOW` 精细 | 512 | 768 | 384（重叠 128） | 176 |
| `LOW` 快速 | 512 | 768 | 512（core 不重叠） | 96 |

最低裁切 320 px（实测 192–320 已可用，避免把周边糊进来）；网格尾端的贴边裁切可能产生大于名义
步进的真实 core 重叠，`InpaintTilePlan` 记录每一侧的**实际重叠宽度**，权重函数按实际宽度互补。

---

## 7. 由基线推导出的 E1 契约

| 决策 | 来源 |
|---|---|
| 变体由 `OrtSession.getInputInfo()` 签名探测，缓存 `(fileLength, sha256)` 旁的 `.variant.properties` | §3 签名表；§1 旧文件识别 |
| `GENERATOR_FIXED_512` 保留 float32 `[1,4,512,512]` 路径；`PIPELINE_UINT8` 走 uint8 动态路径 | §3、§4 |
| Kotlin 保持 `255 = hole`；模型边界 `maskInput = if (coverage > 0) 0 else 255` | §4 掩码语义 |
| pipeline 路径不做 Kotlin 预羽化 | §4 软掩码与内部羽化 |
| 上下文 128、最低裁切 320、单块上限 1024/768 | §5、§6 |
| `alpha = feather × ramp`；未涂抹处与 core 之外恒 0；四 tile 交汇取 ramp 乘积 | §6 洞外 ≤14 级；§12 #6 |
| 单 tile = 单 `InpaintPatchRef`，内存与涂抹总面积解耦 | §5 + 24 MP 全覆盖的旧累加器量级 |

代码与测试对应：

- `InpaintVariantDetector` / `MiganPipelineCodec` / `MiganPipelineRunner` / `MiGanInpainter`
- `InpaintCropSpec` / `InpaintCropPlanner` / `InpaintTileWeight` / `InpaintTilePatch`
- `MiganPipelineRunnerTest`、`InpaintVariantDetectorTest`、`InpaintCropPlannerTest`、
  `InpaintTileWeightTest`、`InpaintTilePatchTest`、`MiGanInpainterTest`

---

## 8. 未验证项与风险

| 项 | 状态 | 补测计划 |
|---|---|---|
| `PIPELINE_UINT8` 路径真机耗时 / 峰值 RSS（各裁切规模） | **未测** | Redmi K20 Pro（`raphael`）逐档 boot + `VmHWM`，见规范 §11.2 |
| 真机 24 MP 分块消除无 OOM、单批 <15 s | **未测** | E2 预算分批 + 真机 90% 涂抹用例 |
| 真机接缝目视与洞外逐像素一致 | **未测** | E2 `InpaintTilePatchSeamTest` + 真机逐像素比对 |
| 低端 4 GB 实机 | **没有实体设备** | 只能以 LOW 档位模拟契约；不得用模拟数据冒充低端机结论 |
| 桌面 ORT 1.32.0 与应用 1.22.0 的数值差异 | 未量化 | 真机以应用实际 ORT 版本为准；桌面数据只作相对行为 |
| 旧 generator 的 20 样图 bit-exact | 仅对 28,026,303 B 文件成立 | 该文件不发布；未来自托管发布时重新补门禁 |

已知风险：

- 上游 pipeline 内部有 `NonZero/ScatterND/GatherND` 等动态控制流，内存随输入增长（§5），因此
  不允许把整幅 24 MP 图直接送入；上限由 `InpaintCropSpec.maxCropSidePx` 强制。
- 快速档名义上"core 不重叠"，但网格尾端按 bbox 贴边锚定时仍可能出现实际重叠；`InpaintTilePlan`
  已记录实际宽度，不能假设固定 128。
- `anyisalin/migan-onnx` 无许可元数据，仅作字节一致兜底；上游变动即移除（`TD-2`）。
