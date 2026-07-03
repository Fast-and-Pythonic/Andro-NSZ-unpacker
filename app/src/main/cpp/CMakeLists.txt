cmake_minimum_required(VERSION 3.22)
project(AndroNSZ C)

# ── zstd: static decompressor-only build ─────────────────────────────────────
include(FetchContent)

set(ZSTD_FETCH_OPTIONS
    URL      https://github.com/facebook/zstd/releases/download/v1.5.5/zstd-1.5.5.tar.gz
    URL_HASH SHA256=9c4396cc829cfae319a6e2615202e82aad41372073482fce286fac78646d3ee4
    SOURCE_SUBDIR build/cmake
)

if(CMAKE_VERSION VERSION_GREATER_EQUAL 3.24)
    list(APPEND ZSTD_FETCH_OPTIONS DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
endif()

FetchContent_Declare(
    zstd
    ${ZSTD_FETCH_OPTIONS}
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
    nca_cnmt.c
)

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_sources(${CMAKE_PROJECT_NAME} PRIVATE jni_bridge.c)
endif()

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
    $<$<C_COMPILER_ID:Clang,AppleClang>:-flto=thin>
    # ARM64: hardware AES via AESE/AESMC intrinsics
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+crypto>
    # Only decompression needed from zstd
    -DZSTD_LIB_COMPRESSION=0
    -DZSTD_LIB_DICTBUILDER=0
)

# LTO must be passed to the linker as well, or the bitcode objects won't be
# optimized/codegen'd at link time.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
    $<$<C_COMPILER_ID:Clang,AppleClang>:-flto=thin>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${zstd_SOURCE_DIR}/lib
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    libzstd_static
)

# Android NDK provides liblog; host-only configure runs do not.
if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    find_library(LOG_LIB log REQUIRED)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${LOG_LIB})
endif()

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
    add_executable(andro_nsz_cli
        tools/andro_nsz_cli.c
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
        nca_cnmt.c
    )
    target_compile_options(andro_nsz_cli PRIVATE
        -O3
        -ffast-math
        -DZSTD_LIB_COMPRESSION=0
        -DZSTD_LIB_DICTBUILDER=0
    )
    target_include_directories(andro_nsz_cli PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${zstd_SOURCE_DIR}/lib
    )
    target_link_libraries(andro_nsz_cli
        libzstd_static
    )
endif()
