# 手机端图片编辑器 30 项能力完整实现规范测绘地图

Labels: wayfinder:map

## Destination

锁定 `docs/research/2026-11-21-image-editor-pipeline.md` 中全部 30 项图片编辑能力（34 个原子参数）的所有前置产品语义、交互原型、外部依赖集成与渲染管线规格决策，产出一份零待决事项、可直接交接开发团队按 P0→P3 实施的完整技术规范（`.scratch/image-editor/spec.md`）。

## Notes

- **领域**：Android 端非破坏性图片编辑器（`:editor` 模块 + `:libraw` 16-bit 线性 RGBA16F 扩展 + `:app` 宿主契约注入；`minSdk 24` / `compileSdk 36`，GLES 3.0 + 自建 EGL/FBO，XML + ViewBinding，无 Jetpack Compose）。
- **必读材料与 Skill**：
  - 核心调研基线：`docs/research/2026-11-21-image-editor-pipeline.md`
  - 领域词汇表：`CONTEXT.md`
  - 每个会话推进工单时应查阅的 Skill：`grilling`、`domain-modeling`；涉及 UI/交互工单时查阅 `prototype`、`frontend-design`；涉及模块边界时查阅 `codebase-design`；涉及事实调查时查阅 `research`。
- **固定偏好**：
  - 本地图采用**纯规划模式**（只解决决策、事实调研与原型验证，最终收敛为 `.scratch/image-editor/spec.md`，不在地图推进过程中直接编写生产业务代码）。
  - 原始照片文件（RAW/JPEG）永不覆盖，所有编辑状态仅以非破坏性 `EditGraph` JSON 保存，导出始终生成新的系统相册条目并保留拍摄 EXIF（`Orientation=1`）。
  - 在所有给人类阅读的叙述与索引中，一律使用**工单标题包裹链接**引用工单，绝不使用裸编号。

## Decisions so far

<!-- 索引：每个已关闭工单一行，足以判断相关性，然后顺着链接放大看工单保存的细节 -->

## Not yet specified

<!-- 在范围内但暂时还无法成工单的迷雾；随前沿推进而毕业 -->

- **GLES 3.0 6-Pass Shader 精确 Uniform 契约与 Pre-pass 金字塔分块拼接规格**：待产品语义边界（光感公式、画幅裁切策略）与 NDK `libjpeg-turbo` 流式编码调研落定后，需细化每个 Pass 的输入/输出纹理格式、`Camera Default Look` 在 GPU 端的精确补偿参数、1024px Pre-pass 引导金字塔层级定义，以及分块导出时 `32–64px` Halo 的全局 UV 与边界裁剪公式。
- **GPU 碎片化降级矩阵、设备黑名单与金标验收测试集规格**：待渲染 Pass 与模型集成方案锁定后，需定义无 `GL_EXT_color_buffer_half_float` 设备的 `RGBA8` 抖动降级触发条件、Shader 编译失败的最小管线回退策略，以及用于自动化验收的恒等测试、ΔE2000 / SSIM 金标图与分块一致性断言规格。

## Out of scope

<!-- 被判定在目的地之外的工作；关闭，永不毕业 -->

- **云端/端侧视觉大模型（VLM）自动生成调色参数**：已在 `docs/dev/research/2026-10-27-ai-vlm-raw-tuning.md` 独立调研，不属于本次 30 项手动/端侧确定性编辑器管线规范的目的地范围。
- **在地图推进阶段直接落地 `:editor` 生产代码**：本地图专注于消除所有设计迷雾并交付完整技术规范，生产代码实现在地图完成后交接执行。
