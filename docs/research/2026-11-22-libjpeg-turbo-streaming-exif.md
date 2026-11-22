# NDK libjpeg-turbo 流式编码与 ExifInterface FileDescriptor 写回

**日期**: 2026-11-22
**状态**: 调研（对应工单 `.scratch/image-editor/issues/02-libjpeg-turbo-streaming-and-exif-writeback.md`）
**范围**: `:libraw` 原生构建体系下的 `libjpeg-turbo` 集成、`jpeg_write_scanlines` 的分块几何与内存契约、`androidx.exifinterface` 在 API 24–28 / 29+ 两条落盘路径上的写回机制与 RAW 元数据映射。不设计 UI、不落地生产代码。

> 引用约定：外部来源一律给「文件:行号」或条款号，且以**上游仓库源码**为准（libjpeg-turbo 3.2.2 `main` @ `093bc6c`，androidx `ExifInterface` 1.4.2 发布源码，AOSP `main`，OpenGL ES 3.0.6 规范）。仓库内引用统一 `path:line`。二手来源不参与结论推导，仅在文末单列。
> 实测环境：NDK `29.0.14033849`、AGP 侧 CMake `3.22.1`（`~/Android/Sdk/cmake/3.22.1`）、宿主 CMake 4.4.4、Linux x86_64。

---

## 0. 结论摘要

1. **`add_subdirectory()` 走不通，这是上游的硬拒绝**。libjpeg-turbo 顶层 `CMakeLists.txt:69-71` 在 `CMAKE_SOURCE_DIR != CMAKE_CURRENT_SOURCE_DIR` 时直接 `FATAL_ERROR`（错误文案还带一句 "Use ExternalProject_Add() instead"）。实测报错：`The raw_wrapper build system cannot be integrated into another build system using add_subdirectory().`。因此「像 LibRaw/lcms2 那样 `file(GLOB)` 或 `add_subdirectory`」两条既有习惯路径里，只有前者需要伪造生成头文件，后者被禁止。

2. **推荐路径 = `ExternalProject_Add` + `IMPORTED` 静态库**（实测通过：CMake 3.22.1 + NDK 29 + `c++_static`，arm64-v8a / armeabi-v7a 双 ABI，单目标构建、二次调用零重建）。备选路径 = 在 submodule 里删掉那 3 行守卫（实测同样可 configure + 构建，CMake 图更干净，但要维护补丁）。

3. **SIMD 是默认开的，不需要我们做任何事**。`WITH_SIMD` 默认 `TRUE`（`CMakeLists.txt:277`）。arm64 走 Neon intrinsics 且 AArch64 的 NEON 是基线，**不加任何 `-mfpu` 标志**；armeabi-v7a 对 14 个 Neon 源文件逐个加 `-mfpu=neon`（`simd/CMakeLists.txt:352-359`），并保留 `/proc/cpuinfo` 运行时探测（`simd/CMakeLists.txt:259-268`），所以**同一个 `.so` 能跑在无 NEON 的老 ARMv7 上**。

4. **二进制体积实测**（Release，`-Wl,-s`，只看编码器可达符号）：`libjpeg.a` arm64 6.40 MiB / v7a 4.25 MiB；但静态链接后 **stripped `.so` 只增长 333 KiB（arm64）/ 215 KiB（v7a）**，因为链接器只拉进 encoder 路径。SIMD 本身只占其中 **52 KiB**（arm64：281 KiB → 333 KiB）。全量 `--whole-archive` 也只有 614 KiB。相比现有 `libraw_wrapper.so` 的 2.6 MiB（arm64）/ 2.0 MiB（v7a），增量约 +13%。

5. **`jpeg_write_scanlines` 的编码器侧内存与图像高度无关**：主控制器只分配「每个分量 `v_samp_factor × DCTSIZE` 行」的条带缓冲（`src/jcmainct.c:136-190`），4:2:0 下 24MP/50MP/100MP 分别是 **141 / 191 / 273 KiB**。真正的雷在别处 ↓

6. **`optimize_coding` / 渐进式 / 算术编码会触发「全图 DCT 系数缓冲」**：`src/jcinit.c:121-129` 的契约是 `num_scans > 1 || optimize_coding` → `jinit_c_coef_controller(cinfo, TRUE)` → 给每个分量申请**整幅虚拟数组**（`src/jccoefct.c:451-467`），而默认构建**没有 backing store**（`src/jmemnobs.c:9-15`，纯 `malloc`）。按 4:2:0、8-bit、每 MCU 768 B 计算：24MP **68.7 MiB**、50MP **143.1 MiB**、100MP **291.1 MiB** 的额外常驻内存，且没有降级路径，直接 `JERR_OUT_OF_MEMORY`。**必须保持 `optimize_coding = FALSE`、`num_scans = 1`**（即 baseline sequential Huffman），代价是文件大 5–8%。

7. **全宽 Strip 在 100MP 上物理不可行**：GLES 3.0 只保证 `GL_MAX_TEXTURE_SIZE ≥ 2048`（规范 §6.1 / `glGet` 参考页原文 "must be at least 2048"），主流旗舰 8192，而 100MP 宽 11648。所以「全宽水平 Strip（`W × 512`）vs 正方形 Tile + 拼接」这个二选一本身就是伪命题：**正确解是「全宽 row buffer + ≤2048 宽列带（column band）」**——渲染目标永远是 `2048 × (512+2×halo)`，CPU 侧用 `GL_PACK_ROW_LENGTH = W` 把每条带散射进全宽行缓冲，再整块喂给 `jpeg_write_scanlines`。

8. **三种几何的精确内存**（RGBA16F 双缓冲 + CPU 行缓冲，halo 64）：推荐几何 **31.9 / 36.1 / 43.0 MiB**（24/50/100MP）；正方形 2048² Tile + 全宽拼接 **110.9 / 127.8 / 155.0 MiB**（2.6–3.6 倍）；全宽 Strip（仅当 `W ≤ maxTex`）**70.3 / 95.6 / 不可行**。导出峰值预算 512MB 的真正大头不是这套机制，而是**源图纹理本身**（24/50/100MP 的 RGBA16F 分别是 183 / 381 / 776 MiB）——这一项必须由导出规格另行定档。

9. **`ExifInterface.saveAttributes()` 是「整文件临时拷贝 + 整文件重写」**，不是原地修补。源码注释原文："This is expensive because it involves copying all the data from one file to another and deleting the old file and renaming the other."（`ExifInterface.java:4864-4866`）；实现是 `File.createTempFile("temp","tmp")`（`:4908`）→ 全量 copy → 从临时文件重写到目标。而 `java.io.tmpdir` 在 Android 上被设为 **`context.getCacheDir()`**（AOSP `ActivityThread.java:7341`），即临时拷贝落在应用私有 cache，**磁盘**占用翻倍（RAM 只有几十 KB）。

10. **FileDescriptor 路径必须可 seek 且以 `"rw"` 打开**：构造器用 `Os.lseek(fd,0,SEEK_CUR)` 试探（`ExifInterface.java:4839`），不可 seek 则 `mSeekableFileDescriptor = null`，`saveAttributes()` 抛 `IOException("ExifInterface does not support saving attributes for the current input.")`。AOSP `ContentResolver` 的原文也点明："Opening with the 'rw' mode implies a file on disk that supports seeking."（`ContentResolver.java` `openFileDescriptor` 三参重载注释）。

11. **FD 路径没有 `ftruncate`**，源码全文无 `truncate` 调用；`File` 路径靠 `FileOutputStream` 的 `O_TRUNC` 兜住。对「libjpeg 新写出的、本来没有 APP1 的文件」这一场景是安全的（只会变长），但**绝不能用 `"rwt"`**（会截断刚写好的图像）；若将来对「已带更大 EXIF 的既有文件」复用该 API，必须自己 `Os.ftruncate(fd, newLength)`，否则尾部会残留旧字节。

12. **EXIF 载荷硬上限 65535 字节**：`writeExifSegment` 在 `totalSize > 0xFFFF` 时抛 `IllegalStateException("Size of exif data ... exceeds the max size of a JPEG APP1 segment (65536 bytes)")`（`ExifInterface.java:7804-7808`）。带大 MakerNote 的原图必须裁剪标签白名单，不能盲抄。

13. **零重写替代方案存在**：把 EXIF blob 自己拼好后用 `jpeg_write_marker(&cinfo, JPEG_APP0+1, ...)` **在第一次 `jpeg_write_scanlines` 之前**写入（`src/jcapimin.c:244-262` 要求 `next_scanline == 0`），可完全省掉第 9 条的整文件拷贝；代价是自己实现 TIFF/IFD 编码（约 300 行）。这是唯一能把「EXIF 写回」从导出关键路径上摘掉的方案，列为 P1 优化项而非 P0 必需。

14. **RAW 元数据可完整映射**，最容易踩的语义陷阱是时间：LibRaw 把 EXIF 的 `DateTimeOriginal` 字符串用 **`mktime()`** 转成 `time_t`（`src/metadata/misc_parsers.cpp:557-577`，`t.tm_isdst = -1`），即**按设备本地时区解释相机墙钟时间**。写回时必须用 `localtime()` 反解，用 `gmtime()` 会凭空平移 UTC 偏移量。

15. **androidx.exifinterface 没有 `getAttributes()`**（1.4.2 与 `androidx-main` 都没有该公开方法，只有 `getAttribute/getAttributeInt/getAttributeDouble/getAttributeBytes/hasAttribute`）。「抄原图 EXIF」只能按**显式标签白名单**逐项 `getAttribute` → `setAttribute`；类型由标签字典决定，与字符串形式无关，但 RATIONAL 的分子分母精度会按规范归一化（如 `10/3000` → `1/300`）。

---

## 1. 仓库基线（调研起点）

| 事实 | 位置 | 数值 |
|---|---|---|
| 模块 | `libraw/build.gradle:6-31` | `:libraw`，`minSdk 24`，`compileSdk 36` |
| ABI | `libraw/build.gradle:13-15` | `arm64-v8a`、`armeabi-v7a` |
| STL | `libraw/build.gradle:21` | `-DANDROID_STL=c++_static`（为了避开与 OpenCV 的 `libc++_shared.so` 冲突） |
| CMake | `libraw/build.gradle:26-31` | `version = "3.22.1"`；`CMakeLists.txt:1` `cmake_minimum_required(VERSION 3.22.1)` |
| 语言标准 | `CMakeLists.txt:4-7` | C++17 / C11，`CMAKE_POSITION_INDEPENDENT_CODE ON` |
| 现有第三方 | `.gitmodules` | `third_party/LibRaw`、`third_party/Little-CMS`，均源码编译 |
| 现有产物 | `libraw/build/intermediates/stripped_native_libs/...` | `libraw_wrapper.so`：arm64 **2.6 MiB**、v7a **2.0 MiB**（stripped） |
| OpenMP | `CMakeLists.txt:41,55-56` | `-fopenmp` + `-Werror` 无关；链接 `-static-openmp` |
| 导出落盘 | `MainActivity.kt:1225-1273` | API 29+ `MediaStore` + `IS_PENDING=1`；API ≤28 直接 `FileOutputStream` + `MediaStore.insert` |
| 导出流 | `MainActivity.kt:1196-1209` | 只有单向 `OutputStream`，没有 FD 通道，也没有 `exifinterface` 依赖 |

一个容易被忽略的点：`CMakeLists.txt:13` 的 `file(GLOB ...)` 风格对 LibRaw/lcms2 成立，但**对 libjpeg-turbo 不成立**——它需要 `configure_file` 生成 `jconfig.h / jconfigint.h / jversion.h`（`CMakeLists.txt:622,707-708`）以及 `simd/` 下的 `neon-compat.h`（`simd/CMakeLists.txt:349`），还有一个独立的 `simd` OBJECT 库。手工 GLOB 会在「宏定义缺失」和「SIMD 目标漏链」上反复踩坑，不建议。

---

## 2. 集成 libjpeg-turbo

### 2.1 上游立场：`add_subdirectory()` 被硬拒绝（实测）

`CMakeLists.txt:44-71` 是一段完整的「我们永远不会支持被 add_subdirectory 集成」声明，末尾 3 行是执行体：

```cmake
# CMakeLists.txt:69-71
if(NOT CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  message(FATAL_ERROR "The ${CMAKE_PROJECT_NAME} build system cannot be integrated into another build system using add_subdirectory().  Use ExternalProject_Add() instead.")
endif()
```

实测（把源码软链到 `third_party/libjpeg-turbo` 后 configure）：

```
CMake Error at third_party/libjpeg-turbo/CMakeLists.txt:70 (message):
  The raw_wrapper build system cannot be integrated into another build system
  using add_subdirectory().  Use ExternalProject_Add() instead.
```

注意文案里的 `raw_wrapper` 是**父工程名**（`CMAKE_PROJECT_NAME` 始终指向最顶层 `project()`），这也顺带说明：在子目录里它连自己的名字都拿不准，上游列出的 6 条改造理由（`CMakeLists.txt:47-61`：避免 `CMAKE_SOURCE_DIR`/`CMAKE_BINARY_DIR`/`CMAKE_PROJECT_NAME`、避免相对路径与隐式 include、install 开关、target 前缀、`PROJECT_VERSION`、回归测试）是认真的。

上游给的两条出路写在 `CMakeLists.txt:63-67`：**用 `ExternalProject_Add()`，或者自己打下游补丁**（"or make downstream modifications to the libjpeg-turbo build system to suit their specific needs. Please do not file bug reports..."）。`BUILDING.md:86-87` 同样一句话。

### 2.2 三条路径的实测对比

| 维度 | A. 预编译 `.a` + `IMPORTED` | B. **`ExternalProject_Add`（推荐）** | C. 打补丁后 `add_subdirectory` |
|---|---|---|---|
| 是否改上游源码 | 否 | 否 | 是（删 3 行守卫） |
| submodule 升级成本 | 重新生成二进制 | `git submodule update` 即可 | 补丁需重放，可能 conflict |
| CMake 图 | 最简单（只有 `IMPORTED`） | 多一层 nested configure/build | 最干净（`jpeg-static` 直接可 link） |
| 首次构建耗时 | 0 | +3.6 s wall / +16 s CPU（arm64 实测） | 同 B（合并进主图） |
| 增量构建 | 0 | 靠 stamp，实测第二次 `make raw_wrapper` 零重建 | 0 |
| 仓库一致性 | ✗ 引入 6.4+4.25 MiB 二进制、且与 NDK 版本强耦合 | ✓ 与 LibRaw/lcms2 同为源码编译 | ✓ 同左 |
| 需要手工处理 include 目录 | 是 | 是 | 是（子目录的 `include_directories` 不外传） |
| IDE 断点/索引 | 无 | 弱 | 好 |

**A 被否**：11 MiB 二进制入库违背本仓库「第三方一律源码 submodule」的既有约定，且 `.a` 与 clang 版本、`-DANDROID_PLATFORM` 强耦合，NDK 一升级就得全量重生成。

**C 的补丁只有 3 行**（实测最小补丁：只删守卫，不需要动 `${CMAKE_SOURCE_DIR}`）：

```diff
-if(NOT CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
-  message(FATAL_ERROR "The ${CMAKE_PROJECT_NAME} build system cannot be integrated into another build system using add_subdirectory().  Use ExternalProject_Add() instead.")
-endif()
+# downstream patch: allow add_subdirectory() integration (minimal config only)
```

实测结论：只删守卫时，arm64-v8a 与 armeabi-v7a **都能 configure 通过并成功链接**，因为 `${CMAKE_SOURCE_DIR}` 的其余引用（`CMakeLists.txt:520,641,759,798,856,966` 与测试段 `1923+`）都落在 `WITH_TURBOJPEG / WITH_TOOLS / WITH_TESTS` 关闭后不执行的代码里。**但这是「关掉一半功能才安全」的巧合**，一旦有人打开 `WITH_TESTS` 就会踩到 `check_c_source_compiles(${CMAKE_SOURCE_DIR}/cmakescripts/right_shift_is_unsigned.c)` 路径错乱。所以 C 只作为「不想要 nested build 时的可选项」，并必须在补丁里写清这个前提。

**B 为推荐路径**：不碰上游、submodule 直接跟上游 main、升级零冲突；代价是多一层构建图与一段样板 CMake。

### 2.3 推荐方案 B 的完整 CMake 片段（实测可编译可链接）

```cmake
# libraw/src/main/cpp/CMakeLists.txt 追加
include(ExternalProject)

set(LJT_SRC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo)   # submodule
set(LJT_BIN ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-build)
set(LJT_LIB ${LJT_BIN}/libjpeg.a)                                    # 单配置生成器

ExternalProject_Add(libjpeg_turbo_ep
  SOURCE_DIR  ${LJT_SRC}
  BINARY_DIR  ${LJT_BIN}
  CMAKE_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DANDROID_STL=${ANDROID_STL}
    -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}     # AGP 用 Ninja，必须转发
    -DENABLE_SHARED=0 -DENABLE_STATIC=1
    -DWITH_TURBOJPEG=0 -DWITH_TOOLS=0 -DWITH_TESTS=0
    -DWITH_SIMD=1
  BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target jpeg-static
  BUILD_BYPRODUCTS ${LJT_LIB}
  INSTALL_COMMAND ""
)

add_library(jpeg_static STATIC IMPORTED GLOBAL)
set_target_properties(jpeg_static PROPERTIES IMPORTED_LOCATION ${LJT_LIB})
add_dependencies(jpeg_static libjpeg_turbo_ep)
# 子目录的 include_directories 不会外传，必须手工补：公共头在 src/，生成头在 BINARY_DIR
target_include_directories(jpeg_static INTERFACE ${LJT_SRC}/src ${LJT_BIN})

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE jpeg_static)
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE -Wl,--exclude-libs,ALL)
```

实测记录（CMake 3.22.1 + NDK 29 + `-DANDROID_STL=c++_static`）：

- configure 0.2 s；`make raw_wrapper -j8` 3.6 s（其中 libjpeg-turbo 编译 16 s CPU），产物 `libraw_wrapper.so` 正常链接；
- 第二次 `make raw_wrapper` 输出 `[80%] Built target libjpeg_turbo_ep` + `[100%] Built target raw_wrapper`，**不重建**；
- 与 `-fopenmp -static-openmp` 共存无冲突（额外验证：`-O3 -fopenmp` 编译 + `-fopenmp -static-openmp` 链接，成功）。

三个必须注意的实现细节：

1. **`BUILD_BYPRODUCTS` 必须写**，否则 Ninja 认为 `libjpeg.a` 是「未知副作用」，增量构建会报 missing 或重复链接。
2. **`CMAKE_MAKE_PROGRAM` 必须转发**。AGP 用 Ninja 且不保证 `ninja` 在 `PATH` 上；ExternalProject 会自动继承父工程的 `CMAKE_GENERATOR`，但生成器程序路径要显式给。
3. **`INSTALL_COMMAND ""`**：跳过 install，否则会在 `<BINARY_DIR>` 下铺 `include/`、`lib/`，还会让 `install()` 规则挂进自己的 install 目标。

### 2.4 编译宏与 SIMD 的实际形态

libjpeg-turbo 3.2.2 的公共配置头（`jconfig.h`）实测生成结果：

```c
#define JPEG_LIB_VERSION          62          /* 默认 v6b ABI（WITH_JPEG7/8=0） */
#define LIBJPEG_TURBO_VERSION     3.2.2
#define LIBJPEG_TURBO_VERSION_NUMBER 3002002
#define C_ARITH_CODING_SUPPORTED  1
#define D_ARITH_CODING_SUPPORTED  1
#define MEM_SRCDST_SUPPORTED      1
#define WITH_SIMD                 1
#define BITS_IN_JSAMPLE           8           /* 3.x 起精度运行时可选中，8 仍是默认 API */
```

内部头 `jconfigint.h` 另有 `#define SIMD_ARCHITECTURE ARM64`（64 位）/ `ARM`（32 位）。

SIMD 开关行为（`simd/CMakeLists.txt`）：

| ABI | CPU_TYPE 判定 | NEON 处理 | 需要额外标志？ |
|---|---|---|---|
| arm64-v8a | `CMAKE_SYSTEM_PROCESSOR = aarch64` → `arm64`（`CMakeLists.txt:126-131`） | `arm/aarch64/*` + `arm/*.c` intrinsics，NEON 是 AArch64 基线 | **不需要**，实测编译命令里没有任何 `-mfpu` |
| armeabi-v7a | `armv7-a` → `arm` | 14 个源文件逐个 `set_source_files_properties(... COMPILE_FLAGS "-mfpu=neon")`（`:352-359`） | 由 CMake 自动加；实测 `flags.make` 里 14 处 `-mfpu=neon` |

关键结论：**armeabi-v7a 不需要为「老设备没有 NEON」单独出一个 flavor**。32 位构建会编译「C 参考实现 + Neon intrinsics」两套并带 `jsimdcpu.c` 运行时探测（`simd/CMakeLists.txt:259-268` 的注释解释了为什么 32 位要用 `-mfloat-abi=softfp` 编 intrinsics 而标量部分保持默认）。`REQUIRE_SIMD`（`CMakeLists.txt:248`）保持默认 `FALSE`，否则一旦某天上游删掉某架构的 SIMD 会直接 configure 失败。

一个容易误判的点：**不需要 `-DANDROID_ARM_MODE=arm`**。`BUILDING.md:454,481` 的配方里带着它，但那是给老版本（含 `.S` 汇编）准备的；3.x 的 ARM 路径已经是纯 intrinsics，实测 `simd/arm/` 下没有任何 `.S` 文件（只有 `.c` 与 `neon-compat.h.in`）。NDK 29 默认 `thumb` 完全没问题。

### 2.5 体积实测

构建配置：`-DCMAKE_BUILD_TYPE=Release`、`ENABLE_SHARED=0`、`WITH_{TURBOJPEG,TOOLS,TESTS}=0`、`WITH_SIMD=1`，NDK 29，`-DANDROID_PLATFORM=android-24`。

| 度量 | arm64-v8a | armeabi-v7a |
|---|---|---|
| `libjpeg.a` 归档大小 | 6.40 MiB（6,712,010 B） | 4.25 MiB（4,454,694 B） |
| 空壳 `.so` 基线（`-Wl,-s`） | 3,784 B | 2,680 B |
| 含**仅编码器**可达代码的 stripped `.so` | 344,944 B → **增量 333 KiB** | 223,192 B → **增量 215 KiB** |
| 同上但 `WITH_SIMD=0` | 291,232 B → 增量 281 KiB | —（未测） |
| `--whole-archive` 全量 stripped `.so` | 632,392 B → 增量 614 KiB | — |
| 对照：现有 `libraw_wrapper.so` | 2.6 MiB | 2.0 MiB |

读法：**`libjpeg.a` 的 6.4 MiB 是误导性数字**，静态链接只拉进被引用的成员（`.o`），本项目只用编码器，最终增量 ≈ 333 KiB（arm64）。SIMD 让 `.so` 多 52 KiB，换 DCT/色彩转换/下采样的 2–4 倍加速，没有不开启的理由。

> 若将来需要 JPEG **解码**或 `jpegtran` 式无损旋转，增量会涨到 600 KiB 量级（全量）。当前使用 `BitmapFactory` 解码，不需要。

### 2.6 符号可见性与 `--exclude-libs`

默认链接会**导出 45 个 `jpeg*`/`jinit_*` 符号**（实测 `llvm-nm -D --defined-only` 输出 `jpeg_*`、`j12init_*`、`j16init_*`、`jcopy_sample_rows` 等全为 `T`）。这在 Android 上不是纯洁癖问题：系统镜像里本来就有 `libjpeg.so`，若同进程另一个 `.so` 也静态链了 libjpeg，动态链接器可能把两边的 `jpeg_*` 绑到一起（同名全局符号先到先得），产生「谁的 `jpeg_std_error` 版本」这类偶发崩溃。

修复一行（实测有效）：

```cmake
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE -Wl,--exclude-libs,ALL)
```

效果：stripped `.so` 344,944 → 337,680 B，导出符号只剩业务 JNI 入口 1 个。副作用是 `--exclude-libs,ALL` 会同时隐藏其他静态库（LibRaw/lcms2）的符号——本模块本来就只该导出 `Java_cn_devcxl_photosync_wrapper_*`，这正是想要的行为；若将来需要导出别的 C API，改成 `-Wl,--exclude-libs,libjpeg.a`。

### 2.7 许可与版本

- 许可：IJG/BSD 三条款系（`LICENSE.md`），可商用、可静态链接；分发二进制时需在关于页/第三方声明里保留版权与免责声明。
- 版本锚点：本次核实 `main` 为 **3.2.2**（`CMakeLists.txt:17`，`git log -1` = `093bc6c` 2026-11-20）。
- submodule 建议钉到发布 tag（`3.2.2`）而非 `main`，并在 `libraw/build.gradle` 附近注释「升级需重跑 §2.5 体积与 §3.3 内存回归」。

---

## 3. `jpeg_write_scanlines` 的行序契约

### 3.1 契约原文

`doc/libjpeg.txt:452-466`：

> Now write all the required image data by calling jpeg*_write_scanlines() **one or more times. You can pass one or more scanlines in each call, up to the total image height.** …
> Image data should be written in **top-to-bottom scanline order**. …
> The library maintains a count of the number of scanlines written so far in the `next_scanline` field …

实现层（`src/jcapistd.c:130-137`）确认了「一次喂多条」的语义：

```c
  /* Ignore any extra scanlines at bottom of image. */
  rows_left = cinfo->image_height - cinfo->next_scanline;
  if (num_lines > rows_left) num_lines = rows_left;
  row_ctr = 0;
  (*cinfo->main->_process_data) (cinfo, scanlines, &row_ctr, num_lines);
  cinfo->next_scanline += row_ctr;
```

即：`num_lines` 会被 clamp 到剩余高度，主控制器内部按 iMCU 行循环消费调用方数组。**没有「一次最多 N 行」的限制，也没有回退/随机寻址能力**——顺序、全宽、从上到下，三者是硬约束。

### 3.2 编码器自身内存是 O(1)（可以放心分块）

`_jinit_c_main_controller`（`src/jcmainct.c:136-190`）只为每个分量分配一条「下采样后」的条带：

```c
    /* Allocate a strip buffer for each component */
    for (ci = 0, compptr = cinfo->comp_info; ci < cinfo->num_components; ci++, compptr++) {
      main_ptr->buffer[ci] = (*cinfo->mem->alloc_sarray)(...,
         compptr->width_in_blocks * data_unit,                  /* data_unit = DCTSIZE = 8 */
         (JDIMENSION)(compptr->v_samp_factor * data_unit));
    }
```

4:2:0 下 Y 分量 `2 × 8 = 16` 行、色度各 `1 × 8 = 8` 行，与图像高度无关。按本项目三种宽度精确计算（8-bit、4:2:0）：

| 图像 | Y 缓冲 | Cb | Cr | 合计 |
|---|---|---|---|---|
| 6000 × 4000 | 93.8 KiB | 23.4 KiB | 23.4 KiB | **140.6 KiB** |
| 8160 × 6120 | 127.5 KiB | 31.9 KiB | 31.9 KiB | **191.2 KiB** |
| 11648 × 8736 | 182.0 KiB | 45.5 KiB | 45.5 KiB | **273.0 KiB** |

外加 `jpeg_stdio_dest` 的 `OUTPUT_BUF_SIZE = 4096`（`src/jdatadst.c:41`）。**编码器侧总和 < 300 KiB，可以忽略。**

### 3.3 致命项：任何「多趟」都会要全图系数

`src/jcinit.c:121-129`：

```c
    /* Need a full-image coefficient buffer in any multi-pass mode. */
    jinit_c_coef_controller(cinfo, (boolean)(cinfo->num_scans > 1 || cinfo->optimize_coding));
```

`jccoefct.c:451-467` 在 `need_full_buffer` 时对每个分量 `request_virt_barray` 一圈**整幅**块数组。而默认构建的内存管理器是 `jmemnobs.c`（"assumes that no backing-store files are needed: all required space can be obtained from malloc()"），**没有磁盘回退**。

按 4:2:0（每 MCU 6 块 × 128 B = 768 B）、8-bit 计算额外常驻：

| 图像 | MCU 数 | 全图系数缓冲 |
|---|---|---|
| 6000 × 4000 | 375 × 250 = 93,750 | **68.7 MiB** |
| 8160 × 6120 | 510 × 383 = 195,330 | **143.1 MiB** |
| 11648 × 8736 | 728 × 546 = 397,488 | **291.1 MiB** |

触发条件（`src/jcmaster.c:757-786`）：`optimize_coding = TRUE`（用户设置，或渐进式自动开启 `progressive_mode → optimize_coding = TRUE`）、`jpeg_simple_progression()` 造成的 `num_scans > 1`、`arith_code`（算术编码虽强制关掉 `optimize_coding`，但仍会因 `num_scans`/内部多趟走到全量缓冲）。

**规格必须写死**：

```c
cinfo.optimize_coding   = FALSE;   /* 不调用 jpeg_simple_progression()，不用算术编码 */
cinfo.arith_code        = FALSE;
cinfo.dct_method        = JDCT_ISLOW;   /* 默认精度；JDCT_IFAST 更快但精度略降 */
```

代价：baseline Huffman 相比 optimized 通常大 5–8%。对一个 24MP 高质量 JPEG（约 8–12 MB）意味着 0.5–1 MB，完全可接受。若产品坚持要 optimized Huffman，唯一出路是把导出改成「两趟 + 自己落盘暂存」，那已经不是 P0 范畴。

### 3.4 零拷贝喂 RGBA：`JCS_EXT_RGBX`

GL 侧 `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` 是最快路径（避免 3 字节行对齐的慢通道）。libjpeg-turbo 的扩展色彩空间允许直接吃 4 通道数据，末字节被忽略：

- `src/jpeglib.h:273`：`JCS_EXT_RGBX,  /* red/green/blue/x */`
- `src/jcparam.c:285-305`：`jpeg_default_colorspace` 把 `JCS_EXT_RGBX` 归到 `JCS_YCbCr`（lossy）分支
- `src/jccolor.c:129-132,453`：`RGB_RED/GREEN/BLUE = EXT_RGBX_*`、`RGB_PIXELSIZE = 4`

所以：

```c
cinfo.input_components = 4;
cinfo.in_color_space   = JCS_EXT_RGBX;
jpeg_set_defaults(&cinfo);           /* 得到 YCbCr + 4:2:0 */
jpeg_set_quality(&cinfo, 92, TRUE);  /* TRUE = 强制 baseline 兼容量化表 */
```

**这就是「GPU 输出格式 = 编码器输入格式」**，中间不需要任何 per-pixel 转换或打包，`JSAMPROW` 数组直接指向 `glReadPixels` 写好的内存。

### 3.5 推荐编码参数

| 参数 | 值 | 理由 |
|---|---|---|
| `in_color_space` / `input_components` | `JCS_EXT_RGBX` / 4 | 吃 GL 的 RGBA8 输出，零转换 |
| `jpeg_set_quality` | 92（可 85–95 配置） | 与现有 `Bitmap.compress` 导出（`MainActivity.kt:1198`）同档，避免两条导出路径观感不一致 |
| 子采样 | `jpeg_set_defaults` 的 4:2:0；质量 ≥ 95 时手动设 `h_samp=1,v_samp=1`（4:4:4） | 4:2:0 时主控缓冲 16 行；4:4:4 时 8 行 |
| `optimize_coding` | `FALSE` | 见 §3.3 |
| `dct_method` | `JDCT_ISLOW`（默认） | 精度优先；`JDCT_IFAST` 可作「快速导出」选项（速度换精度） |
| `density_unit/X_density/Y_density` | 从原图 `XResolution/YResolution/ResolutionUnit` 映射（默认 72/1 或 300/1） | JFIF APP0 与 EXIF 保持一致 |
| `write_JFIF_header` | `TRUE`（默认） | 保持与普通相机/相册 JPEG 同构 |
| 目标管理器 | `jpeg_stdio_dest(fdopen(dup(fd),"wb"))`，或自写 `jpeg_destination_mgr` 走 `write(2)` | 内存 4 KiB；后者少一层 stdio 语义（推荐） |

---

## 4. 分块几何与精确内存

### 4.1 GL 侧硬约束（一手）

OpenGL ES 3.0.6 规范 §6.1 与 `glGet` 参考页对 `GL_MAX_TEXTURE_SIZE` 的原文：

> The value gives a rough estimate of the largest texture that the GL can handle. **The value must be at least 2048.**

`GL_MAX_RENDERBUFFER_SIZE` 同样 "must be at least 2048"；`GL_MAX_VIEWPORT_DIMS` 只保证 "at least as large as the visible dimensions of the display"（所以平板横屏可能 > 2048，但**不能**指望它 ≥ 图像宽度）。

推论（对本项目三种分辨率）：

| 图像宽 | 主流旗舰 `maxTex = 8192` | 规范下限 `maxTex = 2048` |
|---|---|---|
| 6000（24MP） | 单张全宽纹理可行 | 不可行 |
| 8160（50MP） | 勉强可行（余量 32 px） | 不可行 |
| 11648（100MP） | **不可行** | 不可行 |

所以「全宽 Strip」不是「内存换不换得起」的问题，而是**在大图上根本渲染不出来**。任何要覆盖 100MP 的实现都必须做横向切分，哪怕纵向只有一条。

### 4.2 三种几何的精确内存

统一口径：中间结果 RGBA16F（8 B/px，`GL_EXT_color_buffer_half_float` 可用时）或 RGBA8（4 B/px 降级）；ping-pong 两张渲染目标；CPU 侧行缓冲用 RGBA8（4 B/px，直接作为 `JSAMPROW` 数据）；halo 64 px（管线文档 §5.4 的「固定 32，最大 64」）。

**几何 A：全宽水平 Strip（`W × S`）** —— 仅当 `W ≤ maxTex`

| 图像 | S=256 | S=512 | S=1024 |
|---|---|---|---|
| 24MP | 2×W×S×8 = 23.4 + 行缓冲 5.9 = **29.3 MiB** | 46.9 + 11.7 = **58.6 MiB** | 93.8 + 23.4 = **117.2 MiB** |
| 50MP | 31.9 + 8.0 = **39.8 MiB** | 63.8 + 15.9 = **79.7 MiB** | 127.5 + 31.9 = **159.4 MiB** |
| 100MP | 不可渲染 | 不可渲染 | 不可渲染 |

计入 halo（目标高 `S + 2×64`）后 24MP/S=512 实际是 `2 × 6000×640×8 = 58.6 MiB` + 行缓冲 11.7 = **70.3 MiB**；50MP 为 **95.6 MiB**。

**几何 B：正方形 Tile `2048 × 2048` + 全宽拼接缓冲** —— 工单提出的对照方案

| 图像 | 2×Tile（RGBA16F） | 拼接缓冲 `W × 2048 × 4` | 合计 |
|---|---|---|---|
| 24MP | 64.0 MiB | 46.9 MiB | **110.9 MiB** |
| 50MP | 64.0 MiB | 63.8 MiB | **127.8 MiB** |
| 100MP | 64.0 MiB | 91.0 MiB | **155.0 MiB** |

**几何 C：全宽 row buffer + ≤2048 宽列带（推荐）**

参数：`S = 512`（16 的倍数 → 4:2:0 下正好 32 个 MCU 行）、`halo = 64`、band 有效宽 `bw = 1920`、渲染目标 `(bw + 2×halo) × (S + 2×halo) = 2048 × 640`。

| 图像 | 2 × 2048×640 RGBA16F | 行缓冲 `W × 512 × 4` | libjpeg 缓冲 | 合计 | 带数 × 条数 |
|---|---|---|---|---|---|
| 24MP | 20.0 MiB | 11.7 MiB | 141 KiB | **31.9 MiB** | 4 × 8 = 32 |
| 50MP | 20.0 MiB | 15.9 MiB | 191 KiB | **36.1 MiB** | 5 × 12 = 60 |
| 100MP | 20.0 MiB | 22.8 MiB | 273 KiB | **43.0 MiB** | 7 × 18 = 126 |

S=1024 的变体（目标 `2048 × 1152`）：24/50/100MP 分别 **59.4 / 67.9 / 81.5 MiB**，绘制次数减半、halo 系数从 1.333 降到 1.200。

### 4.3 halo 代价对比（像素工作量放大系数）

| 几何（halo=64） | 放大系数 | halo=32 |
|---|---|---|
| 全宽 Strip S=256 | 1.500 | 1.250 |
| 全宽 Strip S=512 | 1.250 | 1.125 |
| 全宽 Strip S=1024 | 1.125 | 1.062 |
| 正方形 Tile 2048² | 1.129 | 1.063 |
| 列带 1920 × 512 | 1.333 | 1.163 |
| 列带 1920 × 1024 | 1.200 | 1.098 |

正方形 Tile 的 halo 效率最高（二维都是 2048 边长），但它的内存是几何 C 的 **2.6–3.6 倍**，且**并不能免除全宽拼接**——`jpeg_write_scanlines` 只认整宽行，攒够 2048 行就必须落地成全宽缓冲。用 3.6 倍内存换 18% 的像素工作量（1.333 → 1.129），在 512MB 预算下不划算。

**结论：正方形 Tile 只适合预览/局部重算，不适合流式导出。**

### 4.4 推荐几何与数据通路

```
for row0 in 0 until H step S:                        // S = 512
    rowBuf = ByteBuffer(W * S * 4)                   // RGBA8, 复用同一块
    for col0 in 0 until W step bw:                   // bw = 1920
        bandW  = min(bw, W - col0)
        renderIntoFbo(bandW + 2H, S' + 2H, col0 - H, row0 - H)   // 6-pass chain
        glPixelStorei(GL_PACK_ROW_LENGTH, W)
        glReadPixels(0, 0, bandW, S', GL_RGBA, GL_UNSIGNED_BYTE,
                     rowBuf + col0 * 4)              // 直接散射进全宽行缓冲
    jpeg_write_scanlines(&cinfo, rowPtrs, S')        // rowPtrs[i] = rowBuf + i*W*4
```

四个实现要点：

1. **`GL_PACK_ROW_LENGTH = W` 是这条通路的关键**。ES 3.0 §4.3.2 Table 4.4 明确 `PACK_ROW_LENGTH / PACK_SKIP_ROWS / PACK_SKIP_PIXELS / PACK_ALIGNMENT` 作用于 `ReadPixels`；把 stride 设成整宽，就能让驱动把一条带**直接写进全宽缓冲的正确列偏移**，省掉一次逐行 `memcpy`（100MP 下每张图要省 11648×8736×4 ≈ 388 MB 的拷贝量）。`PACK_ALIGNMENT` 保持默认 4，`W × 4` 天然 4 对齐。
2. **行序要翻**。`glReadPixels` 的 `y` 原点在左下（ES 3.0 §2.12「viewport's lower left」），返回的第一行是**图像最下面**一行；而 `jpeg_write_scanlines` 要求 top-to-bottom。最省事的做法是在导出的顶点着色器里翻转 V，使 FBO 第 0 行就是图像第 0 行，这样 `rowPtrs` 可以顺序构造。**不要**在 CPU 上倒序喂 `JSAMPROW`（会破坏 `next_scanline` 单调性，也失去零拷贝）。
3. **`S` 取 16 的倍数**，保证是 4:2:0 的整数个 MCU 行、也是 4:4:4 的整数个块行；否则末条带要补边（libjpeg 会自动 pad 最后一条 iMCU 行，但补的是重复行，视觉上无害、语义上要清楚）。
4. **列带宽度必须满足 `bw + 2×halo ≤ maxTex`**。`maxTex = 2048` 时 `bw ≤ 1920`；`maxTex ≥ 4096` 时也建议保持 1920，让内存在 24/50/100MP 上恒定，只让行缓冲随宽度线性增长。

### 4.5 512MB 预算的真实构成

把三块加起来看，「分块机制」其实很便宜，**源图才是大头**：

| 项 | 24MP | 50MP | 100MP |
|---|---|---|---|
| 源图 RGBA16F 全幅纹理 | 183.1 MiB | 381.0 MiB | 776.3 MiB |
| 源图 RGBA8 全幅纹理 | 91.6 MiB | 190.5 MiB | 388.2 MiB |
| 源图 `half_size=1` 后再 RGBA16F | 45.8 MiB | 95.3 MiB | 194.1 MiB |
| 导出分块机制（几何 C，S=512） | 31.9 MiB | 36.1 MiB | 43.0 MiB |
| libjpeg 编码器 | 0.14 MiB | 0.19 MiB | 0.27 MiB |
| 原生堆 + Java 堆 + GL 驱动开销（经验预留） | ~100 MiB | ~100 MiB | ~100 MiB |

结论很清楚：

- 24MP：全幅 RGBA16F 源图 + 分块机制 ≈ 215 MiB，**在 512MB 内可行**。
- 50MP：全幅 RGBA16F ≈ 412 MiB + 36 MiB，**已经贴着天花板**，任何额外纹理池都会破线。
- 100MP：全幅 RGBA16F 776 MiB **必然 OOM**；`half_size=1` 后 194 MiB 才回到可行区间。

也就是说，**「导出长边上限」应由「源图是否必须全幅驻留」推导，而不是由分块机制推导**。分块机制已经把自身压到 43 MiB 以内，再优化它没有意义；真正的档位选择（≤24MP 全幅 / >24MP 走 `half_size` 或降采样 / 长边上限 8000）属于导出规格工单的决策，本文只提供这条预算边界。

> 附注：管线文档 §5.5 写的「长边上限 8000px」在 100MP 上意味着 8000×6000 的 RGBA16F 源图 = 366 MiB，加上分块机制与堆开销会破 512MB。若采用该上限，源图必须以 RGBA8 或 `half_size` 形式驻留，或改用「按需重解码条带」的源图供应方式。**这是本文对导出规格的硬约束输入，不是本文的结论。**

---

## 5. EXIF 写回

### 5.1 `saveAttributes()` 的真实行为（一手源码）

`androidx.exifinterface:exifinterface:1.4.2`，`ExifInterface.java`。

**javadoc 自述**（`:4864-4866`）：

> Save the tag data into the original image file. **This is expensive because it involves copying all the data from one file to another and deleting the old file and renaming the other.** It's best to use `setAttribute` to set all attributes to write and make a single call rather than multiple calls for each attribute.

**实现摘要**（`:4882-5020`）：

```java
public void saveAttributes() throws IOException {
    if (!isSupportedFormatForSavingAttributes(mMimeType)) throw new IOException(... "JPEG, PNG, WebP" ...);
    if (mSeekableFileDescriptor == null && mFilename == null) throw new IOException(...);
    if (mHasThumbnail && mHasThumbnailStrips && !mAreThumbnailStripsConsecutive) throw new IOException(...);

    mModified = true;
    mThumbnailBytes = getThumbnail();          // 把缩略图读进内存（新建文件时为 null）

    File tempFile;
    // 1) 全量拷贝到临时文件
    tempFile = File.createTempFile("temp", "tmp");     // :4908
    if (mFilename != null) in = new FileInputStream(mFilename);
    else { Os.lseek(mSeekableFileDescriptor, 0, SEEK_SET); in = new FileInputStream(mSeekableFileDescriptor); }
    out = new FileOutputStream(tempFile);
    copy(in, out);

    // 2) 从临时文件重写到目标（边写边插入新 APP1）
    in = new FileInputStream(tempFile);
    if (mFilename != null) out = new FileOutputStream(mFilename);
    else { Os.lseek(mSeekableFileDescriptor, 0, SEEK_SET); out = new FileOutputStream(mSeekableFileDescriptor); }
    bufferedIn = new BufferedInputStream(in);
    bufferedOut = new BufferedOutputStream(out);
    if (mMimeType == IMAGE_TYPE_JPEG) saveJpegAttributes(bufferedIn, bufferedOut);
    ...
    tempFile.delete();
}
```

要点逐一：

| 问题 | 答案 | 证据 |
|---|---|---|
| 会产生整文件临时拷贝吗？ | **会，而且是磁盘拷贝**（RAM 只有几十 KiB 流式缓冲） | `:4908` `File.createTempFile` |
| 临时文件落在哪？ | **应用私有 cache**，因为 Android 把 `java.io.tmpdir` 设成了 `context.getCacheDir()` | AOSP `ActivityThread.java:7341` `System.setProperty("java.io.tmpdir", cacheDir.getAbsolutePath())` |
| 需要可 seek 的 FD 吗？ | **必须**。不可 seek 时 `mSeekableFileDescriptor = null`，`saveAttributes()` 直接抛 | `isSeekableFD`（`:4839`）= `Os.lseek(fd,0,SEEK_CUR)` 试探；`:4887-4890` 抛异常 |
| 会不会截断？ | `File` 路径会（`FileOutputStream` 带 `O_TRUNC`）；**FD 路径不会**（源码全文无 `truncate`/`ftruncate`） | 全文 grep `truncate` 0 命中；`:4930-4940` FD 分支直接 `new FileOutputStream(fd)` |
| JPEG 段顺序 | 新 APP1 插在 **SOI 之后、原 APP0(JFIF) 之前**，其余段原样拷贝 | `saveJpegAttributes`（`:6514+`）先写 `MARKER_SOI` 再写 APP1，随后循环复制 |
| EXIF 大小上限 | **65535 B**，超出抛 `IllegalStateException` | `:7804-7808` |
| 会不会写缩略图？ | 只有 `mHasThumbnail` 为真才写 IFD1；新建文件没有缩略图，**导出的 JPEG 不会带旧缩略图**（正确行为） | `:7689-7693, 7723-7736` |
| 会改 `ImageWidth/ImageLength` 吗？ | **不会**。写入的是 `mAttributes` 里的值，必须自己 `setAttribute` | `writeExifSegment` 无自动计算 |
| 能枚举全部标签吗？ | **不能**。1.4.2 没有 `getAttributes()`，只有 `getAttribute(String)` | `:4311`（`getAttribute`）、`public @Nullable String getAttribute` 是唯一枚举入口 |

### 5.2 API 24–28：直接写 `File`

现状：`MainActivity.saveToGallery`（`MainActivity.kt:1253-1267`）在 `SDK < 29` 分支直接写 `Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/PhotoSync/<name>` 再 `MediaStore.insert`。这条路径下 EXIF 写回就是：

```kotlin
ExifInterface(file.absolutePath).apply {
    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    ... // 白名单标签
}.saveAttributes()
```

三个必须处理的风险：

1. **中途失败会留下半截文件**（没有 `IS_PENDING` 保护，且 `FileOutputStream` 一开就截断）。规格应要求：先写 `<name>.jpg.tmp` → `ExifInterface(tmpFile).saveAttributes()` → `tmpFile.renameTo(final)` → 再 `MediaStore.insert`。
2. **需要 `WRITE_EXTERNAL_STORAGE`**（API ≤28），现有代码已有 `REQ_WRITE_STORAGE` 流程（`MainActivity.kt:1175-1185`），`GallerySink` 要保留这个前置条件。
3. **`saveAttributes()` 的临时拷贝也在 cache**，低存储设备上会 `IOException`；失败必须删除 tmp 并回报，不能留孤儿文件。

### 5.3 API 29+：`IS_PENDING=1` + `"rw"` FD

官方推荐节奏（developer.android.com "Add an item → Toggle pending status"）：`insert(IS_PENDING=1)` → 通过 `openFileDescriptor(uri,"w")` 写数据 → `update(IS_PENDING=0)` 发布；"Only your app can view the file until your app changes the value of `IS_PENDING` back to 0."

本项目的导出流程应为：

```
1. values = {DISPLAY_NAME, MIME_TYPE=image/jpeg, RELATIVE_PATH=Pictures/PhotoSync, IS_PENDING=1}
2. uri = resolver.insert(Images.EXTERNAL_CONTENT_URI, values)
3. pfd = resolver.openFileDescriptor(uri, "w")  → detachFd() → 交给 native 编码器
4. native 写完 → close(fd)
5. pfd2 = resolver.openFileDescriptor(uri, "rw")           // 必须 "rw"
   ExifInterface(pfd2.fileDescriptor).apply { ...白名单... ; TAG_ORIENTATION = "1" }.saveAttributes()
   pfd2.close()
6. values = {IS_PENDING = 0}; resolver.update(uri, values, null, null)
7. 任何一步抛异常 → resolver.delete(uri, null, null)  // 不留半成品
```

为什么第 5 步必须是 `"rw"`：AOSP `ContentResolver.openFileDescriptor` 的注释写得很直白——

> If opening with the exclusive "r" or "w" modes, the returned ParcelFileDescriptor **could be a pipe or socket pair** to enable streaming of data. **Opening with the "rw" mode implies a file on disk that supports seeking.**

而 `ExifInterface` 的 FD 分支只有在 `isSeekableFD()` 为真时才保留 `mSeekableFileDescriptor`，否则 `saveAttributes()` 直接抛。**用 `"w"` 打开再写 EXIF 是本场景最常见的踩坑点。**

再几个具体注意：

- **不要用 `"rwt"`**：官方文档明说它是 "truncating or overwriting existing file contents"，会把刚写好的 JPEG 清零。
- **FD 路径不截断**：本流程里目标文件是 libjpeg 刚写出的、**没有 APP1** 的新文件，`saveAttributes()` 只会让它变长，因此不存在「尾部残留旧数据」问题。这个结论**只对「新建且无 EXIF」的目标成立**；若将来复用已导出的文件再改 EXIF，必须自己补 `Os.ftruncate(fd, newLength)`。
- **顺序不能反**：必须在 `IS_PENDING=0` 之前写完 EXIF，否则相册/其他应用有机会看到无 EXIF 的中间态。
- **`ParcelFileDescriptor` 生命周期**：`detachFd()` 之后 raw fd 归 native 管，`close(2)` 由 native 负责；EXIF 那一步用的是另一个 `ParcelFileDescriptor`，必须自己 `close()`（`use {}`）。
- **`MediaStore` 的 `SIZE` 列不用手动维护**：EXIF 写入会使文件变长，但发布后相册以实际文件为准。避免「手动写 SIZE 反而与真实大小不一致」。
- **`ACCESS_MEDIA_LOCATION`**：只影响**读取**他人照片的 GPS；我们写自己的新条目不受影响（官方文档 "Media location permission" 一节）。

### 5.4 零重写替代方案（P1 优化，不是 P0 必需）

如果不想付 §5.1 的整文件拷贝，可以在 native 侧把 EXIF blob 拼好后**随图像流一起写出**：

```c
jpeg_start_compress(&cinfo, TRUE);
jpeg_write_marker(&cinfo, JPEG_APP0 + 1, exifBlob, exifLen);  // 必须在第一条 scanline 之前
/* 然后才开始 jpeg_write_scanlines */
```

约束（`src/jcapimin.c:244-262`）：`jpeg_write_marker` 要求 `cinfo->next_scanline == 0`，否则 `JERR_BAD_STATE`。所以写入时机是 `jpeg_start_compress()` 之后、第一次 `jpeg_write_scanlines()` 之前，**只能写一次，且必须一次性给全 blob**（APPn 段长上限 65533 + 2）。

成本收益：

- 收益：完全省掉「临时拷贝 + 整文件重写」两趟 I/O（100MP 导出的 40 MB JPEG 省约 80 MB 磁盘写 + 80 MB 读）；API 29+ 可以简化成「单次 `"w"` FD 写完即发布」，不再需要第二次 `"rw"` 打开。
- 成本：自己实现 TIFF/IFD 序列化（字节序、IFD0/ExifIFD/GPS IFD 偏移、RATIONAL/SRATIONAL/ASCII/UNDEFINED 编码、MakerNote 透传），约 300 行 C++ 且有格式正确性风险；`ExifInterface` 的标签字典与类型推断不能复用。
- 判断：**P0 用 `ExifInterface`（正确性优先），P1 若导出耗时成为瓶颈再替换**。替换时保留 `ExifInterface` 作为「读原图」的实现（只读不需要可写 FD，用 `InputStream` 构造即可）。

### 5.5 RAW 源：LibRaw → EXIF 映射

字段来源均取自仓库内置 submodule `libraw/src/main/cpp/third_party/LibRaw/libraw/libraw_types.h`。

| EXIF 标签（ExifInterface 常量） | LibRaw 字段 | 类型/格式 | 备注 |
|---|---|---|---|
| `TAG_MAKE` | `imgdata.idata.normalized_make` → 回退 `make[64]` | ASCII | `normalized_*` 是 0.21+ 的规范化名，跨品牌更统一 |
| `TAG_MODEL` | `idata.normalized_model` → `model[64]` | ASCII | |
| `TAG_SOFTWARE` | `idata.software[64]` | ASCII | 常为空，空则跳过 |
| `TAG_DATETIME_ORIGINAL` | `other.timestamp`（`time_t`） | ASCII `yyyy:MM:dd HH:mm:ss` | **必须 `localtime()`**，见下 |
| `TAG_DATETIME_DIGITIZED` | 同上 | 同上 | 相机通常两者相同 |
| `TAG_DATETIME` | 同上 | 同上 | IFD0 的 DateTime |
| `TAG_EXPOSURE_TIME` | `other.shutter`（float，秒） | RATIONAL | `< 1s` 用 `1/N`（如 `1/250`），`≥1s` 用 `N/1` |
| `TAG_F_NUMBER` | `other.aperture`（float，f 值） | RATIONAL | `28/10` 形式，保留一位小数 |
| `TAG_PHOTOGRAPHIC_SENSITIVITY` | `other.iso_speed`（float） | SHORT/INT | 用新名；`TAG_ISO_SPEED_RATINGS` 在 `setAttribute` 内部会被重定向（`ExifInterface.java:4402-4414`） |
| `TAG_FOCAL_LENGTH` | `other.focal_len`（float，mm） | RATIONAL | |
| `TAG_FOCAL_LENGTH_IN_35MM_FILM` | `lens.FocalLengthIn35mmFormat`（ushort） | SHORT | 0 表示未知 |
| `TAG_LENS_MAKE` | `lens.LensMake[128]` | ASCII | |
| `TAG_LENS_MODEL` | `lens.Lens[128]` | ASCII | 回退 `lens.makernotes.Lens[128]` |
| `TAG_LENS_SERIAL_NUMBER` | `lens.LensSerial[128]` | ASCII | |
| `TAG_BODY_SERIAL_NUMBER` | `shootinginfo.BodySerial[64]` | ASCII | |
| `TAG_EXPOSURE_PROGRAM` | `shootinginfo.ExposureProgram`（short） | SHORT | 0 未定义 / 1 手动 / 2 程序 / 3 光圈优先 / 4 快门优先 |
| `TAG_METERING_MODE` | `shootinginfo.MeteringMode`（short） | SHORT | |
| `TAG_IMAGE_DESCRIPTION` | `other.desc[512]` | ASCII | 可能被相机写满垃圾，建议只在非空且可打印时写 |
| `TAG_ARTIST` | `other.artist[64]` | ASCII | |
| `TAG_IMAGE_WIDTH` / `TAG_IMAGE_LENGTH` | **导出尺寸**（非源图尺寸） | LONG/SHORT | 由导出管线提供，不是 LibRaw 字段 |
| `TAG_ORIENTATION` | 常量 `"1"` | SHORT | 像素已在 GPU 侧转正，必须强制 1 |
| GPS 组 | `other.parsed_gps`（`libraw_gps_info_t`，`libraw_types.h:828-836`） | — | 用 `ExifInterface.setLatLong(lat, lon)` + `setAltitude(m)`，让库自己写 DMS 与 Ref |
| `TAG_X_RESOLUTION`/`Y`/`RESOLUTION_UNIT` | LibRaw 不直接暴露 | RATIONAL/SHORT | 默认 `72/1` + `2`(inch)；若沿用原图则以原图 EXIF 为准 |

**时间语义是唯一的真正陷阱。** `src/metadata/misc_parsers.cpp:557-577`：

```c
  if (sscanf(str, "%d:%d:%d %d:%d:%d", &t.tm_year, &t.tm_mon, &t.tm_mday,
             &t.tm_hour, &t.tm_min, &t.tm_sec) != 6) return;
  t.tm_year -= 1900; t.tm_mon -= 1; t.tm_isdst = -1;
  if (mktime(&t) > 0) timestamp = mktime(&t);
```

`mktime()` 把相机墙钟字符串按**设备当前时区**解释。写回时必须对称地用 `localtime()`（`strftime("%Y:%m:%d %H:%M:%S")`），并用 `tm_isdst = -1` 让 libc 自己判定夏令时。用 `gmtime()` 会让所有导出照片的拍摄时间偏移整个 UTC 差（东八区 → 早 8 小时），而且**在用户跨时区旅行后行为还会变**，是最难复现的一类 bug。

其余健壮性要求：

- `other.timestamp == 0`（未解析到）时**不要写** DateTime 系列，否则会写出 `1970:01:01 08:00:00`。
- `iso_speed`/`shutter`/`aperture`/`focal_len` 为 0 表示未知，跳过而不是写 `0/1`。
- `parsed_gps.gpsparsed == 0` 时不写 GPS 组；`altitude == 0` 且 `altref == 0` 也要区分「海平面 0m」与「无数据」。
- `make/model` 可能含尾部空格与不可打印字符（尤其 Sony/Nikon 的 `model` 会带机型后缀），写入前 trim。

### 5.6 JPEG 源：标签白名单复制

因为 1.4.2 没有 `getAttributes()`（§5.1 末行），「抄原图 EXIF」只能显式枚举。建议分成三组：

```kotlin
private val COPY_TAGS = arrayOf(
    TAG_MAKE, TAG_MODEL, TAG_SOFTWARE, TAG_LENS_MAKE, TAG_LENS_MODEL,
    TAG_LENS_SERIAL_NUMBER, TAG_BODY_SERIAL_NUMBER,
    TAG_DATETIME, TAG_DATETIME_ORIGINAL, TAG_DATETIME_DIGITIZED, TAG_OFFSET_TIME,
    TAG_OFFSET_TIME_ORIGINAL, TAG_OFFSET_TIME_DIGITIZED,
    TAG_EXPOSURE_TIME, TAG_F_NUMBER, TAG_PHOTOGRAPHIC_SENSITIVITY,
    TAG_FOCAL_LENGTH, TAG_FOCAL_LENGTH_IN_35MM_FILM,
    TAG_EXPOSURE_PROGRAM, TAG_METERING_MODE, TAG_FLASH,
    TAG_IMAGE_DESCRIPTION, TAG_ARTIST,
)
// 不复制：TAG_ORIENTATION（强制 1）、TAG_IMAGE_WIDTH/LENGTH（用导出尺寸）、
//        TAG_THUMBNAIL*（旧缩略图已失效）、TAG_MAKER_NOTE（二进制，字符串往返不安全）、
//        TAG_XMP（体积大且与新像素不一致）
```

复制方式：

```kotlin
val src = ExifInterface(sourcePath)                 // 只读，InputStream/File 均可
val dst = ExifInterface(fd)
COPY_TAGS.forEach { tag -> src.getAttribute(tag)?.let { dst.setAttribute(tag, it) } }
src.getLatLong()?.let { dst.setLatLong(it[0], it[1]) }
src.getAltitude(0.0).takeIf { it != 0.0 }?.let { dst.setAltitude(it) }
dst.setAttribute(TAG_ORIENTATION, "1")
dst.setAttribute(TAG_IMAGE_WIDTH, width.toString())
dst.setAttribute(TAG_IMAGE_LENGTH, height.toString())
dst.saveAttributes()
```

已知精度损失（必须写进规格，避免验收时被当 bug）：

- RATIONAL 经 `getAttribute` 变成 `"num/den"` 字符串，再 `setAttribute` 时按标签字典重建；`ExposureTime` 的 `10/3000` 会归一化成 `1/300`。这是 `ExifInterface` 的既定行为，不是我们的错。
- 未列入白名单的厂商私有标签（MakerNote 里的镜头校正数据、Canon 的 `ColorData`、Nikon 的 `ShotInfo`）**会丢失**。若产品要求「像素级 EXIF 保真」，只能走 §5.4 的透传方案（自己拼 blob 时可以把原 MakerNote 原样搬进 `TAG_MAKER_NOTE`）。
- `TAG_MAKER_NOTE` 即使 `setAttribute` 传入字符串也会被当 ASCII 写，**必然损坏**，必须排除。

### 5.7 代码契约（供实现阶段直接照抄）

Kotlin / 原生边界：

```kotlin
// :editor 侧（:libraw 暴露）
interface JpegStreamEncoder : Closeable {
    fun begin(fd: Int, width: Int, height: Int, quality: Int, fourTwoZero: Boolean)
    /** rgba: width * rows * 4 字节，行序为图像从上到下；返回实际消费行数 */
    fun writeStrip(rgba: ByteBuffer, rows: Int): Int
    fun finish()
}
```

JNI 侧（C++）必须遵守的不变量：

| 不变量 | 依据 |
|---|---|
| `width`/`height` 在 `jpeg_start_compress` 前设定，之后不可变 | `doc/libjpeg.txt:441` |
| `input_components=4` + `JCS_EXT_RGBX`，行缓冲每行 `width*4` 字节 | `jpeglib.h:273`、`jccolor.c:129-132` |
| `optimize_coding=FALSE`、无 `jpeg_simple_progression()`、`arith_code=FALSE` | `jcinit.c:121-129`（§3.3） |
| 每次 `writeStrip` 的 `rows` 可为任意 ≤ 剩余高度值；`S` 取 16 的倍数 | `jcapistd.c:130-137` |
| 全部行写完后必须 `jpeg_finish_compress`，否则尾部缓冲不落盘 | `doc/libjpeg.txt:504-518` |
| 目标管理器写 raw fd（`write(2)`），不要 `fsync` 在编码线程里做 | 编码器会做大量 4 KiB 小块写 |

---

## 6. 给 `.scratch/image-editor/spec.md` 的决策输入

1. **构建体系**：`:libraw` 增加 `third_party/libjpeg-turbo` submodule（钉 3.2.2 tag）+ `ExternalProject_Add`；`raw_wrapper` 链接 `jpeg_static` 并加 `-Wl,--exclude-libs,ALL`。CMake 版本无需升级（`3.22.1` 满足上游 `3.15...3.28`）。
2. **编码参数**：`JCS_EXT_RGBX` + `quality` 可配置（默认 92）+ 4:2:0（质量 ≥95 时切 4:4:4）+ `optimize_coding=FALSE` + `JDCT_ISLOW`。
3. **导出几何**：条带高 `S=512`（16 的倍数）、列带有效宽 1920、halo 64、渲染目标 `2048×640`；CPU 全宽行缓冲 `W × 512 × 4`；`GL_PACK_ROW_LENGTH = W` 散射；shader 内翻转 V 保证行序。
4. **预算**：分块机制常量级 32–43 MiB（24–100MP），**导出分辨率档位由源图驻留方式决定**，不由分块机制决定；100MP 必须 `half_size=1` 或降采样（§4.5 表）。
5. **EXIF**：P0 用 `androidx.exifinterface:exifinterface:1.4.2` + `saveAttributes()`；API 29+ 必须 `"rw"` FD；API ≤28 用 tmp+rename；`Orientation=1`；`ImageWidth/Length` 显式写；标签白名单（§5.6）；EXIF 载荷 >64 KiB 时裁剪。
6. **`GallerySink` 契约调整**：需要一个「可二次打开」的句柄，不只是 `OutputStream`。建议暴露 `fun openWrite(): Int`（raw fd，交 native）与 `fun openRandomAccess(): ParcelFileDescriptor/File`（交 `ExifInterface`），并在 `IS_PENDING` 发布前完成两步。
7. **新增依赖**：`gradle/libs.versions.toml` 加 `androidx.exifinterface:exifinterface = "1.4.2"`（Google Maven 元数据 `lastUpdated 20261119`，最新正式版；`1.5.0-beta02` 存在但不必冒险）。

---

## 7. 未验证项与风险

| 项 | 状态 | 影响 / 建议 |
|---|---|---|
| **AGP 真实构建下的 `ExternalProject`** | 未验证（不能在本次调研中改仓库） | 已在独立 CMake 工程用同一 NDK/CMake/`c++_static` 复现并通过，但 AGP 的 Ninja + `CMAKE_MAKE_PROGRAM` 转发需在接入时实测一次 |
| **`ExternalProject` 在 `assembleRelease` 下的增量行为** | 未验证 | stamp 机制在 Makefiles 上已验证零重建；Ninja 同理但未跑 |
| **`arm64` + `-DANDROID_ARM_MODE` 无关** | 已验证（`simd/arm/` 无 `.S`） | 但若将来上游恢复汇编，需重新评估 |
| **`saveAttributes()` 在 MediaStore FUSE FD 上的实测** | 未验证（无设备） | 理论依据充分（`"rw"` → 落盘可 seek），但**必须在 API 29/30/33 各一台真机上跑一次**，断言写入后 `getAttribute(TAG_ORIENTATION)=="1"` 且文件可被相册打开 |
| **EXIF 重写对 MediaStore `SIZE`/`DATE_MODIFIED` 的影响** | 未验证 | 发布前完成写入即可规避；若发现相册缓存大小，再补一次 `update(SIZE=...)` |
| **`exifinterface` 1.4.2 的 minSdk** | 未取到官方明文（build.gradle 未声明，走 AndroidX 默认） | 项目 `minSdk 24` 远高于其基线，接入时用 manifest merger 输出确认一次即可 |
| **100MP 真机导出全链路** | 未验证 | 本文只保证「分块机制 ≤43 MiB」；源图驻留是瓶颈（§4.5），需在导出规格里先定档 |
| **`JCS_EXT_RGBX` 与 4:2:0 的色度质量** | 未验证 | 主观验收（与 `Bitmap.compress` 同质量对比）后再定默认 quality |

---

## 8. 来源

### 一手：libjpeg-turbo 3.2.2（`main` @ `093bc6c`，2026-11-20）

1. `CMakeLists.txt:1`（`cmake_minimum_required(VERSION 3.15...3.28)`）、`:17`（`VERSION 3.2.2`）、`:44-71`（add_subdirectory 拒绝 + 理由）、`:244-292`（`ENABLE_SHARED/ENABLE_STATIC/REQUIRE_SIMD/WITH_SIMD/WITH_TURBOJPEG/WITH_TOOLS/WITH_TESTS`）、`:126-131`（CPU_TYPE 判定）、`:622,707-708`（生成 `jversion.h/jconfig.h/jconfigint.h`）、`:778`（`add_library(jpeg-static STATIC ...)`）。
   <https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/CMakeLists.txt>
2. `simd/CMakeLists.txt:234-268`（ARM 分支与 32 位运行时探测注释）、`:336-359`（`SIMD_SOURCES` 与 `-mfpu=neon`）、`:349`（`neon-compat.h` 生成）。
   <https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/simd/CMakeLists.txt>
3. `BUILDING.md:86-87`（禁止 add_subdirectory）、`:430-535`（Android armv7/arm64 配方）。
   <https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/BUILDING.md>
4. `doc/libjpeg.txt:441`（`jpeg_start_compress` 后参数冻结）、`:452-466`（scanline 顺序契约）、`:504-518`（`jpeg_finish_compress` 必需）。
   <https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/doc/libjpeg.txt>
5. `src/jcapistd.c:85-137`（`_jpeg_write_scanlines` 的 clamp 与循环消费）、`src/jcmainct.c:136-190`（每分量条带缓冲）、`src/jcinit.c:121-129`（多趟 → 全图系数）、`src/jccoefct.c:436-467`（`whole_image` 虚拟数组）、`src/jmemnobs.c:1-40`（无 backing store）、`src/jcmaster.c:757-800`（`optimize_coding` 推导）、`src/jcparam.c:285-310`（`JCS_EXT_*` 归类）、`src/jccolor.c:129-132,453`（`EXT_RGBX` 宏映射）、`src/jpeglib.h:273-284`（扩展色彩空间枚举）、`src/jdatadst.c:41`（`OUTPUT_BUF_SIZE 4096`）、`src/jcapimin.c:244-262`（`jpeg_write_marker` 要求 `next_scanline==0`）。
   <https://github.com/libjpeg-turbo/libjpeg-turbo/tree/main/src>
6. `LICENSE.md`（IJG/BSD 系）。
   <https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/LICENSE.md>

### 一手：OpenGL ES 3.0.6 规范（2019-11-01）

7. §6.1 状态表与 `glGet` 语义（`GL_MAX_TEXTURE_SIZE` / `GL_MAX_RENDERBUFFER_SIZE` "must be at least 2048"）：<https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glGet.xhtml>
8. §4.3.2 Table 4.4（`PACK_ROW_LENGTH / PACK_SKIP_ROWS / PACK_SKIP_PIXELS / PACK_ALIGNMENT` 作用于 `ReadPixels`）、§3.7.2.1（行 stride 公式 3.12/3.13）：<https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf>
9. `glPixelStorei` 参考页（pack/unpack 参数清单）：<https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glPixelStorei.xhtml>

### 一手：androidx.exifinterface 1.4.2（Google Maven 发布源码）

10. `ExifInterface.java:4864-4866`（"expensive ... copying all the data"）、`:4882-5020`（`saveAttributes` 全流程）、`:4908`（`File.createTempFile`）、`:4839`（`isSeekableFD`）、`:4122`（`ExifInterface(FileDescriptor)`）、`:4311`（`getAttribute`）、`:4408-4414`（ISO 标签重定向）、`:6514+`（`saveJpegAttributes` 段序）、`:7804-7808`（APP1 64 KiB 上限）、`:4400-4440`（`setAttribute` 兼容转换）。
    <https://dl.google.com/dl/android/maven2/androidx/exifinterface/exifinterface/1.4.2/exifinterface-1.4.2-sources.jar>
11. 版本元数据（最新正式版 1.4.2，`lastUpdated 20261119`）：<https://dl.google.com/dl/android/maven2/androidx/exifinterface/exifinterface/maven-metadata.xml>

### 一手：AOSP（`main`）

12. `ActivityThread.java:7341`（`java.io.tmpdir = context.getCacheDir()`）：<https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/app/ActivityThread.java>
13. `ContentResolver.java:1644-1680`（"rw" 暗示落盘可 seek；"r"/"w" 可能是 pipe）：<https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/content/ContentResolver.java>
14. Android 开发者文档「Add an item → Toggle pending status」（`IS_PENDING` 语义与发布节奏）、「Update in native code」（`detachFd()`）：<https://developer.android.com/training/data-storage/shared/media#add-item>

### 一手：LibRaw（仓库内 submodule）

15. `libraw_types.h:178-198`（`libraw_iparams_t`：`make/model/software/normalized_*`）、`:838-850`（`libraw_imgother_t`：`iso_speed/shutter/aperture/focal_len/timestamp/desc/artist/parsed_gps`）、`:828-836`（`libraw_gps_info_t`）、`:1045-1057`（`libraw_shootinginfo_t`）、`:1018-1026`（`libraw_lensinfo_t` 的 `LensMake/Lens/LensSerial/FocalLengthIn35mmFormat`）。
    `libraw/src/main/cpp/third_party/LibRaw/libraw/libraw_types.h`
16. `src/metadata/misc_parsers.cpp:557-577`（`get_timestamp` 用 `mktime`，`tm_isdst=-1`）。
    `libraw/src/main/cpp/third_party/LibRaw/src/metadata/misc_parsers.cpp`

### 一手：本仓库

17. `libraw/build.gradle:6-41`、`libraw/src/main/cpp/CMakeLists.txt:1-68`、`app/src/main/java/cn/devcxl/photosync/activity/MainActivity.kt:1196-1273`、`docs/research/2026-11-21-image-editor-pipeline.md` §5.4/§6.4。

### 实测记录（本次调研自行构建，非外部来源）

18. `libjpeg.a` 体积、encoder-only stripped `.so` 增量、`--whole-archive` 全量、SIMD 开关对比、ExternalProject 单目标/增量构建、OpenMP 共存、`-Wl,--exclude-libs,ALL` 符号隐藏、guard-only 补丁可构建：均在 NDK `29.0.14033849` + CMake `3.22.1` 下用 `arm64-v8a`/`armeabi-v7a`、`android-24`、`c++_static`、`Release` 复现。

### 二手（仅作旁证，不承载结论）

19. `libjpeg-turbo` issue 讨论「add_subdirectory 支持」的历史（上游立场与 `CMakeLists.txt:44-71` 一致，本文结论只引源码）。
