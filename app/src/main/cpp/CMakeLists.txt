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

# In a debug build the zstd dependency inherits CMAKE_BUILD_TYPE=Debug, which
# compiles it at -O0 with -DDEBUGLEVEL=1 (internal asserts) — that cripples
# decompression throughput, the dominant cost on the conversion hot path.
# Force it to build fully optimized regardless of the app's build type so the
# debug APK decompresses at release speed. (-U then -D ensures DEBUGLEVEL wins.)
if(TARGET libzstd_static)
    target_compile_options(libzstd_static PRIVATE
        -O3 -DNDEBUG -UDEBUGLEVEL -DDEBUGLEVEL=0)
endif()

# ── native engine library ────────────────────────────────────────────────────
add_library(${CMAKE_PROJECT_NAME} SHARED
    nsz_debug.c
    aes_ctr.c
    aes_xts.c
    sha256.c
    pfs0.c
    hfs0.c
    ncz.c
    async_writer.c
    ncz_decompress.c
    ncz_engine.c
    nca_verifier.c
    jni_bridge.c
)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    # ThinLTO: the engine is split across ~11 translation units, so without
    # link-time optimization the compiler can't inline hot helpers across file
    # boundaries (e.g. the AES block step into the ncz decompress loop, or
    # sha256_update into the verifier). ThinLTO is parallel/incremental, so the
    # build-time cost is small. Modest gain — the hot path is dominated by
    # hardware crypto and zstd, which this can't touch — but low-risk.
    -flto=thin
    # ARM64: hardware AES via AESE/AESMC intrinsics
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+crypto>
    # Only decompression needed from zstd
    -DZSTD_LIB_COMPRESSION=0
    -DZSTD_LIB_DICTBUILDER=0
)

# LTO must be passed to the linker as well, or the bitcode objects won't be
# optimized/codegen'd at link time.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE -flto=thin)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${zstd_SOURCE_DIR}/lib
)

find_library(log-lib log)

target_link_libraries(${CMAKE_PROJECT_NAME}
    libzstd_static
    ${log-lib}
)
