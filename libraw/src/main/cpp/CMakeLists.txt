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
# Little-CMS 不包含 CMakeLists.txt（使用 meson/autotools），因此手动编译源文件。
file(GLOB LCMS2_SOURCES "${LCMS2_ROOT}/src/*.c")

add_library(lcms2_static STATIC ${LCMS2_SOURCES})

target_include_directories(lcms2_static PUBLIC
    ${LCMS2_ROOT}/include/
)

# lcms2 源码中 lcms2_internal.h 位于 src/ 目录，需要添加到搜索路径
target_include_directories(lcms2_static PRIVATE
    ${LCMS2_ROOT}/src/
)

# ── 2. 编译 LibRaw 为静态库 ───────────────────────────────────────────────────
# LibRaw 主仓库不含 CMakeLists.txt，手动收集源文件编译
file(GLOB_RECURSE LIBRAW_SOURCES "${LIBRAW_ROOT}/src/*.cpp")

# 排除 *_ph.cpp 占位/桩文件（这些文件是空占位符，编译会导致符号冲突）
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

add_library(libraw_static STATIC ${LIBRAW_SOURCES})

target_include_directories(libraw_static PUBLIC
    ${LIBRAW_ROOT}/
    ${LIBRAW_ROOT}/libraw/
)

target_include_directories(libraw_static PRIVATE
    ${LCMS2_ROOT}/include/
)

target_compile_definitions(libraw_static PRIVATE
    LIBRAW_NODLL          # 静态链接标记（现有配置等价）
    LIBRAW_NOTHREADS      # 单线程模式
    USE_LCMS2             # 启用 lcms2 色彩管理支持
)

# 链接 lcms2 静态库到 libraw
target_link_libraries(libraw_static PRIVATE lcms2_static)

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
    lcms2_static
    ${log_lib}
    ${android_lib}
)
