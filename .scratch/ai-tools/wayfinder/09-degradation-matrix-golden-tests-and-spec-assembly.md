# 全分辨率烘焙与端侧 AI 降级矩阵、金标验收基线及最终技术规范总装

Type: grilling
Status: resolved
Assignee: devcxl/wayfinder-session
Blocked by:
- [端侧 AI 底座、多镜像按需分发与低内存预算规范](04-ai-runtime-distribution-and-memory-budget-spec.md)
- [非破坏性 AI 资产层、EditGraph 扩展与渲染/导出管线挂载点规范](05-non-destructive-ai-assets-and-editgraph-v2-spec.md)
- [AI 工具面板交互、涂抹消除画笔与模型下载/管理原型](06-ai-tools-editor-ui-and-interaction-prototype.md)
- [P4 云端生成式能力（BYOK / ComfyUI）接口契约、安全存储与隐私边界规范](07-byok-cloud-provider-contract-and-privacy-boundary.md)
- [P3 换天空光影融合、前景色彩协调与天空素材分发规范](08-sky-replacement-relighting-and-asset-pack-spec.md)

## Question

全部 P0–P4 架构、模型、管线、交互原型与安全边界均已锁定。作为本 Wayfinder 地图的收官工单，需要敲定最后两项交付门禁并完成总装规范 `.scratch/ai-tools/spec.md`（同步检查 `CONTEXT.md` 新增术语清单）：

1. **端侧 AI + 全分辨率烘焙分级降级矩阵**：针对不同内存档位（≤4 GB 低端机 / >4 GB 标准机）与 GPU 能力档位（沿用现有 Tier 1 RGBA16F / Tier 2 RGBA8 / Tier 3 最小 Shader），锁定 MODNet、MI-GAN、分块 `GuidedFilter`、FMGE 去色溢与 `PngStripSink` 在 OOM、缺模型、半浮点不支持或超时时的确定性安全降级路径。
2. **分阶段（P0→P4）交付切分与金标验收门禁**：定义 JVM 纯单测、Instrumented 真机回归测试（内存峰值 RSS、延迟红线、视觉金标、隐私 EXIF 零残留校验、BYOK 禁公网明文回归）以及向开发团队交接的最终实施清单，并总装产出 `.scratch/ai-tools/spec.md`。

## Answer

已完成两档内存（≤4 GB / >4 GB）× 三档 GPU（Tier 1–3）安全降级矩阵、P0→P4 五波次交付切分与 JVM + 真机金标验收门禁的最终锁定，并总装产出完整技术规范 [`.scratch/ai-tools/spec.md`](../spec.md)。

核心门禁摘要：
- **P0 准入门禁**：须先从官方 `.pt` 导出 MI-GAN 生成器-only 512 ONNX 并固化 SHA-256，在标准机与 ≤4 GB 低端机上完成 MODNet / MI-GAN 真实延迟与峰值 RSS 摸底，再推进 P1。
- **低端机确定性降级**：≤4 GB 设备推理后立即 `close()` 释放 `OrtSession`、烘焙期 OOM 自动退回双线性上采样 + 边缘羽化保导出不中断、`LiveTetherPreset` 静默降级不阻塞相机 USB 同步。
- **自动化回归门禁**：覆盖 `EditGraph` v1→v2 兼容与单图像素资产隔离、多镜像与 SAF 校验、`PngStripSink` 无损流式导出、外发图片零 EXIF/零缩略图验证、公网明文 HTTP 与跨源重定向拦截，以及 R8 Release APK 遥测 provider 移除检查。
