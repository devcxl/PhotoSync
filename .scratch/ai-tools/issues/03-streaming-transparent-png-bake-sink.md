# 03: 全分辨率流式透明 PNG 烘焙导出通道（PngStripSink）

**What to build:** 扩展全分辨率列带分块烘焙导出通道，基于现有 `StripSink` 接口交付零额外原生依赖的流式透明 PNG 编码器（`PngStripSink`，基于标准库 Deflater 与 PNG 行滤波，保持 O(图像宽度) 内存占用），使 24MP–60MP 照片在启用透明去背时能够以无损带 Alpha 通道的 PNG 导出至系统相册，并沿用 API 29+ `MediaStore` `IS_PENDING` 原子提交、写入基础拍摄 `eXIf` 块；未启用透明去背的照片继续走既有 JPEG 流式导出，`BatchBake` 批量队列按每张照片的 `EditGraph` 自动分流输出 `.png` 与 `.jpg`。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `PngStripSink` 通过 JVM 单测：逐条带写入合成 RGBA8 像素后产出的字节流可被标准 PNG 解码器无错解码，且每个像素的 R/G/B/A 值与输入逐位一致（含半透明边缘）。
- [ ] 流式编码过程不分配整图尺寸像素缓冲，内存峰值保持在单行条带级别（O(宽度)），并在收到取消信号时及时中止且不留下半成品相册条目。
- [ ] 导出透明去背照片时写入系统相册 `image/png`（API 29+ 通过 `IS_PENDING` 原子公开，API 24–28 走兼容路径），并正确写入基础 `eXIf` 拍摄元数据（方向归一化为正向）。
- [ ] `BatchBake` 混合包含「透明去背」与「普通编辑」多张照片时，逐张自动分流导出 `.png` 与 `.jpg`，互不干扰且进度显示准确。
