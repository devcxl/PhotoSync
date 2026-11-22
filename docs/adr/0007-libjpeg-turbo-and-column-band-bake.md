# 0007 — libjpeg-turbo 流式编码、三档源图驻留与列带流式 Bake

- 状态：已采纳
- 日期：2026-11-23
- 影响范围：`libraw/src/main/cpp/`（`CMakeLists.txt`、`jpeg_stream_writer*.{h,cpp}`、`native_rgb16.{h,cpp}`、`raw_decode_config.h`、`native_handle_registry.h`）、`libraw/src/main/java/cn/devcxl/photosync/wrapper/`（`JpegScanlineContract`、`JpegStreamWriter`、`NativeRgb16Image`）、`editor/src/main/java/cn/devcxl/photosync/editor/domain/`（`SourceResidencyPlanner`、`ColumnBandTiling`、`SourceSlicePlanner`）、`editor/.../render/`（`GeometryCaGlProgram`、`SpatialDetailGlChain`、`EffectsOutputGlProgram`、`EffectsOutputShaderSources`、`OffscreenColorTarget`）、`editor/.../export/`（`BakeExporter`、`BakeSourceTextures`、`BakePassChain`、`BakeBandEncoder`、`BakePrepass`、`BakeFrameFactory`、`StripSink`、`SurfacelessEglContext`、`ExifCopier` 及其测试）、`editor/.../ui/`（`EditorExportCoordinator`、`EditorFinishAction`、`EditorTopBarBinder`）、`app/.../activity/MainActivity.kt`、`app/.../export/GallerySink.kt`

## 背景

工单 09 要求把预览所用的 4 个物理 Pass 原样复用到 24MP–100MP 的全分辨率导出：不重写一套 CPU 管线、不整图驻留超过 `GL_MAX_TEXTURE_SIZE`、峰值内存 ≤ 512MB，并用 `libjpeg-turbo` 以 `O(1)` 编码内存流式落盘。调研（`docs/research/2026-11-22-libjpeg-turbo-streaming-exif.md`）已给出关键事实：`add_subdirectory()` 被上游硬拒绝、`optimize_coding`/渐进式会申请整幅 DCT 系数数组、`glReadPixels` 的 `GL_PACK_ROW_LENGTH` 可直接散射。落地时又锁定了几项不在调研范围内的工程决策。

## 决策

### 1. `ExternalProject_Add` + `IMPORTED libjpeg.a`，符号用 `--exclude-libs,ALL` 隐藏

上游 `CMakeLists.txt:69-71` 在非顶层构建时直接 `FATAL_ERROR`，因此 `third_party/libjpeg-turbo` 以 submodule 固定到 `3.2.2`（commit `093bc6c`），由 `ExternalProject_Add` 单独 configure 出静态 `libjpeg.a`，再通过 `IMPORTED` 目标链接进 `raw_wrapper`。

- 必须转发 `CMAKE_TOOLCHAIN_FILE / ANDROID_ABI / ANDROID_PLATFORM / ANDROID_STL / CMAKE_BUILD_TYPE / CMAKE_MAKE_PROGRAM`，并声明 `BUILD_BYPRODUCTS`，否则 Ninja 会把 `libjpeg.a` 当作未知副作用，增量构建时反复重链；
- 子目录的 `include_directories()` 不外传，`jpeg_static` 的 `INTERFACE` 需要手工补 `${LJT_ROOT}/src` 与生成头所在的 `${LJT_BUILD_DIR}`；
- `-Wl,--exclude-libs,ALL` 把 45 个 `jpeg*` 符号清零。这是安全需求而非体积洁癖：系统镜像自带 `libjpeg.so`，若两个 `.so` 都导出同名全局符号，动态链接器可能把 `jpeg_std_error` 之类绑错实现；
- SIMD 由 `WITH_SIMD=1` 打开（ARM64 基线自带 NEON，v7a 由上游逐文件加 `-mfpu=neon` 并保留运行时探测），不额外传 `-mfpu`。

### 2. 编码器锁定「单趟 baseline Huffman」

`jpeg_stream_writer.cpp` 在 `jpeg_set_defaults/quality` 之后强制 `optimize_coding = FALSE`、`arith_code = FALSE`、`dct_method = JDCT_ISLOW`，且从不调用 `jpeg_simple_progression()`。原因是 `jcinit.c:121-129` 的契约：只要 `num_scans > 1 || optimize_coding` 就会 `jinit_c_coef_controller(TRUE)`，按全图申请系数虚拟数组（24/50/100MP 分别 69/143/291 MiB），而默认构建没有 backing store，必然 OOM。

输入格式为 `JCS_EXT_RGBA`（`input_components = 4`），与 `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` 完全同构，GPU → 编码器零重打包。目标管理器自写 `jpeg_destination_mgr`：64 KiB 原生缓冲经 `SetByteArrayRegion` 拷进 Java `byte[]` 后调用 `OutputStream.write`，编码器自身内存恒定 < 300 KiB。

### 3. 行序用「反向行指针」而不是 CPU 翻转

`glReadPixels` 的第 0 行是 FBO 底行，而 `jpeg_write_scanlines` 要求从图像顶行开始。约定：所有 Pass 的 `vTexCoord.y = 0` 对应图像顶边（源纹理按 top-down 上传，`QuadMesh` 已翻转 V），于是 FBO 内容天然是「图像顶边在上」，回读得到的行缓冲是自下而上。

修正方式是把约束下沉到 `writeScanlines(..., bottomUp)`：native 侧构造 `rowPointers[i] = base + (rows - 1 - i) * stride`，**零拷贝**地让 libjpeg 按顶行优先消费。若在 CPU 上倒序或逐行 memcpy，100MP 导出每张图要多搬 ~388MB。

### 4. 三档驻留策略的判定只看「像素数 + 长边 + RAM」

`SourceResidencyPlanner` 是纯 Kotlin：

| 档位 | 条件 | 行为 |
|---|---|---|
| 1 | `pixels <= 26MP && longEdge <= min(GL_MAX_TEXTURE_SIZE, 6144) && totalRam >= 4GB` | 整图一次上传 `GL_RGBA16F` |
| 2 | `pixels <= 65MP && totalRam >= 6GB`（未落入档位 1） | 源图保留为 native `RGB16`，按列带逆映射包围盒上传子切片 |
| 3 | 其余（`> 65MP` 或 RAM 不足） | LibRaw `half_size = 1` 后再按档位 1 上传 |

档位只依赖像素数、长边与 RAM，不依赖方向：LibRaw 可能因 `user_flip` 交换宽高，但两者乘积与长边不变，所以「先探头部尺寸做计划、再解码」不会出现计划与实物不一致。

Tier 2 的 native 缓冲直接持有 LibRaw 的 `libraw_processed_image_t*`，**不做第二次拷贝**：61MP 的 `RGB16` 约 349 MiB，复制一次就会顶破 512MB 预算。切片提取在 OpenMP 循环里直接写 `RGBA16F` 半浮点，复用预览的 `halfFromFloat`。

### 5. 逆映射包围盒用 4 条边 × 4 段采样 + 4px 余量

单应变换把直线映成直线，所以列带（含 halo）矩形在源图上的像仍是凸四边形，其轴对齐包围盒由 4 个角的像唯一确定。`SourceSlicePlanner` 仍沿四条边各取 4 段（共 16 点）作为对数值误差的防御，再按 `floor/ceil` 扩 4px 双线性余量。

极端裁切（例如 11648px 宽的原图只保留 10% 再放大导出）会让一个 2048px 宽的输出列带覆盖超过 2560px 的源区域。此时 `exceedsUploadLimit = true`，`BakeBandEncoder` 退化为「上传一张长边 2560 的整图降采样纹理 + 全图切片矩形」，并记录 warning：这类组合本就不可能以原生分辨率采样完，宁可全局降采样也不产生拼缝。

### 6. 预览与 Bake 共用 shader，只新增两个默认恒等的 Uniform

- Pass A 新增 `uTileUvTransform`（列带局部 UV → Post-crop 全局 UV）与 `uSrcSliceRect`（Tier 2 子切片矩形）。预览与全幅导出分别传 `(1,1,0,0)` / `(0,0,1,1)`，数学上是恒等；
- Pass C 早已有 `uTileUvTransform`，现在由 `SpatialDetailGlChain.run(guideTransform = ...)` 真正传入列带变换；
- Pass D 新增 `uTileUvTransform` 与 `uTilePixelScale`：颗粒、晕影、三角抖动全部改用**全局 Post-crop 坐标**求值，抖动像素坐标取 `floor(globalUv * (W_crop, H_crop))`。

最后一点是零拼缝的关键：原实现用 `gl_FragCoord`，列带渲染时它是带内局部坐标，同一像素在全幅与分块导出下会拿到不同噪声；改用全局像素坐标后两条路径逐位一致，`<= 1/255` 的分块验收才有意义。颗粒还要再做一次「像素对齐」：`globalUv * (W, H)` 向上取整得到整数像素后再还原为中心 UV `(pixel + 0.5) / (W, H)`，否则分块与全幅之间约 `1e-7` 的 UV 浮点差会在噪声晶格边界上（频率 ~1000）挑选到不同格子，产生零星但可达 7/255 的差异。晕影是平滑函数，直接用 `globalUv` 即可。

### 7. 中间目标优先 `GL_RGBA16F`，不支持半精度 FBO 时自动回落至带抖动 `GL_RGBA8`

Tier 1 设备使用 `GL_RGBA16F` 中间目标可消除量化误差，满足 `<= 1/255` 的恒等与拼缝预算；在缺少 `GL_EXT_color_buffer_half_float` 的 Tier 2 设备上，`BakePassChain` 通过 `OffscreenColorTarget` 与 `SpatialDetailGlChain` 自动回落到 `GL_RGBA8` 并为 Pass A/B/C/D 每趟启用 `1/255` 三角抖动，确保中端机仍可完成全分辨率导出而不崩溃。

- 注：`MlsSimilarity` 对奇异控制点集群做了 `[0.5, 2.0]` 尺度钳位；另在列带 `BakePassChain` 中 `uSkinMaskTex` 经 `guideUv()` 采样并随工作分辨率按比例缩放美肤模糊步长。

### 8. 协程取消按列带检查，编码器作为 `Closeable` 兜底

`BakeBandEncoder.encode(isActive)` 在每个列带开始前检查 `CoroutineContext.isActive`，取消即抛 `CancellationException`。由于 GL 渲染发生在 `GallerySink.save(write = ...)` 的回调里，取消异常会先被 sink 的 `RuntimeException` 分支吞掉并删除 pending 条目，因此 `BakeExporter` 在 `save` 返回后再次检查 `isActive()` 并重新抛出取消，避免把「用户取消」伪装成「导出失败」。

`JpegStreamWriter` 实现 `Closeable`：`finish()` 成功后句柄归零，`use {}` 退出时的 `close()` 是空操作；任何异常路径都会 `jpeg_destroy_compress` 并删除 Java 全局引用。native 侧用自增句柄表而非裸指针，双击或迟到的 abort 只会得到「句柄不存在」。

### 9. EXIF 写回逻辑迁入 `:editor`

`ExifCopier` 从 `:app` 移动到 `editor/.../export/ExifCopier.kt`（同包改名），因为 `BakeExporter` 在 `:editor` 内必须能按白名单写回拍摄 EXIF，而 `:app` 依赖 `:editor`，方向不能反转。`:app` 的 `MediaStoreGallerySink` 改为导入 `AndroidExifTagAdapter`，行为不变。`BakeExifWriter` 在其上增加「RAW 元数据不可用时的几何兜底」，保证 `Orientation=1` 与裁切尺寸一定写入。

### 10. 测试在 JPEG 量化之前断言像素

JPEG q95 本身就会带来远大于 `1/255` 的逐像素误差，因此恒等与拼缝断言必须针对渲染出的 `RGBA8` 行缓冲，而不是解码后的 JPEG。生产代码把「整条带交给谁」抽象为 `StripSink`：生产实现 `JpegStripSink` 直接喂编码器，instrumented 测试实现 `CapturingStripSink` 把整幅 RGBA 拼回来做 `<= 1/255` 比较；EXIF/Orientation 断言则走完整的 `BakeExporter` + 文件版 `GallerySink`。

## 后果

- `raw_wrapper.so` 的 stripped 体积增加约 333 KiB（arm64），换来 2–4 倍编码加速与 `O(1)` 编码内存；
- `:libraw` 新增 `timber` 依赖（Kotlin 层与全仓库日志规范一致，`NativeRgb16Image` 用 Timber 记录切片越界；C++ JNI 侧沿用 `__android_log_print` `ALOGE/ALOGI` 宏）；
- bake 的 GL 上下文与预览的 `GLSurfaceView` 上下文互不干扰，但两者都在 `Dispatchers.Default` 上串行执行 GL 调用，CPU 侧仍可能出现短暂争用；
- Tier 2 极端裁切会退化为 2560px 全局降采样；这是已知取舍，日志中有 `Band ... needs an oversized slice` 可追踪；
- 超大 JPEG（长边 > `GL_MAX_TEXTURE_SIZE`）走采样解码（Tier 2 的 native 路径只服务 RAW），因此超大 JPEG 的原生分辨率导出不在本工单承诺内。

## 后续可做

- 用 `jpeg_write_marker(APP1, ...)` 在首条 scanline 前写入自拼 EXIF blob，省掉 `saveAttributes()` 的整文件拷贝（调研 §5.4 的 P1 项）；
- 若要支持「超大 JPEG 全分辨率」，可把 source provider 扩展为「按行重解码」的 JPEG 源，Tier 2 切片逻辑无需改动。
