cmake_minimum_required(VERSION 3.22.1)
# Use a clean target name without leading 'lib' to avoid liblib* artifacts
project("raw_wrapper" LANGUAGES C CXX)

message("CMAKE_PROJECT_NAME: ${CMAKE_PROJECT_NAME}")
message("ANDROID_ABI: ${ANDROID_ABI}")
message("PROJECT_SOURCE_DIR: ${PROJECT_SOURCE_DIR}")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LIBRAW_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libraw)
set(LCMS2_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lcms2)

# Fail fast if the expected prebuilt .so for current ABI is missing
if(NOT EXISTS ${LIBRAW_ROOT}/libs/${ANDROID_ABI}/libraw.so)
    message(FATAL_ERROR "libraw prebuilt not found for ABI ${ANDROID_ABI} at ${LIBRAW_ROOT}/libs/${ANDROID_ABI}/libraw.so. Provide the .so or limit abiFilters.")
endif()

# Fail fast if the expected prebuilt .so for current ABI is missing
if(NOT EXISTS ${LCMS2_ROOT}/libs/${ANDROID_ABI}/liblcms2.so)
    message(FATAL_ERROR "liblcms2 prebuilt not found for ABI ${ANDROID_ABI} at ${LCMS2_ROOT}/libs/${ANDROID_ABI}/liblcms2.so. Provide the .so or limit abiFilters.")
endif()

# Prebuilt shared libraw per-ABI (.so) placed under third_party/libraw/libs/<ABI>/libraw.so
add_library(raw SHARED IMPORTED)
set_target_properties(raw PROPERTIES
    IMPORTED_LOCATION ${LIBRAW_ROOT}/libs/${ANDROID_ABI}/libraw.so
    INTERFACE_INCLUDE_DIRECTORIES ${LIBRAW_ROOT}/include
)
add_library(lcms2 SHARED IMPORTED)
set_target_properties(lcms2 PROPERTIES
    IMPORTED_LOCATION ${LCMS2_ROOT}/libs/${ANDROID_ABI}/liblcms2.so
    INTERFACE_INCLUDE_DIRECTORIES ${LCMS2_ROOT}/include
)

# Our JNI-facing wrapper that links against the imported libraw
add_library(${CMAKE_PROJECT_NAME} SHARED
    libraw_jni.cpp
)

find_library(cpp_shared_lib c++_shared)
find_library(log_lib log)
find_library(android_lib android)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${LIBRAW_ROOT}/include)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE LIBRAW_NODLL)

# Ensure we export all needed symbols even under LTO (optional):
# set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)

# Link
target_link_libraries(${CMAKE_PROJECT_NAME}
    raw
    lcms2
    ${log_lib}
    ${android_lib}
    ${cpp_shared_lib}
)
