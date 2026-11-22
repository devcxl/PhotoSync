# 01: 抽取相册落盘与 EXIF 写回契约（Prefactoring）

**What to build:** 将主界面现有的相册导出逻辑抽取为通用的 `GallerySink` 与 `ExifCopier` 契约，并接入 `androidx.exifinterface:exifinterface`。无论是直接拷贝 JPEG、解码导出未编辑 RAW，还是后续由编辑器烘焙导出成片，都能通过同一套 API 29+（`MediaStore` `IS_PENDING=1` 写流后以可寻址 `"rw"` 模式重新打开 `FileDescriptor` 写回 EXIF）与 API 24–28（临时文件写回 EXIF 后原子重命名并登记 `MediaStore`）安全落盘，完整保留原图拍摄元数据并强制将输出文件的 `Orientation` 归一化为 `1`。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 引入 `androidx.exifinterface:exifinterface` 依赖（版本集中声明在 Version Catalog 中）。
- [ ] 提供 `GallerySink` 与 `ExifCopier`：支持从 JPEG 原图或 RAW 解码元数据（品牌、机型、拍摄时间 `localtime()` 反解、光圈、快门、ISO、焦距、镜头信息）按显式白名单复制 EXIF 标签（排除 `TAG_MAKER_NOTE`），并强制覆写 `TAG_ORIENTATION = "1"` 与实际输出宽高。
- [ ] API 29+ 路径在像素流写完并关闭 `"w"` 流后，通过 `openFileDescriptor(uri, "rw")` 执行 `ExifInterface.saveAttributes()`，成功后再将 `IS_PENDING` 置为 `0`；任一步骤抛异常时自动调用 `resolver.delete(uri)` 清理半成品。
- [ ] 主界面现有的未编辑 JPEG 与 RAW 导出切换至该契约，所有既有单元测试与 Lint 检查保持全绿。
