cmake_minimum_required(VERSION 3.22.1)
project("raw_wrapper" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LIBRAW_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/LibRaw)
set(LCMS2_ROOT  ${CMAKE_CURRENT_SOURCE_DIR}/third_party/Little-CMS)

file(GLOB LCMS2_SOURCES "${LCMS2_ROOT}/src/*.c")
add_library(lcms2_static STATIC ${LCMS2_SOURCES})

target_include_directories(lcms2_static PUBLIC
    ${LCMS2_ROOT}/include/
)
target_include_directories(lcms2_static PRIVATE
    ${LCMS2_ROOT}/src/
)

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
    LIBRAW_NOTHREADS
    USE_LCMS2
)
target_link_libraries(libraw_static PRIVATE lcms2_static)

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
