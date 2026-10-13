# =============================================================================
# libsacd - SACD library
# =============================================================================

# Source files (in src/)
set(LIBSACD_BASE_SOURCES
    src/sacd_charset.c
    src/sacd_genre_tables.c
    src/sacd_dst_reader.c
    src/sacd.c
    src/sacd_helpers.c
    src/sacd_dsd_reader.c
    src/sacd_area_toc.c
    src/sacd_master_toc.c
    src/sacd_input.c
    src/sacd_input_file.c
)
set(LIBSACD_SOURCES ${LIBSACD_BASE_SOURCES})

if(BUILD_PS3DRIVE)
    list(APPEND LIBSACD_SOURCES
        src/sacd_input_ps3drive.c
        src/sacd_input_network.c
        src/sacd_pb_stream.c
        proto/sacd_ripper.pb.c
    )
endif()

# Public headers (in include/sacd/)
set(LIBSACD_PUBLIC_HEADERS
    include/libsacd/sacd.h
)

# Private headers (in src/)
set(LIBSACD_PRIVATE_HEADERS
    src/sacd_input.h
    src/sacd_specification.h
    src/sacd_sector_reader.h
    src/sacd_charset.h
    src/sacd_master_toc.h
    src/sacd_dsd_reader.h
    src/sacd_dst_reader.h
    src/sacd_area_toc.h
    src/sacd_frame_reader.h
)

if(BUILD_PS3DRIVE)
    list(APPEND LIBSACD_PRIVATE_HEADERS
        src/sacd_pb_stream.h
        proto/sacd_ripper.pb.h
    )
endif()

# Create OBJECT library (combined into umbrella libdsd)
add_library(libsacd OBJECT
    ${LIBSACD_SOURCES}
    ${LIBSACD_PUBLIC_HEADERS}
    ${LIBSACD_PRIVATE_HEADERS}
)

# Set include directories for this library
target_include_directories(libsacd
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

if(BUILD_PS3DRIVE)
    target_include_directories(libsacd PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/proto
    )
endif()

# Output directories handled by umbrella library (libdsd)

# Link libraries (PUBLIC so consumers of libsacd also link against dependencies)
if(BUILD_PS3DRIVE)
    target_link_libraries(libsacd
        PUBLIC sautil libps3drive
        PRIVATE nanopb tinycsocket
    )
else()
    target_link_libraries(libsacd
        PUBLIC sautil
    )
    target_compile_definitions(libsacd PRIVATE SACD_NO_PS3DRIVE)
endif()

# Version configuration
configure_version_header(
    TARGET      libsacd
    PREFIX      LIBSACD_
    GUARD       LIBSACD_VERSION_H
    PRODUCT     "libsacd"
    VERSION     ${CMAKE_PROJECT_VERSION}
    OUTPUT_DIR  ${CMAKE_CURRENT_BINARY_DIR}/include/libsacd
)

# =============================================================================
# libsacd_lite - Standalone OBJECT target without PS3 drive support.
# Used by sacd-vfs which reads ISO files only and never needs PS3 drive code.
# =============================================================================

add_library(libsacd_lite OBJECT
    ${LIBSACD_BASE_SOURCES}
    ${LIBSACD_PUBLIC_HEADERS}
    ${LIBSACD_PRIVATE_HEADERS}
)

target_include_directories(libsacd_lite
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(libsacd_lite PUBLIC sautil)
target_compile_definitions(libsacd_lite PRIVATE SACD_NO_PS3DRIVE)

# Share the same version header as libsacd
target_include_directories(libsacd_lite PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_BINARY_DIR}/include>
)

# Export macros and installation handled by umbrella library (libdsd)
