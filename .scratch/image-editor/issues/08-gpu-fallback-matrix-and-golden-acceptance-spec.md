# GPU 降级矩阵、金标验收测试集与最终规范总装

Type: grilling
Status: open
Blocked by: 07

## Question

作为通往目的地（`.scratch/image-editor/spec.md`）的最后一张收官工单，已从迷雾区毕业。需在 6-Pass Shader 与分块渲染规范锁定后，敲定最后的健壮性降级与自动化验收契约，并将全图所有已解决工单总装为 `.scratch/image-editor/spec.md`：
1. **GPU 能力探测与分级回退矩阵**：
   - 无 `GL_EXT_color_buffer_half_float`（或 FBO 完整性检查失败）时回退 `RGBA8` + 输出三角抖动的精确策略；
   - `GL_MAX_TEXTURE_SIZE` 分档（2048 / 4096 / 8192 / 16384）对预览工作纹理与导出源图驻留档位（全幅 vs `half_size=1`）的自动裁剪上限；
   - Shader 编译/链接异常或 MediaPipe GPU Delegate 抛 `MediaPipeException` 时的回退与 `Timber.e` 诊断日志字段。
2. **自动化金标验收测试集（Golden Acceptance Suite）契约**：
   - JVM 单测（`EditValidator`、曲线 Fritsch–Carlson 单调插值、Bradford D65 恒等矩阵、`.cube` 畸形文件拒绝、`CopyMask` 过滤）；
   - Android Instrumented 测试（`MIGRATION_3_4` 数据库迁移 + 级联删除、0 参数恒等断言 $\Delta \le 1/255$、整幅 vs `2048×512` 列带分块导出逐像素差 $\le 1$、8 种 EXIF Orientation 导出方向与拍摄元数据保留断言）。
