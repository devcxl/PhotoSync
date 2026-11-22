cmake_minimum_required(VERSION 3.22.1)
project("raw_wrapper" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LIBRAW_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/LibRaw)
set(LCMS2_ROOT  ${CMAKE_CURRENT_SOURCE_DIR}/third_party/Little-CMS)
set(LJT_ROOT    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo)

file(GLOB LCMS2_SOURCES "${LCMS2_ROOT}/src/*.c")
add_library(lcms2_static STATIC ${LCMS2_SOURCES})

target_include_directories(lcms2_static PUBLIC
    ${LCMS2_ROOT}/include/
)
target_include_directories(lcms2_static PRIVATE
    ${LCMS2_ROOT}/src/
)

target_compile_options(lcms2_static PRIVATE -O3)

file(GLOB_RECURSE LIBRAW_SOURCES "${LIBRAW_ROOT}/src/*.cpp")
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
    LIBRAW_NODLL
    USE_LCMS2
)
target_compile_options(libraw_static PRIVATE -O3 -fopenmp)
target_link_libraries(libraw_static PRIVATE lcms2_static)

# --- libjpeg-turbo (static, ARM NEON SIMD) ------------------------------------------------
# Upstream refuses add_subdirectory() integration (CMakeLists.txt:69-71), so the encoder is
# built as an ExternalProject and linked through an IMPORTED static library. Only the encoder
# members are pulled in (+333 KiB on arm64), and -Wl,--exclude-libs,ALL keeps the 45 jpeg*
# symbols hidden so they can never bind against the platform's libjpeg.so.
include(ExternalProject)

set(LJT_BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-build)
set(LJT_LIBRARY   ${LJT_BUILD_DIR}/libjpeg.a)

ExternalProject_Add(libjpeg_turbo_ep
    SOURCE_DIR ${LJT_ROOT}
    BINARY_DIR ${LJT_BUILD_DIR}
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_STL=${ANDROID_STL}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DENABLE_SHARED=0
        -DENABLE_STATIC=1
        -DWITH_SIMD=1
        -DWITH_TURBOJPEG=0
        -DWITH_TOOLS=0
        -DWITH_TESTS=0
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target jpeg-static
    BUILD_BYPRODUCTS ${LJT_LIBRARY}
    INSTALL_COMMAND ""
    BUILD_ALWAYS OFF
)

add_library(jpeg_static STATIC IMPORTED GLOBAL)
set_target_properties(jpeg_static PROPERTIES IMPORTED_LOCATION ${LJT_LIBRARY})
add_dependencies(jpeg_static libjpeg_turbo_ep)
# Subdirectory include_directories() do not propagate: public headers live in src/, generated
# configuration headers (jconfig.h / jconfigint.h / jversion.h) in the build directory.
target_include_directories(jpeg_static INTERFACE ${LJT_ROOT}/src ${LJT_BUILD_DIR})

add_library(${CMAKE_PROJECT_NAME} SHARED
    jpeg_stream_writer.cpp
    jpeg_stream_writer_jni.cpp
    libraw_jni.cpp
    native_rgb16.cpp
    raw_autobright.cpp
    raw_exif_jni.cpp
    raw_linear16.cpp
    raw_linear16_jni.cpp
    raw_postprocess.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${LIBRAW_ROOT}/libraw/
)
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    LIBRAW_NODLL
)
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -O3 -fopenmp)
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE -fopenmp -static-openmp -Wl,--exclude-libs,ALL)

find_library(log_lib log)
find_library(android_lib android)
find_library(jnigraphics_lib jnigraphics)

target_link_libraries(${CMAKE_PROJECT_NAME}
    libraw_static
    lcms2_static
    jpeg_static
    ${log_lib}
    ${android_lib}
    ${jnigraphics_lib}
)
