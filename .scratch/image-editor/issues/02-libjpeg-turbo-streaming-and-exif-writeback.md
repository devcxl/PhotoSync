# NDK libjpeg-turbo 流式编码与 ExifInterface FileDescriptor 写回技术调研

Type: research
Status: resolved

## Question

针对 `docs/research/2026-11-21-image-editor-pipeline.md` §5.4 与 §6.4 提出的分块流式导出与 EXIF 无损保留方案：
1. 在现有 `:libraw` 模块（CMake `3.22.1`、NDK `29.0.14033849`、`c++_static`、`-fopenmp`）的构建体系下，引入 `libjpeg-turbo` 用于 `jpeg_write_scanlines` 按行流式编码的最佳工程路径是什么（git submodule 源码编译 vs CMake 配置要点，如何开启 ARM NEON SIMD 加速）？
2. 当分块渲染按 Tile 产出像素（例如每块 `2048×2048`）时，`jpeg_write_scanlines` 要求按**整图水平扫描线（全宽行）**顺序喂入数据：在内存受限（峰值 ≤ 512MB）的前提下，Tile 行缓冲（Strip Buffer）应如何组织（按全宽 Strip 分块渲染 vs 同行 Tile 拼接缓冲），对应的精确内存开销是多少？
3. `androidx.exifinterface:exifinterface` 在 API 24–28（直接写 `File`）和 API 29+（`MediaStore` `IS_PENDING=1` + `ContentResolver.openFileDescriptor(uri, "rw")`）下，如何在 `libjpeg-turbo` 写完图像流后原地写回原图（含 RAW 经 LibRaw 提取的拍摄参数与 JPEG 原图 EXIF）的拍摄元数据并强制设置 `TAG_ORIENTATION = 1`？`ExifInterface.saveAttributes()` 是否会产生整文件临时拷贝，有哪些坑？

## Answer

完整一手调研（含源码行号、实测数据、精确内存表）见
[`docs/research/2026-11-22-libjpeg-turbo-streaming-exif.md`](../../../docs/research/2026-11-22-libjpeg-turbo-streaming-exif.md)。

### 1. 在 `:libraw` 下集成 libjpeg-turbo

- **`add_subdirectory()` 被上游硬拒绝**：`CMakeLists.txt:69-71` 在 `CMAKE_SOURCE_DIR != CMAKE_CURRENT_SOURCE_DIR` 时 `FATAL_ERROR`（文案里就写着 "Use ExternalProject_Add() instead"），实测照此报错。`file(GLOB)` 也不行——它依赖 `configure_file` 生成的 `jconfig.h/jconfigint.h/jversion.h` 与独立的 `simd` OBJECT 库。
- **推荐路径**：加 `third_party/libjpeg-turbo` submodule（钉 3.2.2），用 `ExternalProject_Add` 编译出 `libjpeg.a`，再以 `IMPORTED` 目标链接。实测 CMake 3.22.1 + NDK 29 + `c++_static` 双 ABI 通过，单目标构建正常，二次 `make raw_wrapper` 零重建，与 `-fopenmp -static-openmp` 共存无冲突。必须转发 `CMAKE_TOOLCHAIN_FILE/ANDROID_ABI/ANDROID_PLATFORM/ANDROID_STL/CMAKE_MAKE_PROGRAM`，声明 `BUILD_BYPRODUCTS`，并手工补 include（`${LJT_SRC}/src` + `${LJT_BIN}`，因 `INTERFACE` 头不外传）。
- **备选路径**：在 submodule 里删掉那 3 行守卫，`add_subdirectory` 即可 configure + 构建（实测双 ABI 通过）；代价是维护补丁，且该「安全」只在 `WITH_TURBOJPEG/WITH_TOOLS/WITH_TESTS` 全关时成立。
- **SIMD**：`WITH_SIMD` 默认 ON。arm64 走 Neon intrinsics 且 AArch64 基线自带 NEON，**无需任何 `-mfpu`**；armeabi-v7a 由 CMake 对 14 个 Neon 源文件逐个加 `-mfpu=neon`，并保留 `/proc/cpuinfo` 运行时探测，**同一个 `.so` 可跑无 NEON 的老 ARMv7**。不需要 `-DANDROID_ARM_MODE=arm`（3.x 已无 `.S`）。
- **宏**：`JPEG_LIB_VERSION=62`、`LIBJPEG_TURBO_VERSION=3.2.2`、`WITH_SIMD=1`、`SIMD_ARCHITECTURE=ARM64|ARM`、`BITS_IN_JSAMPLE=8`。
- **体积实测**：`libjpeg.a` arm64 6.40 MiB / v7a 4.25 MiB；但静态链接后 stripped `.so` **只增 333 KiB / 215 KiB**（只用编码器），SIMD 仅占其中 52 KiB；全量 `--whole-archive` 也就 614 KiB。加 `-Wl,--exclude-libs,ALL` 可把 45 个 `jpeg*` 导出符号清零（体积再降到 330 KiB）。

### 2. `jpeg_write_scanlines` 的行序约束与分块几何

- **契约**：一次可喂任意多行（clamp 到剩余高度），但必须**全宽、从上到下**顺序写，无随机寻址（`doc/libjpeg.txt:452-466`、`src/jcapistd.c:130-137`）。
- **编码器自身内存 O(1)**：只分配每分量 `v_samp_factor × 8` 行的条带缓冲，4:2:0 下 24/50/100MP 分别 141 / 191 / 273 KiB。
- **必须禁用多趟**：`num_scans > 1 || optimize_coding` 会触发**全图 DCT 系数虚拟数组**（`src/jcinit.c:121-129`、`src/jccoefct.c:451-467`），而默认构建无 backing store（`src/jmemnobs.c`，纯 malloc）→ 24/50/100MP 额外 68.7 / 143.1 / 291.1 MiB 且直接 OOM。即 `optimize_coding=FALSE`、不用 `jpeg_simple_progression()`、不用算术编码（文件大 5–8%）。
- **全宽 Strip 在 100MP 上不可行**：GLES 3.0 只保证 `GL_MAX_TEXTURE_SIZE ≥ 2048`，旗舰普遍 8192，而 100MP 宽 11648。结论：**「全宽 row buffer + ≤2048 宽列带（column band）」**，渲染目标固定 `2048 × (512+128)`，用 `GL_PACK_ROW_LENGTH = W` 把每条带散射进全宽行缓冲（ES 3.0 §4.3.2 Table 4.4 明确 PACK_* 作用于 `ReadPixels`），再整块喂 `jpeg_write_scanlines`。
- **精确内存（halo=64，S=512，RGBA16F 双缓冲 + RGBA8 行缓冲）**：推荐几何 **31.9 / 36.1 / 43.0 MiB**（24/50/100MP，恒定 20 MiB 渲染目标）；正方形 2048² Tile + 全宽拼接 **110.9 / 127.8 / 155.0 MiB**（2.6–3.6×，且攒满 2048 行仍要全宽缓冲，**不推荐用于流式导出**）；全宽 Strip（仅 `W ≤ maxTex`）**70.3 / 95.6 / 不可行**。
- **halo 代价**：列带 1920×512 为 1.333（h=64）/ 1.163（h=32）；正方 Tile 1.129 / 1.063；全宽 Strip 512 为 1.250 / 1.125。
- **预算提醒**：512MB 的真正大头是**源图纹理**（RGBA16F 全幅 183 / 381 / 776 MiB），分块机制已压到 43 MiB 以内。所以「导出分辨率上限」应由源图驻留方式（全幅 / `half_size` / 降采样）推导，而不是由分块机制推导。

### 3. ExifInterface 写回机制

- **`saveAttributes()` = 整文件临时拷贝 + 整文件重写**（源码注释原话 "This is expensive because it involves copying all the data from one file to another…"）。临时文件由 `File.createTempFile` 创建，而 Android 把 `java.io.tmpdir` 设为 `context.getCacheDir()`（AOSP `ActivityThread.java:7341`）→ **磁盘**占用翻倍，RAM 只有几十 KB。
- **FD 必须可 seek 且用 `"rw"`**：`isSeekableFD` 用 `Os.lseek` 试探，不可 seek 则 `saveAttributes()` 抛 `IOException`；AOSP `ContentResolver` 原文 "Opening with the 'rw' mode implies a file on disk that supports seeking"。用 `"w"` 打开再写 EXIF 是典型踩坑点；`"rwt"` 会截断图像。
- **FD 路径无 `ftruncate`**：对本场景（libjpeg 新写出、无 APP1 的新文件）安全（只变长），但不可复用到「已有更大 EXIF」的既有文件。
- **API 29+ 流程**：`insert(IS_PENDING=1)` → `openFileDescriptor(uri,"w")`/`detachFd()` 交 native 编码 → 关闭 → `openFileDescriptor(uri,"rw")` 交给 `ExifInterface(pfd.fileDescriptor)` → `saveAttributes()` → 关闭 → `update(IS_PENDING=0)`；任一步异常即 `delete(uri)`。
- **API 24–28**：写 `<name>.jpg.tmp` → `ExifInterface(tmp).saveAttributes()` → `renameTo(final)` → `MediaStore.insert`（避免半截文件；需要 `WRITE_EXTERNAL_STORAGE`）。
- **必须显式设置**：`TAG_ORIENTATION = "1"`、`TAG_IMAGE_WIDTH/LENGTH`（库不会自动改）；EXIF 载荷 > 65535 B 会抛 `IllegalStateException`，需裁剪白名单。
- **无 `getAttributes()`**（1.4.2 与 main 都没有）→ 抄原图 EXIF 只能按显式标签白名单逐项 `getAttribute`→`setAttribute`；`TAG_MAKER_NOTE` 必须排除（字符串往返必损坏），RATIONAL 会归一化（`10/3000` → `1/300`）。
- **RAW 元数据映射**：`idata.normalized_make/model`、`other.{iso_speed,shutter,aperture,focal_len,timestamp,desc,artist}`、`lens.{LensMake,Lens,LensSerial,FocalLengthIn35mmFormat}`、`shootinginfo.{ExposureProgram,MeteringMode,BodySerial}`、`other.parsed_gps`（配 `setLatLong/setAltitude`）已逐项给出。**时间必须用 `localtime()` 反解**，因为 LibRaw 是用 `mktime()`（按设备本地时区）把 `DateTimeOriginal` 字符串转成 `time_t` 的。
- **零重写优化（P1）**：自己拼 EXIF blob 后 `jpeg_write_marker(&cinfo, JPEG_APP0+1, ...)` 在第一条 scanline 之前写出（`src/jcapimin.c:244-262` 强制 `next_scanline==0`），可省掉整文件拷贝与第二次 `"rw"` 打开；代价是自实现 TIFF/IFD 编码（约 300 行）。

### 待验证（接入前必须补的实测）

- AGP 真实 Gradle 构建下 `ExternalProject` 的 Ninja/增量行为（已在独立 CMake 工程用同 NDK/CMake 复现）。
- `saveAttributes()` 在 MediaStore FUSE FD 上至少一台 API 29/30/33 真机的端到端验证。
- 100MP 全链路导出与最终源图驻留档位（依赖导出规格工单）。
