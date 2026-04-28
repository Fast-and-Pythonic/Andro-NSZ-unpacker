cmake_minimum_required(VERSION 3.22)
project(AndroNSZ C)

# ── zstd: static decompressor-only build ─────────────────────────────────────
include(FetchContent)

FetchContent_Declare(
    zstd
    URL      https://github.com/facebook/zstd/releases/download/v1.5.5/zstd-1.5.5.tar.gz
    URL_HASH SHA256=9c4396cc829cfae319a6e2615202e82aad41372073482fce286fac78646d3ee4
    SOURCE_SUBDIR build/cmake
)

set(ZSTD_BUILD_PROGRAMS  OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED    OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC    ON  CACHE BOOL "" FORCE)
set(ZSTD_LEGACY_SUPPORT  OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_CONTRIB   OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(zstd)

# ── native engine library ────────────────────────────────────────────────────
add_library(${CMAKE_PROJECT_NAME} SHARED
    nsz_debug.c
    aes_ctr.c
    aes_xts.c
    sha256.c
    pfs0.c
    hfs0.c
    ncz.c
    ncz_decompress.c
    ncz_engine.c
    nca_verifier.c
    jni_bridge.c
)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    # ARM64: hardware AES via AESE/AESMC intrinsics
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+crypto>
    # Only decompression needed from zstd
    -DZSTD_LIB_COMPRESSION=0
    -DZSTD_LIB_DICTBUILDER=0
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${zstd_SOURCE_DIR}/lib
)

find_library(log-lib log)

target_link_libraries(${CMAKE_PROJECT_NAME}
    libzstd_static
    ${log-lib}
)
