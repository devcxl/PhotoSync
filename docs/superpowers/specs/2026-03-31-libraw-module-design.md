# LibRaw 独立模块化设计

**日期**: 2026-03-31
**状态**: 已确认

## 背景

当前 PhotoSync 项目通过预编译的 `.so` 文件集成 LibRaw 和 lcms2，仅支持 `arm64-v8a` 架构。需要将其改为源码编译，以支持多 ABI（`arm64-v8a` + `armeabi-v7a`），并以独立 Gradle 模块的形式组织，提升可维护性。

### 现状

- LibRaw 0.21.4 + lcms2 预编译 `.so` 位于 `app/src/main/cpp/third_party/`
- 仅 `arm64-v8a` 一个 ABI
- `app/build.gradle` 中 `ndk { abiFilters "arm64-v8a" }` 写死
- JNI 桥接: `libraw_jni.cpp` + `RawWrapper.kt` 在 app 模块内

## 设计方案

### 目标

1. 创建独立 `:libraw` Gradle Library 模块
2. 通过 git submodule 引入 LibRaw + lcms2 源码
3. 自行编写 CMakeLists.txt 从源码编译为静态库
4. 支持 `arm64-v8a` + `armeabi-v7a`
5. 迁移 JNI 代码和 Kotlin wrapper 到新模块

### 目录结构

```
PhotoSync/
├── app/
│   ├── build.gradle              # 依赖 :libraw，移除 externalNativeBuild
│   └── src/main/
│       └── java/...              # RawWrapper.kt 已移除
│
├── libraw/                        # 新 Gradle Library 模块
│   ├── build.gradle               # com.android.library + externalNativeBuild
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt     # 编译 lcms2 + libraw + JNI wrapper
│   │   │   ├── libraw_jni.cpp     # 从 app 迁移
│   │   │   └── third_party/
│   │   │       ├── LibRaw/        # git submodule (tag 0.21.4)
│   │   │       └── Little-CMS/    # git submodule (最新稳定)
│   │   └── java/cn/devcxl/photosync/wrapper/
│   │       └── RawWrapper.kt      # 从 app 迁移
│   └── .gitignore
│
├── settings.gradle                # include ':libraw'
└── ...
```

### CMake 构建策略

自行编写 CMakeLists.txt 编译 LibRaw 源码。**原因**：LibRaw 主仓库（0.21.4）不包含 CMakeLists.txt，社区维护的 `LibRaw/LibRaw-cmake` 仓库活跃度低且标注为 unmaintained，不宜依赖。LibRaw 源码结构简单（单层 src/ 目录下的 .cpp 文件），自行编写 CMake 成本低且可控。

核心逻辑：

1. **lcms2**: 通过 `add_subdirectory(third_party/Little-CMS)` 编译，官方仓库自带 CMakeLists.txt。若 NDK 交叉编译出现兼容性问题（如 CMake module 路径、系统头文件等），降级方案：手动列出 lcms2 的 `src/*.c` 文件编译为静态库，绕过其 CMake 配置。
2. **LibRaw**: `file(GLOB_RECURSE ... "src/*.cpp")` 排除 `*_ph.cpp` 占位文件，编译为静态库
3. **JNI wrapper**: `add_library(raw_wrapper SHARED libraw_jni.cpp)` 链接上述两个静态库

关键编译定义（与现有 `app/src/main/cpp/CMakeLists.txt` 等价）：
- `LIBRAW_NOTHREADS` - 单线程模式（现有配置在 LibRaw-cmake 中也使用此定义）
- `LIBRAW_NODLL` - 静态链接标记（现有 CMakeLists.txt 的 `target_compile_definitions` 已定义此宏）

最终产出单个 `libraw_wrapper.so`，内部静态链接 libraw + lcms2。

### Git Submodule

| 仓库 | 模块内路径 | 固定版本 |
|------|-----------|---------|
| `LibRaw/LibRaw` | `libraw/src/main/cpp/third_party/LibRaw` | tag `0.21.4` |
| `mm2/Little-CMS` | `libraw/src/main/cpp/third_party/Little-CMS` | 最新稳定 tag |

### app 模块变更

- `build.gradle`: 添加 `implementation project(':libraw')`，删除 `externalNativeBuild` 和 `apply plugin: 'kotlin-kapt'`（如 app 不再有 native 代码）
- 删除 `ndk.abiFilters`（由 `:libraw` 模块控制）
- 删除 `app/src/main/cpp/` 整个目录（CMakeLists.txt + libraw_jni.cpp + third_party/）
- 删除 `app/.../wrapper/RawWrapper.kt`（已迁移到 `:libraw`）

### :libraw 模块 Gradle 配置

```groovy
android {
    namespace "cn.devcxl.photosync.libraw"
    compileSdk 36
    defaultConfig {
        minSdk 24
        ndk { abiFilters "arm64-v8a", "armeabi-v7a" }
        externalNativeBuild {
            cmake { cppFlags "-std=c++17" }
        }
    }
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1"
        }
    }
}
```

### 风险

| 风险 | 应对 |
|------|------|
| lcms2 CMake 对 Android NDK 交叉编译兼容性 | 官方 CMake 较成熟，风险低；降级方案：手写 `src/*.c` 文件列表编译静态库，触发条件为 cmake configure 阶段报错 |
| armeabi-v7a 下 LibRaw 性能差 | 可接受，32 位设备本身性能有限 |
| 首次编译时间增加 | 增量编译后影响不大 |

## 实施步骤

1. 创建 `:libraw` 模块骨架（build.gradle, 目录结构）
2. 添加 git submodule（LibRaw, Little-CMS）
3. 审计现有 `app/src/main/cpp/CMakeLists.txt` 中的编译定义和宏，确保迁移后配置等价
4. 编写 `libraw/src/main/cpp/CMakeLists.txt`
5. 迁移 `libraw_jni.cpp` + `RawWrapper.kt`
6. 修改 `app/build.gradle`，删除旧 native 配置
7. 清理 `app/src/main/cpp/` 旧文件
8. 编译验证 `./gradlew :libraw:assembleDebug`
9. 运行单元测试确认 RawWrapper 调用链不变
