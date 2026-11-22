# NDK libjpeg-turbo 流式编码与 ExifInterface FileDescriptor 写回技术调研

Type: research
Status: claimed

## Question

针对 `docs/research/2026-11-21-image-editor-pipeline.md` §5.4 与 §6.4 提出的分块流式导出与 EXIF 无损保留方案：
1. 在现有 `:libraw` 模块（CMake `3.22.1`、NDK `29.0.14033849`、`c++_static`、`-fopenmp`）的构建体系下，引入 `libjpeg-turbo` 用于 `jpeg_write_scanlines` 按行流式编码的最佳工程路径是什么（git submodule 源码编译 vs CMake 配置要点，如何开启 ARM NEON SIMD 加速）？
2. 当分块渲染按 Tile 产出像素（例如每块 `2048×2048`）时，`jpeg_write_scanlines` 要求按**整图水平扫描线（全宽行）**顺序喂入数据：在内存受限（峰值 ≤ 512MB）的前提下，Tile 行缓冲（Strip Buffer）应如何组织（按全宽 Strip 分块渲染 vs 同行 Tile 拼接缓冲），对应的精确内存开销是多少？
3. `androidx.exifinterface:exifinterface` 在 API 24–28（直接写 `File`）和 API 29+（`MediaStore` `IS_PENDING=1` + `ContentResolver.openFileDescriptor(uri, "rw")`）下，如何在 `libjpeg-turbo` 写完图像流后原地写回原图（含 RAW 经 LibRaw 提取的拍摄参数与 JPEG 原图 EXIF）的拍摄元数据并强制设置 `TAG_ORIENTATION = 1`？`ExifInterface.saveAttributes()` 是否会产生整文件临时拷贝，有哪些坑？
