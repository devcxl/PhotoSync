# LibRaw 独立模块化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 LibRaw + lcms2 从预编译 `.so` 改为源码编译，封装为独立 `:libraw` Gradle Library 模块，支持 `arm64-v8a` + `armeabi-v7a`。

**Architecture:** 新建 `:libraw` Android Library 模块，通过 git submodule 引入 LibRaw 0.21.4 和 Little-CMS 源码，自行编写 CMakeLists.txt 编译为静态库，最终链接为单个 `libraw_wrapper.so`。app 模块依赖 `:libraw`，移除所有原生编译配置和预编译产物。

**Tech Stack:** Kotlin, C++17, CMake 3.22.1, Android NDK, Gradle (Android Library plugin)

---

## 文件结构总览

### 新建文件
- `libraw/build.gradle` — `:libraw` 模块 Gradle 配置
- `libraw/src/main/cpp/CMakeLists.txt` — CMake 构建脚本（编译 lcms2 + LibRaw + JNI wrapper）
- `libraw/src/main/cpp/libraw_jni.cpp` — 从 `app/src/main/cpp/libraw_jni.cpp` 迁移
- `libraw/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt` — 从 `app/.../wrapper/RawWrapper.kt` 迁移，添加 `System.loadLibrary`
- `libraw/src/main/AndroidManifest.xml` — 空 manifest（Android Library 必需）
- `libraw/.gitignore` — 忽略构建产物
- `libraw/src/main/cpp/third_party/LibRaw/` — git submodule（LibRaw 0.21.4）
- `libraw/src/main/cpp/third_party/Little-CMS/` — git submodule（最新稳定 tag）
- `.gitmodules` — 根目录 git submodule 配置文件

### 修改文件
- `settings.gradle` — 添加 `include ':libraw'`
- `build.gradle`（根项目）— 添加 `android-library` 插件声明
- `gradle/libs.versions.toml` — 添加 `android-library` 插件到 version catalog
- `app/build.gradle` — 添加 `implementation project(':libraw')`，移除 `externalNativeBuild`、`ndk`、`cmake` 配置
- `app/src/main/java/cn/devcxl/photosync/App.kt` — 更新 import 路径（包名不变，无需改动）
- `app/src/main/java/cn/devcxl/photosync/activity/MainActivity.kt` — 更新 import 路径（包名不变，无需改动）
- `app/src/androidTest/java/cn/devcxl/photosync/RawLibIntegrationTest.kt` — 更新 import 路径（包名不变，无需改动）

### 删除文件
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/libraw_jni.cpp`
- `app/src/main/cpp/third_party/` — 整个目录（预编译 .so + 头文件）
- `app/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt`

### 关键设计说明

1. **包名不变**: `RawWrapper.kt` 保持 `cn.devcxl.photosync.wrapper` 包名，JNI 函数签名不变（`Java_cn_devcxl_photosync_wrapper_RawWrapper_*`），因此 `App.kt`、`MainActivity.kt` 和 `RawLibIntegrationTest.kt` 的 import 语句 **无需修改**。
2. **`System.loadLibrary` 缺失**: 当前项目没有显式的 `System.loadLibrary("raw_wrapper")` 调用。迁移后必须在 `RawWrapper.kt` 中添加 `init { System.loadLibrary("raw_wrapper") }` 以确保 native 库正确加载。
3. **`kapt` 保留**: `app/build.gradle` 中的 `apply plugin: 'kotlin-kapt'` 用于 Room compiler，与 native 构建无关，保留不动。

---

## Task 1: 注册 `android-library` 插件到 Version Catalog 和根 `build.gradle`

**Files:**
- Modify: `gradle/libs.versions.toml:47-50` (plugins 部分)
- Modify: `build.gradle:1-6` (根项目)

- [ ] **Step 1: 在 `gradle/libs.versions.toml` 的 `[plugins]` 部分添加 android-library 插件**

在 `[plugins]` 部分追加：
```toml
android-library = { id = "com.android.library", version.ref = "agp" }
```

- [ ] **Step 2: 在根 `build.gradle` 中声明 android-library 插件（apply false）**

在现有 plugins 块中追加：
```groovy
alias(libs.plugins.android.library) apply false
```

- [ ] **Step 3: 提交**

```bash
git add gradle/libs.versions.toml build.gradle
git commit -m "chore: register android-library plugin in version catalog"
```

---

## Task 2: 创建 `:libraw` 模块骨架

**Files:**
- Create: `libraw/build.gradle`
- Create: `libraw/src/main/AndroidManifest.xml`
- Create: `libraw/.gitignore`
- Modify: `settings.gradle:24-25`

- [ ] **Step 1: 在 `settings.gradle` 中注册 `:libraw` 模块**

在文件末尾的 `include ':app'` 后面追加：
```groovy
include ':libraw'
```

- [ ] **Step 2: 创建 `libraw/build.gradle`**

```groovy
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cn.devcxl.photosync.libraw"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        ndk {
            abiFilters "arm64-v8a", "armeabi-v7a"
        }

        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17"
                // 显式指定 STL 类型，与原 app 配置保持一致
                arguments "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}
```

- [ ] **Step 3: 创建 `libraw/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 4: 创建 `libraw/.gitignore`**

```
/build/
```

- [ ] **Step 5: 创建必要的目录结构**

```bash
mkdir -p libraw/src/main/cpp/third_party
mkdir -p libraw/src/main/java/cn/devcxl/photosync/wrapper
```

- [ ] **Step 6: 提交**

```bash
git add libraw/ settings.gradle
git commit -m "feat(libraw): create :libraw module skeleton"
```

---

## Task 3: 添加 Git Submodule

**Files:**
- Create: `.gitmodules`
- Create: `libraw/src/main/cpp/third_party/LibRaw/` (submodule)
- Create: `libraw/src/main/cpp/third_party/Little-CMS/` (submodule)

- [ ] **Step 1: 添加 LibRaw submodule，固定到 tag 0.21.4**

```bash
git submodule add https://github.com/LibRaw/LibRaw.git libraw/src/main/cpp/third_party/LibRaw
cd libraw/src/main/cpp/third_party/LibRaw && git checkout 0.21.4 && cd -
```

- [ ] **Step 2: 添加 Little-CMS submodule，固定到最新稳定 tag**

```bash
git submodule add https://github.com/mm2/Little-CMS.git libraw/src/main/cpp/third_party/Little-CMS
cd libraw/src/main/cpp/third_party/Little-CMS && git tag -l --sort=-v:refname | head -5
# 选择最新稳定 tag（如 lcms2.16），然后 checkout
cd libraw/src/main/cpp/third_party/Little-CMS && git checkout <最新稳定tag> && cd -
```

- [ ] **Step 3: 验证 submodule 目录中存在关键文件**

```bash
# LibRaw 源码目录应存在
ls libraw/src/main/cpp/third_party/LibRaw/src/
# 应看到: decoders/ demosaic/ metadata/ postprocessing/ preprocessing/ utils/ 等子目录和 .cpp 文件

# Little-CMS CMakeLists.txt 应存在
ls libraw/src/main/cpp/third_party/Little-CMS/CMakeLists.txt
# 应看到文件存在

# LibRaw 头文件应存在
ls libraw/src/main/cpp/third_party/LibRaw/libraw/libraw.h
# 应看到文件存在
```

- [ ] **Step 4: 提交**

```bash
git add .gitmodules libraw/src/main/cpp/third_party/LibRaw libraw/src/main/cpp/third_party/Little-CMS
git commit -m "chore(libraw): add LibRaw 0.21.4 and Little-CMS as git submodules"
```

---

## Task 4: 编写 CMakeLists.txt

**Files:**
- Create: `libraw/src/main/cpp/CMakeLists.txt`

这是本计划中最复杂的 Task。CMakeLists.txt 需要完成三件事：
1. 编译 lcms2 为静态库
2. 编译 LibRaw 为静态库
3. 将两者链接到最终的 `raw_wrapper` JNI shared library

关键编译定义（从现有 `app/src/main/cpp/CMakeLists.txt:51` 审计得出）：
- `LIBRAW_NODLL` — 静态链接标记（必须保留）
- `LIBRAW_NOTHREADS` — 规格文档要求的单线程模式（新增）

- [ ] **Step 1: 创建 `libraw/src/main/cpp/CMakeLists.txt`**

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("raw_wrapper" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ── 路径定义 ───────────────────────────────────────────────────────────────────
set(LIBRAW_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/LibRaw)
set(LCMS2_ROOT  ${CMAKE_CURRENT_SOURCE_DIR}/third_party/Little-CMS)

# ── 1. 编译 lcms2 为静态库 ────────────────────────────────────────────────────
# Little-CMS 官方仓库自带 CMakeLists.txt，优先使用 add_subdirectory。
# 若 NDK 交叉编译有兼容性问题，降级为手动列出 src/*.c 文件编译。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build lcms2 as static library" FORCE)
set(LCMS2_BUILD_TESTS OFF CACHE BOOL "Disable lcms2 tests" FORCE)
set(LCMS2_BUILD_UTILS OFF CACHE BOOL "Disable lcms2 utilities" FORCE)
set(LCMS2_BUILD_PLUGINS OFF CACHE BOOL "Disable lcms2 plugins" FORCE)
add_subdirectory(${LCMS2_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/lcms2_build)

# ── 2. 编译 LibRaw 为静态库 ───────────────────────────────────────────────────
# LibRaw 主仓库不含 CMakeLists.txt，手动收集源文件编译
file(GLOB_RECURSE LIBRAW_SOURCES "${LIBRAW_ROOT}/src/*.cpp")

# 排除 *_ph.cpp 占位/桩文件（这些文件是空占位符，编译会导致符号冲突）
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

add_library(libraw_static STATIC ${LIBRAW_SOURCES})

target_include_directories(libraw_static PUBLIC
    ${LIBRAW_ROOT}/
    ${LIBRAW_ROOT}/libraw/
    ${LCMS2_ROOT}/include/
)

target_compile_definitions(libraw_static PRIVATE
    LIBRAW_NODLL          # 静态链接标记（现有配置等价）
    LIBRAW_NOTHREADS      # 单线程模式
    USE_LCMS2             # 启用 lcms2 色彩管理支持
)

# 链接 lcms2 静态库到 libraw
target_link_libraries(libraw_static PRIVATE lcms2)

# ── 3. 构建 JNI wrapper shared library ───────────────────────────────────────
add_library(${CMAKE_PROJECT_NAME} SHARED
    libraw_jni.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${LIBRAW_ROOT}/libraw/
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    LIBRAW_NODLL
)

find_library(log_lib log)
find_library(android_lib android)

target_link_libraries(${CMAKE_PROJECT_NAME}
    libraw_static
    lcms2
    ${log_lib}
    ${android_lib}
)
```

- [ ] **Step 2: 验证 CMakeLists.txt 语法（不执行编译）**

手动审查要点：
1. `LIBRAW_ROOT` 指向 `third_party/LibRaw`（submodule 根目录）
2. LibRaw 头文件路径为 `${LIBRAW_ROOT}/libraw/`（包含 `libraw.h`）
3. `GLOB_RECURSE` 能正确收集 `src/` 下所有 `.cpp`
4. `*_ph.cpp` 排除正则正确
5. lcms2 通过 `add_subdirectory` 编译，`BUILD_SHARED_LIBS=OFF` 确保静态

- [ ] **Step 3: 提交**

```bash
git add libraw/src/main/cpp/CMakeLists.txt
git commit -m "feat(libraw): add CMakeLists.txt for source compilation of LibRaw + lcms2"
```

---

## Task 5: 迁移 JNI 代码和 Kotlin Wrapper

**Files:**
- Create: `libraw/src/main/cpp/libraw_jni.cpp` (从 `app/src/main/cpp/libraw_jni.cpp` 复制)
- Create: `libraw/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt` (从 `app/.../wrapper/RawWrapper.kt` 复制 + 修改)

- [ ] **Step 1: 复制 `libraw_jni.cpp` 到新模块**

```bash
cp app/src/main/cpp/libraw_jni.cpp libraw/src/main/cpp/libraw_jni.cpp
```

注意：JNI 函数签名中的包名是 `Java_cn_devcxl_photosync_wrapper_RawWrapper_*`，与 Kotlin 端 `cn.devcxl.photosync.wrapper.RawWrapper` 一致。由于包名不变，JNI 代码无需修改。

- [ ] **Step 2: 修改 `libraw_jni.cpp` 的 include 路径**

旧：
```cpp
#include "libraw.h"
```

新文件无需改动 — CMakeLists.txt 中已配置 `target_include_directories` 指向 `${LIBRAW_ROOT}/libraw/`，可以直接 `#include "libraw.h"`。

验证 LibRaw 源码中头文件位于 `libraw/src/main/cpp/third_party/LibRaw/libraw/libraw.h`。

- [ ] **Step 3: 复制 `RawWrapper.kt` 到新模块并添加 `System.loadLibrary`**

将 `app/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt` 复制到 `libraw/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt`，然后修改：

在 `object RawWrapper {` 之后、`external fun version()` 之前添加：

```kotlin
object RawWrapper {
    init {
        System.loadLibrary("raw_wrapper")
    }

    external fun version(): String
    // ... 其余不变
```

这修复了当前项目中缺失的 native 库加载调用。

- [ ] **Step 4: 验证包名一致性**

```bash
# 确认 Kotlin 文件包声明
head -1 libraw/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt
# 应输出: package cn.devcxl.photosync.wrapper

# 确认 JNI 函数签名
grep "Java_cn_devcxl_photosync_wrapper_RawWrapper" libraw/src/main/cpp/libraw_jni.cpp
# 应输出 3 个匹配（version, decodeThumbnail, decodeToRGB）
```

- [ ] **Step 5: 提交**

```bash
git add libraw/src/main/cpp/libraw_jni.cpp libraw/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt
git commit -m "feat(libraw): migrate libraw_jni.cpp and RawWrapper.kt to :libraw module"
```

---

## Task 6: 修改 `app/build.gradle` — 添加依赖、移除 Native 配置

**Files:**
- Modify: `app/build.gradle:24-42` (移除 cmake/ndk 配置)
- Modify: `app/build.gradle:89` (添加依赖)

- [ ] **Step 1: 移除 `defaultConfig` 中的 `externalNativeBuild` 和 `ndk` 块**

删除 `app/build.gradle` 中第 24-35 行：

```groovy
        // 删除以下内容
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17"
                arguments "-DANDROID_STL=c++_shared"
            }
        }

        // Only build ABI(s) we have prebuilt libraw for. Add more when you add their libraw.so.
        ndk {
            abiFilters "arm64-v8a"
        }
```

- [ ] **Step 2: 移除顶级 `externalNativeBuild` 块**

删除 `app/build.gradle` 中第 37-42 行：

```groovy
    // 删除以下内容
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
```

- [ ] **Step 3: 在 `dependencies` 块中添加 `:libraw` 依赖**

在 `dependencies {` 块开头添加：

```groovy
    implementation project(':libraw')
```

- [ ] **Step 4: 验证 `app/build.gradle` 中不再有 native 相关配置**

```bash
grep -n "externalNativeBuild\|ndk\|cmake\|cpp" app/build.gradle
# 应无匹配（除了注释行）
```

- [ ] **Step 5: 提交**

```bash
git add app/build.gradle
git commit -m "refactor(app): depend on :libraw module, remove native build config"
```

---

## Task 7: 清理 app 模块中的旧文件

**Files:**
- Delete: `app/src/main/cpp/` (整个目录)
- Delete: `app/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt`

- [ ] **Step 1: 删除旧的 native 代码目录**

```bash
rm -rf app/src/main/cpp/
```

这将删除：
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/libraw_jni.cpp`
- `app/src/main/cpp/third_party/libraw/` (预编译 .so + 头文件)
- `app/src/main/cpp/third_party/lcms2/` (预编译 .so + 头文件)

- [ ] **Step 2: 删除旧的 `RawWrapper.kt`**

```bash
rm app/src/main/java/cn/devcxl/photosync/wrapper/RawWrapper.kt
```

- [ ] **Step 3: 确认 app 模块中不再残留 native/wrapper 相关文件**

```bash
# 确认 cpp 目录已删除
ls app/src/main/cpp/ 2>&1
# 应输出: No such file or directory

# 确认 wrapper 目录为空或不存在
ls app/src/main/java/cn/devcxl/photosync/wrapper/ 2>&1
# 应输出: No such file or directory（如果目录下没有其他文件）
```

- [ ] **Step 4: 验证 import 路径仍然有效**

由于 `RawWrapper.kt` 的包名 `cn.devcxl.photosync.wrapper` 没有改变，以下文件中的 import 语句 **无需修改**：
- `App.kt:11` — `import cn.devcxl.photosync.wrapper.RawWrapper` ✓
- `MainActivity.kt:43` — `import cn.devcxl.photosync.wrapper.RawWrapper` ✓
- `RawLibIntegrationTest.kt:5` — `import cn.devcxl.photosync.wrapper.RawWrapper` ✓

Gradle 的模块依赖（`implementation project(':libraw')`）会自动将 `:libraw` 模块的类暴露给 `:app`。

- [ ] **Step 5: 提交**

```bash
git add -A app/src/main/cpp/ app/src/main/java/cn/devcxl/photosync/wrapper/
git commit -m "refactor(app): remove legacy prebuilt native code and old RawWrapper"
```

---

## Task 8: 编译验证

**Files:** 无新建/修改文件，纯验证

- [ ] **Step 1: 编译 `:libraw` 模块**

```bash
./gradlew :libraw:assembleDebug 2>&1
```

预期结果：BUILD SUCCESSFUL。如果失败，根据错误信息调整。

常见潜在问题及对策：
| 错误 | 原因 | 修复 |
|------|------|------|
| `lcms2` cmake configure 失败 | Little-CMS CMake 与 NDK 不兼容 | 降级方案：移除 `add_subdirectory`，改为手动 `file(GLOB ...)` 列出 `src/*.c` 编译 |
| LibRaw 源文件编译报 `#include` 找不到 | `target_include_directories` 路径不正确 | 检查 LibRaw submodule 中头文件的实际路径结构 |
| 链接错误：未定义符号 | 缺少 `USE_LCMS2` 定义或 lcms2 未链接 | 确认 `target_compile_definitions` 和 `target_link_libraries` |

- [ ] **Step 2: 编译整个项目**

```bash
./gradlew assembleDebug 2>&1
```

预期结果：BUILD SUCCESSFUL。`:app` 应能解析 `RawWrapper` 类并正常编译。

- [ ] **Step 3: 检查输出的 .so 文件**

```bash
find libraw/build -name "*.so" -type f 2>/dev/null
# 应看到类似：
# libraw/build/.../arm64-v8a/libraw_wrapper.so
# libraw/build/.../armeabi-v7a/libraw_wrapper.so
```

确认两个 ABI 均有产出。

- [ ] **Step 4: 提交（如有修复）**

如果 Step 1-3 过程中进行了修复，统一提交：
```bash
git add -A
git commit -m "fix(libraw): resolve build issues from source compilation"
```

---

## Task 9: 运行测试

**Files:** 无新建/修改文件，纯验证

- [ ] **Step 1: 运行单元测试**

```bash
./gradlew testDebugUnitTest 2>&1
```

预期结果：所有测试 PASS。`RawWrapper` 的使用者（`App.kt`、`MainActivity.kt`）应能通过编译。

注意：单元测试中的 `RawWrapper` 调用可能因为 JVM 环境无 native 库而 skip/fail — 这是预期行为（native 代码需要在 Android 设备/模拟器上测试）。

- [ ] **Step 2: 运行 lint 检查**

```bash
./gradlew lintDebug 2>&1
```

预期结果：无新增 error。

- [ ] **Step 3: 确认集成测试定义未被破坏**

```bash
# 检查 RawLibIntegrationTest 能通过编译
./gradlew :app:compileDebugAndroidTestKotlin 2>&1
```

预期结果：BUILD SUCCESSFUL。（实际运行 `connectedAndroidTest` 需要设备/模拟器，此处仅验证编译。）

---

## 风险与测试映射

| 风险 | 概率 | 影响 | 测试/验证 |
|------|------|------|-----------|
| lcms2 CMake 与 NDK 交叉编译不兼容 | 低 | 阻塞 | Task 8 Step 1；降级方案在 CMakeLists.txt 注释中说明 |
| LibRaw `*_ph.cpp` 排除不完整 | 低 | 编译错误 | Task 4 Step 2 验证 GLOB 结果 |
| `System.loadLibrary` 加载时机/顺序问题 | 低 | 运行时崩溃 | Task 9 Step 3 + 设备上 `connectedAndroidTest` |
| armeabi-v7a 下 LibRaw 编译失败（32 位特有问题） | 低 | 部分阻塞 | Task 8 Step 3 检查两个 ABI 产出 |
| 包名变更导致 JNI 签名不匹配 | 无 | 崩溃 | Task 5 Step 4 验证一致性；设计已确保包名不变 |
| `RawWrapper` 在 app 中不可见 | 无 | 编译失败 | Task 8 Step 2 验证整体编译 |
