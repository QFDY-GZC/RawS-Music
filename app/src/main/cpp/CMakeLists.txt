cmake_minimum_required(VERSION 3.18.1)
project("rawsmusic_native")

set(FFMPEG_DIR ${CMAKE_SOURCE_DIR}/ffmpeg_include)

# ==========================================
# DSP 引擎
# ==========================================
add_library(
    rawsmusic_dsp
    SHARED
    dsp_engine.cpp
)

target_include_directories(
    rawsmusic_dsp
    PRIVATE
    ${CMAKE_SOURCE_DIR}
)

target_compile_options(rawsmusic_dsp PRIVATE -O3 -fomit-frame-pointer)

target_link_libraries(
    rawsmusic_dsp
    android
    log
)

# ==========================================
# libusb 静态库
# ==========================================
set(LIBUSB_DIR ${CMAKE_SOURCE_DIR}/libusb)

add_library(
    usb
    STATIC
    ${LIBUSB_DIR}/core.c
    ${LIBUSB_DIR}/descriptor.c
    ${LIBUSB_DIR}/hotplug.c
    ${LIBUSB_DIR}/io.c
    ${LIBUSB_DIR}/strerror.c
    ${LIBUSB_DIR}/sync.c
    ${LIBUSB_DIR}/os/linux_usbfs.c
    ${LIBUSB_DIR}/os/linux_netlink.c
    ${LIBUSB_DIR}/os/events_posix.c
    ${LIBUSB_DIR}/os/threads_posix.c
)

target_include_directories(
    usb
    PRIVATE
    ${LIBUSB_DIR}
    ${LIBUSB_DIR}/os
)

target_compile_definitions(usb PRIVATE HAVE_CONFIG_H)

target_compile_options(usb PRIVATE -O2 -fomit-frame-pointer -w)

# ==========================================
# USB 音频引擎 (libusb 异步 I/O)
# ==========================================
add_library(
    rawsmusic_usb
    SHARED
    usb_audio_engine.cpp
)

target_include_directories(
    rawsmusic_usb
    PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${LIBUSB_DIR}
    ${FFMPEG_DIR}
)

target_compile_options(rawsmusic_usb PRIVATE -O2 -fomit-frame-pointer)

target_link_libraries(
    rawsmusic_usb
    usb
    swresample
    avutil
    android
    log
)

# ==========================================
# USB Audio Native Open (libusb claim + set_alt)
# ==========================================
add_library(
    raws_usb_audio
    SHARED
    raws_usb_audio.cpp
)

target_include_directories(
    raws_usb_audio
    PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${LIBUSB_DIR}
)

target_compile_options(raws_usb_audio PRIVATE -O2 -fomit-frame-pointer)

target_link_libraries(
    raws_usb_audio
    usb
    android
    log
)

add_library(
    rawsmusic_ffmpeg
    SHARED
    ffmpeg_bridge.cpp
)

target_include_directories(
    rawsmusic_ffmpeg
    PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${FFMPEG_DIR}
)

target_compile_options(rawsmusic_ffmpeg PRIVATE -O3 -fomit-frame-pointer)

set(JNI_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libavformat.so)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libavutil.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libswresample.so)

target_link_libraries(
    rawsmusic_ffmpeg
    avcodec
    avformat
    avutil
    swresample
    android
    log
)
