# 03: E1 MI-GAN pipeline 变体接入、tile 粒度分块合成与合规文档

**What to build:** 让新分发的 MI-GAN pipeline 变体在设备上真正跑起来，并让消除分块的粒度与内存脱离涂抹总面积：首次创建会话时读取模型输入签名做一次性 `InpaintVariant` 变体探测——输入为单个 float32 固定 512 四通道张量判定为旧生成器形态 `GENERATOR_FIXED_512`，输入为 uint8 的 `image` + `mask` 双张量且高宽为动态维度判定为 `PIPELINE_UINT8`，其他签名一律以 `INCOMPATIBLE` 拒绝——探测结果与文件长度、摘要一起缓存，不重复计算哈希；两种变体都保留可执行实现，用户本地导入的旧生成器文件仍可执行。新增 uint8 pipeline 执行器：在模型边界把领域内"255 代表待修复"的涂抹掩码反相为"255 代表保留"的硬掩码输入，按 uint8 单通道传入；模型前不做预羽化（羽化由模型内部自带，Kotlin 侧只用同一份羽化结果生成合成 alpha），避免洞缘修复带被双重羽化推宽；输出直接按 uint8 RGB 读取，无需反归一化，通道序为 RGB，合成阶段保留既有的高斯羽化与 8 px 边缘色度匹配以兜住洞外像素。移除消除规划器的面积与块数硬拒，改为按实测基线固定上下文边距 128 px、最低裁切 320 px、STANDARD/LOW 裁切上限 1024/768 px 以及精细/快速两档网格参数（精细档相邻核心重叠 128 px 并靠权重 ramp 衔接，快速档核心不重叠、只在上下文边距处相遇）。把消除补丁的粒度由"每连通域一个补丁"改为"每 tile 一个补丁"：每个补丁只在自己的核心矩形内写 alpha，唯一权重函数保证重叠带内相邻补丁 alpha 互补求和恒为 1、三或四块交汇处取最小值不过曝、图像边界一侧不启用 ramp、核心之外与未涂抹区 alpha 恒为 0，从而让顺序合成即为交叉渐变，内存上界只由单块裁切决定；渲染侧不得另算一套权重。同步更新第三方许可声明中的 MI-GAN 条目（新文件名、新字节数与摘要、上游仓库与 MIT 许可、如实标注由上游社区导出、第三方镜像无许可元数据故不作首选）、补充 pipeline 变体基线研究记录（旧 generator 从未发布，其逐字节一致结论只对该文件成立）、更新领域词汇并写入对应 ADR。

**Blocked by:** 01: D1 模型分发修复与发布校验

**Status:** resolved

- [x] 运行时变体探测正确区分 `PIPELINE_UINT8`、`GENERATOR_FIXED_512` 与不匹配拒绝（`INCOMPATIBLE`），探测结果按文件长度与摘要缓存，旧 generator 本地导入仍可执行。
  - 证据：`InpaintVariantDetectorTest` 覆盖三种签名判定与缓存复用；`MiGanInpainterTest` 覆盖旧 generator 本地导入路径。
- [x] uint8 pipeline 执行器单测锁定掩码反相、RGB 通道序、无洞输入恒等性与直接 uint8 输出解码，且不执行模型前预羽化。
  - 证据：`MiganPipelineRunnerTest`、`MiGanInpainterTest`（`ee74ab0` 提交信息）。
- [x] 规划器不再抛出超限硬拒异常，落位 128 px 边距、320 px 最小裁切、1024/768 裁切上限与精细/快速网格参数；唯一 tile 权重函数与单 tile patch 合成单测锁定重叠带互补求和为 1、三/四交汇不过曝、图像边界不启用 ramp、core 之外与未涂抹区 alpha 恒为 0。
  - 证据：`InpaintCropPlannerTest` 锁定边距/最小裁切/上限与两档网格；`InpaintTileWeightTest` 锁定互补求和为 1、三/四交汇取最小值、边界不启用 ramp；`InpaintTilePatchTest` 锁定 core 外与未涂抹区 alpha 恒为 0。
- [x] 完成第三方许可 MI-GAN 条目替换、pipeline 基线研究文档补充、领域词汇更新与对应 ADR。
  - 证据：`THIRD_PARTY_NOTICES.md`、`docs/research/2026-11-27-migan-pipeline-variant-baseline.md`、`CONTEXT.md`、`docs/adr/0011-migan-pipeline-variant-and-tile-patch.md` 均已落盘。

**Verification:** `:editor:testDebugUnitTest` 17 个测试类 96 tests 全绿（含 `MiganPipelineRunnerTest`、`InpaintVariantDetectorTest`、`InpaintCropPlannerTest`、`InpaintTileWeightTest`、`InpaintTilePatchTest`、`MiGanInpainterTest`），`:editor:compileDebugKotlin`、`:app:compileDebugKotlin`、`:editor:lintDebug` 均通过（提交 `ee74ab0`）。
