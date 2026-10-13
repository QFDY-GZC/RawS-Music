# =============================================================================
# libdst - DST decoder library (FFmpeg-based implementation)
# =============================================================================

# Source files (in src/)
set(LIBDST_SOURCES
    src/decoder.c
    src/decoder_batch.c
)

# Public headers (in include/libdst/)
set(LIBDST_PUBLIC_HEADERS
    include/libdst/decoder.h
    include/libdst/decoder_batch.h
    include/libdst/dst_export.h
)

# Create OBJECT library (combined into umbrella libdsd)
add_library(libdst OBJECT
    ${LIBDST_SOURCES}
    ${LIBDST_PUBLIC_HEADERS}
)

# Set include directories for this library
target_include_directories(libdst
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)

# Output directories handled by umbrella library (libdsd)

# Link libraries (PUBLIC so consumers of libdst also link against sautil)
target_link_libraries(libdst PUBLIC sautil)


# Export macros and installation handled by umbrella library (libdsd)


# Export macro control (for dst_export.h)
# Note: USING_DLL is set on the umbrella library's INTERFACE, not here.
if(BUILD_SHARED_LIBS)
    target_compile_definitions(libdst PRIVATE DST_BUILDING_DLL)
endif()
