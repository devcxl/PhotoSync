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

- [MediaPipe 人像模型体积、许可证与 GitHub APK/AAB 双渠道分发事实调研](issues/01-mediapipe-model-packaging-and-distribution.md): 模型与 AAR 均属 Apache-2.0；`tasks-core:1.2.3` native `.so` 使 arm64 APK 增 15.80 MiB（需在 Gradle 排除 x86/x86_64 与 debugSymbol 防 AAB 膨胀 32.5 MB，并加 R8 keep 规则）；GPU Delegate 要求 GLES 3.1+ 且无自动 CPU 回退，需 catch 后回退 CPU；推荐混合分发（内置 3.33 MB `face_landmarker.task` + 250 KB `selfie_segmenter.tflite`，16.37 MB FP32 `selfie_multiclass_256x256.tflite` 首进面板按需 HTTPS 下载 + SHA-256 校验，不引 `deeplab_v3`）。
- [NDK libjpeg-turbo 流式编码与 ExifInterface FileDescriptor 写回技术调研](issues/02-libjpeg-turbo-streaming-and-exif-writeback.md): 用 `ExternalProject_Add` 集成 `libjpeg-turbo` 3.2.2（stripped `.so` 仅增 330 KiB，禁 `optimize_coding`/渐进式以防 291 MiB 全图系数缓冲）；分块几何采用「≤2048 宽列带 × 512 高 + `GL_PACK_ROW_LENGTH` 散射至全宽行缓冲」（峰值渲染/行缓冲 32–43 MiB）；EXIF 走白名单拷贝并在 API 29+ 重新以 `"rw"` 打开 FD 调 `saveAttributes()`（RAW 时间戳用 `localtime()` 反解）。
- [开放参数语义与非破坏性编辑工作流边界](issues/03-parameter-semantics-and-workflow-boundaries.md): 光感采用「氛围型（Ambiance）」三段式联动清晰度/自然饱和度；降噪与颗粒分置消歧；拉直/透视/拉腿统一保持画幅比例自动内接裁切；RAW 默认启用可切换的 `camera_default` 基准观感；确立「联机暗房」闭环（保存用户预设 + 新同步照片自动套用 `LiveTetherPreset`、跨照片复制粘贴参数、主界面显示编辑后预览 + 快捷预设栏 + 长按对比原图、多张后台队列烘焙导出、编辑态来新图静默入库不抢焦点）。
- [单屏 XML/ViewBinding 编辑器交互与手势冲突原型](issues/04-editor-ui-layout-and-gesture-prototype.md): 采用 A+B+C 融合布局（`EditorActivity` 顶栏 + 中央 GLES 画布 + 底栏三层抽屉配零点磁吸刻度滑杆，横屏移至右侧 `320dp`；`MainActivity` 胶片条上方增设快捷预设栏、`LiveTetherPreset` 开关与复制/粘贴按钮）；双指在一切工具下强制优先缩放画布并中止未移动单指液化笔防误涂；拖动期跑 1024px 代理 + 300ms 撤销事务合并，松手 150ms 后补全全清空间细节渲染（原型见 [`prototype-editor-ui-and-state.html`](prototype-editor-ui-and-state.html)）。

## Not yet specified

<!-- 在范围内但暂时还无法成工单的迷雾；随前沿推进而毕业 -->

- **GPU 碎片化降级矩阵、设备黑名单与金标验收测试集规格**：待渲染 Pass 与模型集成方案锁定后，需定义无 `GL_EXT_color_buffer_half_float` 设备的 `RGBA8` 抖动降级触发条件、Shader 编译失败的最小管线回退策略，以及用于自动化验收的恒等测试、ΔE2000 / SSIM 金标图与分块一致性断言规格。

## Out of scope

<!-- 被判定在目的地之外的工作；关闭，永不毕业 -->

- **云端/端侧视觉大模型（VLM）自动生成调色参数**：已在 `docs/dev/research/2026-10-27-ai-vlm-raw-tuning.md` 独立调研，不属于本次 30 项手动/端侧确定性编辑器管线规范的目的地范围。
- **在地图推进阶段直接落地 `:editor` 生产代码**：本地图专注于消除所有设计迷雾并交付完整技术规范，生产代码实现在地图完成后交接执行。
