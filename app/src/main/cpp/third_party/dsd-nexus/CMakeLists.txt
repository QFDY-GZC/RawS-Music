cmake_minimum_required(VERSION 3.18)
project(rawsmusic_sacd C)
set(SAUTIL ${CMAKE_CURRENT_SOURCE_DIR}/libsautil)
if(ANDROID)
    set(SACD_CONFIG ${SAUTIL}/configs/android/arm64)
    set(SACD_CHARSET ${CMAKE_CURRENT_SOURCE_DIR}/android_charset.c)
else()
    set(SACD_CONFIG ${SAUTIL}/configs/win/x64)
    set(SACD_CHARSET ${CMAKE_CURRENT_SOURCE_DIR}/libsacd/src/sacd_charset.c)
endif()
add_library(rawsmusic_sacd SHARED
    libsacd/src/sacd.c libsacd/src/sacd_master_toc.c libsacd/src/sacd_area_toc.c
    libsacd/src/sacd_input.c libsacd/src/sacd_input_file.c
    libsacd/src/sacd_dsd_reader.c libsacd/src/sacd_dst_reader.c
    libsacd/src/sacd_genre_tables.c libsacd/src/sacd_helpers.c
    ${SACD_CHARSET} libdst/src/decoder.c
    libsautil/mem.c libsautil/intmath.c libsautil/log2_tab.c libsautil/reverse.c
    libsautil/sastring.c libsautil/bprint.c libsautil/log.c libsautil/snprintf.c
    libsautil/win_utf8_io.c libsautil/time.c)
set_target_properties(rawsmusic_sacd PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
target_include_directories(rawsmusic_sacd PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/libsacd/include ${CMAKE_CURRENT_SOURCE_DIR}/libdst/include
    ${CMAKE_CURRENT_SOURCE_DIR})
target_include_directories(rawsmusic_sacd PRIVATE ${SACD_CONFIG} ${CMAKE_CURRENT_SOURCE_DIR}/libsacd/src)
# Keep utility time.h below libc headers, matching upstream's include ordering.
target_include_directories(rawsmusic_sacd SYSTEM PRIVATE ${SAUTIL})
target_compile_definitions(rawsmusic_sacd PRIVATE SACD_NO_PS3DRIVE HAVE_SA_CONFIG_H
    SACD_API_EXPORTS DST_BUILDING_DLL SACD_USE_VISIBILITY_ATTR)
target_compile_options(rawsmusic_sacd PRIVATE -O2 -ffunction-sections -fdata-sections)
if(ANDROID)
    target_link_options(rawsmusic_sacd PRIVATE -Wl,--gc-sections)
    target_link_libraries(rawsmusic_sacd PRIVATE m)
endif()
