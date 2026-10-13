cmake_minimum_required(VERSION 3.15)

project(sautil C)

###########################################################
# Platform and Architecture Detection
###########################################################

# Determine platform
if(WIN32)
    if(MSVC)
        set(PLATFORM_NAME "win-msvc")
    else()
        set(PLATFORM_NAME "win")
    endif()
elseif(APPLE)
    if(IOS)
        set(PLATFORM_NAME "ios")
    else()
        set(PLATFORM_NAME "mac")
    endif()
elseif(ANDROID)
    set(PLATFORM_NAME "android")
elseif(UNIX)
    set(PLATFORM_NAME "linux")
endif()

# Determine architecture
# For macOS universal builds (arm64;x86_64), use the universal config wrapper
# which dispatches to the correct arch-specific config at compile time.
if(APPLE AND CMAKE_OSX_ARCHITECTURES MATCHES "arm64" AND CMAKE_OSX_ARCHITECTURES MATCHES "x86_64")
    set(ARCH_NAME "universal")
    set(ARCH_UNIVERSAL 1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|ARM64|arm64")
    set(ARCH_NAME "arm64")
    set(ARCH_AARCH64 1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "armv7|ARM")
    # Check for NEON support
    if(ENABLE_NEON OR CMAKE_CXX_FLAGS MATCHES "neon" OR CMAKE_C_FLAGS MATCHES "neon")
        set(ARCH_NAME "arm-neon")
    else()
        set(ARCH_NAME "arm")
    endif()
    set(ARCH_ARM 1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64|x64")
    set(ARCH_NAME "x64")
    set(ARCH_X86_64 1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "i686|i386|x86")
    set(ARCH_NAME "ia32")
    set(ARCH_X86_32 1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "mips64")
    set(ARCH_NAME "mips64el")
    set(ARCH_MIPS64 1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "mips")
    set(ARCH_NAME "mipsel")
    set(ARCH_MIPS 1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "riscv64")
    set(ARCH_NAME "riscv64")
    set(ARCH_RISCV 1)
else()
    # Default to x64 if unknown
    set(ARCH_NAME "x64")
    set(ARCH_X86_64 1)
endif()

# Check for no-asm variant (can be set via -DUSE_NOASM=ON)
if(USE_NOASM AND PLATFORM_NAME STREQUAL "linux")
    set(PLATFORM_NAME "linux-noasm")
endif()

# Construct config path
set(CONFIG_PATH "${CMAKE_CURRENT_SOURCE_DIR}/configs/${PLATFORM_NAME}/${ARCH_NAME}")

# Fallback: if specific config doesn't exist, try without arch subdirectory
if(NOT EXISTS "${CONFIG_PATH}/config.h")
    set(CONFIG_PATH "${CMAKE_CURRENT_SOURCE_DIR}/configs/${PLATFORM_NAME}")
endif()

# Export CONFIG_PATH as a cache variable so other targets can use it
set(SAUTIL_CONFIG_PATH "${CONFIG_PATH}" CACHE PATH "Path to libsautil config directory" FORCE)

message(STATUS "libsautil: Platform=${PLATFORM_NAME}, Arch=${ARCH_NAME}")
message(STATUS "libsautil: Config path=${CONFIG_PATH}")

###########################################################
# Common Source Files
###########################################################

set(SAUTIL_SOURCES
    # Core utility files
    base64.c
    bprint.c
    buffer.c
    cpu.c
    getopt.c
    intmath.c
    log.c
    log2_tab.c
    mem.c
    reverse.c
    sastring.c
    sa_path.c
    sa_tpool.c
    snprintf.c
    sxmlc.c
    time.c
    tree.c
    win_utf8_io.c
)

set(SAUTIL_HEADERS
    attributes.h
    base64.h
    bprint.h
    bswap.h
    buffer.h
    buffer_internal.h
    common.h
    compat.h
    cpu.h
    dynarray.h
    error.h
    export.h
    getopt.h
    get_bits.h
    internal.h
    intmath.h
    intreadwrite.h
    ll.h
    log.h
    macros.h
    mathops.h
    mem.h
    mem_internal.h
    reverse.h
    sastring.h
    sa_assert.h
    sa_path.h
    sa_tpool.h
    sa_tpool_internal.h
    sxmlc.h
    time.h
    timer.h
    time_internal.h
    tree.h
    va_copy.h
    win_utf8_io.h
    c11threads.h
    msvc/stdatomic.h
)

###########################################################
# Architecture-Specific Source Files
###########################################################

# x86/x86_64 specific files
#if(ARCH_X86_64 OR ARCH_X86_32)
#    list(APPEND SAUTIL_SOURCES
#        x86/cpu.c
#    )
#    list(APPEND SAUTIL_HEADERS
#        x86/cpu.h
#    )
#endif()

# Architecture-specific CPU detection files (optional, only included if present)
if(ARCH_ARM AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/arm/cpu.c")
    list(APPEND SAUTIL_SOURCES arm/cpu.c)
endif()

if(ARCH_AARCH64 AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/aarch64/cpu.c")
    list(APPEND SAUTIL_SOURCES aarch64/cpu.c)
endif()

if((ARCH_MIPS OR ARCH_MIPS64) AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/mips/cpu.c")
    list(APPEND SAUTIL_SOURCES mips/cpu.c)
endif()

if(ARCH_RISCV AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/riscv/cpu.c")
    list(APPEND SAUTIL_SOURCES riscv/cpu.c)
endif()

###########################################################
# Platform-Specific Compatibility Files
###########################################################

# Windows MSVC compatibility
if(WIN32 AND MSVC)
    list(APPEND SAUTIL_SOURCES
        win_utf8_io.c
    )
endif()

# Add getopt if needed (typically for Windows)
if(WIN32)
    list(APPEND SAUTIL_SOURCES
        getopt.c
    )
endif()

###########################################################
# Create Library Target
###########################################################

add_library(sautil OBJECT ${SAUTIL_SOURCES} ${SAUTIL_HEADERS})

# Per-file compile flags for third-party code
if(NOT MSVC)
    # sxmlc.c: Suppress const-qualifier warnings (third-party code with intentional design)
    # GCC uses -Wno-discarded-qualifiers, Clang uses -Wno-incompatible-pointer-types-discards-qualifiers
    if(APPLE)
        set_source_files_properties(sxmlc.c PROPERTIES
            COMPILE_FLAGS "-Wno-incompatible-pointer-types-discards-qualifiers"
        )
    else()
        set_source_files_properties(sxmlc.c PROPERTIES
            COMPILE_FLAGS "-Wno-discarded-qualifiers"
        )
    endif()
endif()

###########################################################
# Include Directories
###########################################################

target_include_directories(sautil PUBLIC
    # Architecture-specific config directory (highest priority)
    ${CONFIG_PATH}

    # Parent directory (for libsautil/ prefixed includes)
    ${CMAKE_CURRENT_SOURCE_DIR}/..
)

# MSVC compat shim for <stdatomic.h> (must come before system includes)
# This provides C11 atomics on MSVC without /experimental:c11atomics
if(WIN32 AND MSVC)
    target_include_directories(sautil BEFORE PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/msvc
    )
endif()

# Use SYSTEM to add libsautil dir with lower priority than system headers
# This prevents libsautil/time.h from shadowing system <time.h>
target_include_directories(sautil SYSTEM PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

###########################################################
# Compiler Definitions
###########################################################

# Define architecture flags
# For universal builds, the config.h wrapper handles ARCH_* per compilation unit
if(ARCH_UNIVERSAL)
    # No ARCH_* definitions needed -- config.h dispatches per-arch at compile time
elseif(ARCH_X86_64)
    target_compile_definitions(sautil PRIVATE ARCH_X86_64=1 ARCH_X86=1)
elseif(ARCH_X86_32)
    target_compile_definitions(sautil PRIVATE ARCH_X86_32=1 ARCH_X86=1)
elseif(ARCH_AARCH64)
    target_compile_definitions(sautil PRIVATE ARCH_AARCH64=1)
elseif(ARCH_ARM)
    target_compile_definitions(sautil PRIVATE ARCH_ARM=1)
elseif(ARCH_MIPS64)
    target_compile_definitions(sautil PRIVATE ARCH_MIPS64=1 ARCH_MIPS=1)
elseif(ARCH_MIPS)
    target_compile_definitions(sautil PRIVATE ARCH_MIPS=1)
elseif(ARCH_RISCV)
    target_compile_definitions(sautil PRIVATE ARCH_RISCV=1)
endif()

# Common definitions
target_compile_definitions(sautil PRIVATE
    HAVE_SA_CONFIG_H  # Enable config.h inclusion in headers
)

# FFmpeg-style POSIX and feature test macros
if(APPLE)
    target_compile_definitions(sautil PRIVATE
        _FILE_OFFSET_BITS=64    # Large file support
        PIC                     # Position-independent code
    )
elseif(UNIX)
    target_compile_definitions(sautil PRIVATE
        _ISOC11_SOURCE          # ISO C11 features
        _FILE_OFFSET_BITS=64    # Large file support
        _LARGEFILE_SOURCE       # Large file support
        _POSIX_C_SOURCE=200112  # POSIX.1-2001 features
        _XOPEN_SOURCE=600       # X/Open 6 features
        PIC                     # Position-independent code
    )
endif()

# SACD_API export control moved to umbrella library (libdsd) in root CMakeLists.txt

# Platform-specific definitions
if(WIN32)
    target_compile_definitions(sautil PRIVATE
        _CRT_SECURE_NO_WARNINGS
        _CRT_NONSTDC_NO_WARNINGS  # Suppress POSIX function name deprecation warnings
        _CRT_DECLARE_NONSTDC_NAMES=1  # Expose POSIX function names
    )
    # Ensure time_t and struct timespec are available
    # _CRT_NO_TIME_T should NOT be defined (we don't define it, just being explicit)
endif()

###########################################################
# Compiler Options
###########################################################

# C17 standard to match FFmpeg (fallback to C11 if not available)
set_target_properties(sautil PROPERTIES
    C_STANDARD 17
    C_STANDARD_REQUIRED OFF
    C_EXTENSIONS ON
)

# Platform-specific compiler flags
if(MSVC)
    # Find Windows SDK time.h and force include it first
    file(GLOB_RECURSE SYSTEM_TIME_H "C:/Program Files (x86)/Windows Kits/*/Include/*/ucrt/time.h")
    list(GET SYSTEM_TIME_H 0 SYSTEM_TIME_H_PATH)
    if(SYSTEM_TIME_H_PATH)
        message(STATUS "Found system time.h: ${SYSTEM_TIME_H_PATH}")
        target_compile_options(sautil PRIVATE
            /W3
            /std:c17
            /FI"${SYSTEM_TIME_H_PATH}"  # Force include system time.h first
            /external:W0  # Suppress warnings from external headers
            /wd4018  # Suppress specific warnings common in FFmpeg code
            /wd4244
            /wd4267
        )
    else()
        target_compile_options(sautil PRIVATE
            /W3
            /std:c17
            /external:W0  # Suppress warnings from external headers
            /wd4018  # Suppress specific warnings common in FFmpeg code
            /wd4244
            /wd4267
        )
    endif()
else()
    # Force include system time.h first to prevent local time.h from shadowing it
    if(APPLE)
        # macOS: system headers are inside the SDK, find the path via xcrun
        execute_process(
            COMMAND xcrun --show-sdk-path
            OUTPUT_VARIABLE _macos_sdk_path
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET
        )
        if(_macos_sdk_path)
            target_compile_options(sautil PRIVATE -include "${_macos_sdk_path}/usr/include/time.h")
        else()
            target_compile_options(sautil PRIVATE -include time.h)
        endif()
    else()
        target_compile_options(sautil PRIVATE -include /usr/include/time.h)
    endif()

    # FFmpeg-style GCC/Clang flags
    target_compile_options(sautil PRIVATE
        # Basic flags
        -fomit-frame-pointer
        -fPIC
        -pthread

        # Warning flags (enabled)
        -Wall
        -Wdisabled-optimization
        -Wpointer-arith
        -Wredundant-decls
        -Wwrite-strings
        -Wtype-limits
        -Wundef
        -Wempty-body
        -Wmissing-prototypes
        -Wstrict-prototypes
        -Wformat
        -fdiagnostics-color=auto

        # Warning flags (disabled for FFmpeg compatibility)
        -Wno-parentheses
        -Wno-switch
        -Wno-format-zero-length
        -Wno-pointer-sign
        -Wno-unused-const-variable
        -Wno-char-subscripts

        # Warnings as errors (critical issues)
        -Werror=implicit-function-declaration
        -Werror=missing-prototypes
        -Werror=format-security
        -Werror=return-type
        -Werror=vla
    )

    # GCC-only warning flags (not supported by Apple Clang)
    if(NOT APPLE)
        target_compile_options(sautil PRIVATE -Wno-bool-operation -Wno-maybe-uninitialized)
    endif()

    # Optimization flags for Release builds
    target_compile_options(sautil PRIVATE
        $<$<CONFIG:Release>:-O3>
        $<$<CONFIG:Release>:-fno-math-errno>
        $<$<CONFIG:Release>:-fno-signed-zeros>
        $<$<CONFIG:Release>:-fno-tree-vectorize>
    )
endif()

# Output directories handled by umbrella library (libdsd)

###########################################################
# Installation (Optional)
###########################################################

# Uncomment if you want to install the library
# install(TARGETS sautil
#     ARCHIVE DESTINATION lib
#     LIBRARY DESTINATION lib
# )
#
# install(FILES ${SAUTIL_HEADERS}
#     DESTINATION include/libsautil
# )

###########################################################
# Debug Information
###########################################################

message(STATUS "libsautil: Source files count: ${CMAKE_CURRENT_LIST_LENGTH}")
message(STATUS "libsautil: Config exists: ${CONFIG_PATH}/config.h")
