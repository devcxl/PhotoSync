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
set(LIBJPEG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg)

# ── 1. 编译 lcms2 为静态库 ────────────────────────────────────────────────────
file(GLOB LCMS2_SOURCES "${LCMS2_ROOT}/src/*.c")

add_library(lcms2_static STATIC ${LCMS2_SOURCES})

target_include_directories(lcms2_static PUBLIC
    ${LCMS2_ROOT}/include/
)

target_include_directories(lcms2_static PRIVATE
    ${LCMS2_ROOT}/src/
)

# ── 2. 编译 libjpeg (IJG-compatible) 为静态库 ─────────────────────────────────
# 排除被其他.c文件 #include 的文件，以及工具/测试文件
file(GLOB LIBJPEG_SOURCES "${LIBJPEG_ROOT}/*.c")
# Include-only files (not standalone translatable units)
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jccolext.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jdcolext.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jdmrgext.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jdcol565.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jdmrg565.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jstdhuff.*")
# Utility programs / tests / turbojpeg wrapper
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*turbojpeg.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jmemdos.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jmemmac.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*cjpeg.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*djpeg.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*jpegtran.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*rdjpgcom.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*wrjpgcom.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*tjbench.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*tjexample.*")
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*tj.*")

add_library(libjpeg_static STATIC ${LIBJPEG_SOURCES})

target_include_directories(libjpeg_static PUBLIC
    ${LIBJPEG_ROOT}/
)

target_compile_definitions(libjpeg_static PRIVATE
    WITH_SIMD=0
)

# ── 3. 编译 LibRaw 为静态库 ───────────────────────────────────────────────────
file(GLOB_RECURSE LIBRAW_SOURCES "${LIBRAW_ROOT}/src/*.cpp")

list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

add_library(libraw_static STATIC ${LIBRAW_SOURCES})

target_include_directories(libraw_static PUBLIC
    ${LIBRAW_ROOT}/
    ${LIBRAW_ROOT}/libraw/
)

target_include_directories(libraw_static PRIVATE
    ${LCMS2_ROOT}/include/
    ${LIBJPEG_ROOT}/
)

target_compile_definitions(libraw_static PRIVATE
    LIBRAW_NODLL
    LIBRAW_NOTHREADS
    USE_LCMS2
    USE_JPEG
)

target_link_libraries(libraw_static PRIVATE lcms2_static libjpeg_static)

# ── 4. 构建 JNI wrapper shared library ───────────────────────────────────────
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
    libjpeg_static
    ${log_lib}
    ${android_lib}
)
