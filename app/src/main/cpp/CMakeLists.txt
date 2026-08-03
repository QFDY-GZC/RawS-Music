cmake_minimum_required(VERSION 3.18.1)
project("rawsmusic_native")

set(FFMPEG_DIR ${CMAKE_SOURCE_DIR}/ffmpeg_include)

# Lyrico-compatible JavaScript source runtime. Keep it isolated from the audio
# libraries so loading a lyric plugin never initializes the playback engine.
set(QUICKJS_DIR ${CMAKE_SOURCE_DIR}/third_party/quickjs-ng)
add_library(rawsmusic_quickjs STATIC
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/dtoa.c
)
target_include_directories(rawsmusic_quickjs PUBLIC ${QUICKJS_DIR})
target_compile_definitions(rawsmusic_quickjs PRIVATE CONFIG_VERSION="0.14.0")
set_target_properties(rawsmusic_quickjs PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
target_compile_options(rawsmusic_quickjs PRIVATE -O2 -fomit-frame-pointer -w)

add_library(rawsmusic_lyrico_quickjs OBJECT rawsmusic_lyrico_quickjs.cpp)
target_include_directories(rawsmusic_lyrico_quickjs PRIVATE ${QUICKJS_DIR})
target_compile_options(rawsmusic_lyrico_quickjs PRIVATE -O2 -fomit-frame-pointer -std=c++17)
target_link_libraries(rawsmusic_lyrico_quickjs rawsmusic_quickjs android log)

# Keep hot-path optimization levels target-specific while allowing the linker to
# discard unreachable native code and merge identical functions in release APKs.
add_compile_options(
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>
)
add_link_options(
    -Wl,--gc-sections
    -Wl,--icf=safe
)

# ==========================================
# DSP 引擎
# ==========================================
add_library(
    rawsmusic_dsp
    OBJECT
    dsp_engine.cpp
    android_dvc_processor.cpp
    auto_peak_limiter.cpp
    experimental_gain.cpp
    loudness_balance_processor.cpp
    mono_bass_processor.cpp
    dynamic_eq_processor.cpp
    moog_ladder_filter.cpp
    realtime_stem_separator.cpp
    stereo_width_processor.cpp
    stereo_coherence_scene.cpp
    second_order_ambisonics.cpp
    analytic_hrtf_decoder.cpp
    compact_brir_renderer.cpp
    android_binaural_spatial_processor.cpp
    speaker_output_effect.cpp
    raw_fft_convolver.cpp
    raw_fft_convolver_jni.cpp
    pcm_dither.cpp
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
    OBJECT
    usb_audio_engine.cpp
    usb_hardware_volume.cpp
    raw_audio_safety.cpp
    raw_usb_background_scheduler.cpp
    raw_usb_audio_schedule_bootstrap.cpp
    raw_usb_native_guardian.cpp
    raw_usb_submit_scheduler.cpp
    raw_audio_format_policy.cpp
    raw_pcm_pipeline.cpp
    pcm_to_dsd_converter.cpp
    usb_hid.cpp
    raw_usb_crash_guard.cpp
)

target_include_directories(
    rawsmusic_usb
    PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${LIBUSB_DIR}
    ${FFMPEG_DIR}
)

target_compile_options(rawsmusic_usb PRIVATE -O2 -fomit-frame-pointer -std=c++17)
# PCM->DSD is a recursive per-bit realtime kernel. Compile this independent
# translation unit at the hot-path level instead of inheriting the broad USB
# engine's conservative -O2 setting. Fast-math is safe here because inputs are
# integer PCM and the converter clamps every sample before modulation.
set_source_files_properties(
    pcm_to_dsd_converter.cpp
    PROPERTIES COMPILE_OPTIONS "-O3;-ffast-math;-fno-math-errno;-fno-trapping-math"
)

# Android 16 exposes OpenSL callback policy as FIFO|SCHED_RESET_ON_FORK;
# the child pthread is therefore reset to SCHED_OTHER. UAPP also falls back to
# its normal event pthread when the callback policy is not exactly FIFO. Keep
# this experimental bootstrap compiled out and rely on the UAPP-style Android
# media identity + foreground/wakelock path for background process exemption.
target_compile_definitions(rawsmusic_usb PRIVATE RAWS_ENABLE_OPENSL_RT_BOOTSTRAP=0)

target_link_libraries(
    rawsmusic_usb
    usb
    rawsmusic_ffmpeg
    android
    log
    OpenSLES
)

set(JNI_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(rawsmusic_ffmpeg SHARED IMPORTED)
set_target_properties(
    rawsmusic_ffmpeg
    PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/librawsmusic_ffmpeg.so
)


# ==========================================
# Native Audio 引擎 (AAudio / OpenSL ES / Direct)
# ==========================================
add_library(
    rawsmusic_native_audio
    OBJECT
    native_audio_engine.cpp
    raw_android_audio_identity.cpp
)

target_include_directories(
    rawsmusic_native_audio
    PRIVATE
    ${CMAKE_SOURCE_DIR}
)

target_compile_options(rawsmusic_native_audio PRIVATE -O3 -fomit-frame-pointer -std=c++17)

target_link_libraries(
    rawsmusic_native_audio
    android
    log
    OpenSLES
    dl
)

# ==========================================
# TagLib 全格式元数据解析库
# ==========================================
set(TAGLIB_DIR ${CMAKE_SOURCE_DIR}/taglib)
set(UTFCPP_DIR ${CMAKE_SOURCE_DIR}/utfcpp-4.1.1/source)

set(TAGLIB_SOURCES
    # Toolkit (核心)
    ${TAGLIB_DIR}/toolkit/tbytevector.cpp
    ${TAGLIB_DIR}/toolkit/tbytevectorlist.cpp
    ${TAGLIB_DIR}/toolkit/tbytevectorstream.cpp
    ${TAGLIB_DIR}/toolkit/tdebug.cpp
    ${TAGLIB_DIR}/toolkit/tdebuglistener.cpp
    ${TAGLIB_DIR}/toolkit/tfile.cpp
    ${TAGLIB_DIR}/toolkit/tfilestream.cpp
    ${TAGLIB_DIR}/toolkit/tiostream.cpp
    ${TAGLIB_DIR}/toolkit/tpicturetype.cpp
    ${TAGLIB_DIR}/toolkit/tpropertymap.cpp
    ${TAGLIB_DIR}/toolkit/tstring.cpp
    ${TAGLIB_DIR}/toolkit/tstringlist.cpp
    ${TAGLIB_DIR}/toolkit/tvariant.cpp
    ${TAGLIB_DIR}/toolkit/tversionnumber.cpp
    ${TAGLIB_DIR}/toolkit/tzlib.cpp
    # 核心基类
    ${TAGLIB_DIR}/audioproperties.cpp
    ${TAGLIB_DIR}/tag.cpp
    ${TAGLIB_DIR}/tagunion.cpp
    ${TAGLIB_DIR}/tagutils.cpp
    # MPEG (MP3)
    ${TAGLIB_DIR}/mpeg/mpegfile.cpp
    ${TAGLIB_DIR}/mpeg/mpegproperties.cpp
    ${TAGLIB_DIR}/mpeg/mpegheader.cpp
    ${TAGLIB_DIR}/mpeg/xingheader.cpp
    ${TAGLIB_DIR}/mpeg/id3v1/id3v1tag.cpp
    ${TAGLIB_DIR}/mpeg/id3v1/id3v1genres.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/id3v2tag.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/id3v2header.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/id3v2frame.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/id3v2framefactory.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/id3v2synchdata.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/id3v2footer.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/id3v2extendedheader.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/attachedpictureframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/chapterframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/commentsframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/eventtimingcodesframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/generalencapsulatedobjectframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/ownershipframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/podcastframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/popularimeterframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/privateframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/relativevolumeframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/synchronizedlyricsframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/tableofcontentsframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/textidentificationframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/uniquefileidentifierframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/unknownframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/unsynchronizedlyricsframe.cpp
    ${TAGLIB_DIR}/mpeg/id3v2/frames/urllinkframe.cpp
    # FLAC
    ${TAGLIB_DIR}/flac/flacfile.cpp
    ${TAGLIB_DIR}/flac/flacproperties.cpp
    ${TAGLIB_DIR}/flac/flacmetadatablock.cpp
    ${TAGLIB_DIR}/flac/flacpicture.cpp
    ${TAGLIB_DIR}/flac/flacunknownmetadatablock.cpp
    # OGG
    ${TAGLIB_DIR}/ogg/oggfile.cpp
    ${TAGLIB_DIR}/ogg/oggpage.cpp
    ${TAGLIB_DIR}/ogg/oggpageheader.cpp
    ${TAGLIB_DIR}/ogg/xiphcomment.cpp
    ${TAGLIB_DIR}/ogg/vorbis/vorbisfile.cpp
    ${TAGLIB_DIR}/ogg/vorbis/vorbisproperties.cpp
    ${TAGLIB_DIR}/ogg/opus/opusfile.cpp
    ${TAGLIB_DIR}/ogg/opus/opusproperties.cpp
    ${TAGLIB_DIR}/ogg/flac/oggflacfile.cpp
    # MP4
    ${TAGLIB_DIR}/mp4/mp4file.cpp
    ${TAGLIB_DIR}/mp4/mp4atom.cpp
    ${TAGLIB_DIR}/mp4/mp4item.cpp
    ${TAGLIB_DIR}/mp4/mp4properties.cpp
    ${TAGLIB_DIR}/mp4/mp4tag.cpp
    ${TAGLIB_DIR}/mp4/mp4coverart.cpp
    ${TAGLIB_DIR}/mp4/mp4itemfactory.cpp
    ${TAGLIB_DIR}/mp4/mp4stem.cpp
    ${TAGLIB_DIR}/mp4/mp4chapter.cpp
    ${TAGLIB_DIR}/mp4/mp4nerochapterlist.cpp
    ${TAGLIB_DIR}/mp4/mp4qtchapterlist.cpp
    # ASF (WMA)
    ${TAGLIB_DIR}/asf/asffile.cpp
    ${TAGLIB_DIR}/asf/asfproperties.cpp
    ${TAGLIB_DIR}/asf/asftag.cpp
    ${TAGLIB_DIR}/asf/asfattribute.cpp
    ${TAGLIB_DIR}/asf/asfpicture.cpp
    # APE
    ${TAGLIB_DIR}/ape/apefile.cpp
    ${TAGLIB_DIR}/ape/apeproperties.cpp
    ${TAGLIB_DIR}/ape/apetag.cpp
    ${TAGLIB_DIR}/ape/apeitem.cpp
    ${TAGLIB_DIR}/ape/apefooter.cpp
    # RIFF (WAV/AIFF)
    ${TAGLIB_DIR}/riff/rifffile.cpp
    ${TAGLIB_DIR}/riff/wav/wavfile.cpp
    ${TAGLIB_DIR}/riff/wav/wavproperties.cpp
    ${TAGLIB_DIR}/riff/wav/infotag.cpp
    ${TAGLIB_DIR}/riff/aiff/aifffile.cpp
    ${TAGLIB_DIR}/riff/aiff/aiffproperties.cpp
    # DSF (DSD)
    ${TAGLIB_DIR}/dsf/dsffile.cpp
    ${TAGLIB_DIR}/dsf/dsfproperties.cpp
    # DSDIFF
    ${TAGLIB_DIR}/dsdiff/dsdifffile.cpp
    ${TAGLIB_DIR}/dsdiff/dsdiffproperties.cpp
    ${TAGLIB_DIR}/dsdiff/dsdiffdiintag.cpp
    # WavPack
    ${TAGLIB_DIR}/wavpack/wavpackfile.cpp
    ${TAGLIB_DIR}/wavpack/wavpackproperties.cpp
    # TrueAudio
    ${TAGLIB_DIR}/trueaudio/trueaudiofile.cpp
    ${TAGLIB_DIR}/trueaudio/trueaudioproperties.cpp
    # MPC
    ${TAGLIB_DIR}/mpc/mpcfile.cpp
    ${TAGLIB_DIR}/mpc/mpcproperties.cpp
)

add_library(taglib_static STATIC ${TAGLIB_SOURCES})

target_include_directories(taglib_static PUBLIC
    ${TAGLIB_DIR}
    ${TAGLIB_DIR}/toolkit
    ${TAGLIB_DIR}/mpeg
    ${TAGLIB_DIR}/mpeg/id3v1
    ${TAGLIB_DIR}/mpeg/id3v2
    ${TAGLIB_DIR}/mpeg/id3v2/frames
    ${TAGLIB_DIR}/flac
    ${TAGLIB_DIR}/ogg
    ${TAGLIB_DIR}/ogg/vorbis
    ${TAGLIB_DIR}/ogg/opus
    ${TAGLIB_DIR}/ogg/flac
    ${TAGLIB_DIR}/mp4
    ${TAGLIB_DIR}/asf
    ${TAGLIB_DIR}/ape
    ${TAGLIB_DIR}/riff
    ${TAGLIB_DIR}/riff/wav
    ${TAGLIB_DIR}/riff/aiff
    ${TAGLIB_DIR}/dsf
    ${TAGLIB_DIR}/dsf
    ${TAGLIB_DIR}/dsdiff
    ${TAGLIB_DIR}/wavpack
    ${TAGLIB_DIR}/trueaudio
    ${TAGLIB_DIR}/mpc
    ${TAGLIB_DIR}/shorten
    ${UTFCPP_DIR}
)

target_compile_definitions(taglib_static PRIVATE
    HAVE_CONFIG_H
    TAGLIB_WITH_MP4
    TAGLIB_WITH_ASF
    TAGLIB_WITH_APE
    TAGLIB_WITH_WAVPACK
    TAGLIB_WITH_FLAC
    TAGLIB_WITH_OGG
    TAGLIB_WITH_MPEG
    TAGLIB_WITH_RIFF
    TAGLIB_WITH_DSF
    TAGLIB_WITH_DSDIFF
    TAGLIB_WITH_TRUEAUDIO
    TAGLIB_WITH_MPC
    TAGLIB_WITH_VORBIS
)

target_compile_options(taglib_static PRIVATE -O2 -fomit-frame-pointer -w -std=c++17)

# TagLib 全格式 JNI 桥接
add_library(rawsmusic_taglib_full OBJECT taglib_full_bridge.cpp)

target_include_directories(rawsmusic_taglib_full PRIVATE
    ${TAGLIB_DIR}
    ${TAGLIB_DIR}/toolkit
    ${UTFCPP_DIR}
)

target_compile_options(rawsmusic_taglib_full PRIVATE -O3 -fomit-frame-pointer -std=c++17)

target_link_libraries(rawsmusic_taglib_full taglib_static android log z)


# ==========================================
# Offline AI vocal separation engine
# ==========================================
# ONNX Runtime execution lives behind the Java adapter; this native target owns only offline
# PCM/STFT/ISTFT/overlap-add and float-WAV writing. It never runs on USB or playback callbacks.
add_library(
    rawsmusic_ai_separation
    OBJECT
    ai_separation_engine.cpp
    ai_separation_engine_jni.cpp
    ai_separation_audio_encoder.cpp
    ai_separation_wav.cpp
)

target_include_directories(
    rawsmusic_ai_separation
    PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${FFMPEG_DIR}
)

target_compile_options(
    rawsmusic_ai_separation
    PRIVATE
    -O2
    -fomit-frame-pointer
    -std=c++17
)

target_link_libraries(
    rawsmusic_ai_separation
    rawsmusic_ffmpeg
    android
    log
)

# ==========================================
# Player artwork transition state machine
# ==========================================
add_library(
    rawsmusic_artwork_transition
    OBJECT
    raw_player_artwork_transition.cpp
)

target_compile_options(
    rawsmusic_artwork_transition
    PRIVATE
    -O3
    -fomit-frame-pointer
    -std=c++17
)

target_link_libraries(
    rawsmusic_artwork_transition
    android
    log
)

add_library(
    rawsmusic_static_background
    OBJECT
    raw_static_background.cpp
)

target_compile_options(
    rawsmusic_static_background
    PRIVATE
    -O3
    -fomit-frame-pointer
    -std=c++17
)

target_link_libraries(
    rawsmusic_static_background
    android
    log
)

# Offline file-spectrum analysis. FFmpeg stays in the Kotlin decoder path;
# this object only owns the stateful FFT and compact analysis payload.
add_library(
    rawsmusic_file_spectrum
    OBJECT
    raw_file_spectrum_analyzer.cpp
    raw_file_spectrum_analyzer_jni.cpp
)

target_include_directories(
    rawsmusic_file_spectrum
    PRIVATE
    ${CMAKE_SOURCE_DIR}
)

target_compile_options(rawsmusic_file_spectrum PRIVATE -O3 -fomit-frame-pointer -std=c++17)
target_link_libraries(rawsmusic_file_spectrum android log)

# Keep application-owned JNI code in one service library. Object libraries preserve
# per-engine compile flags while avoiding duplicate ELF metadata and load operations.
add_library(
    rawscoreservice
    SHARED
    $<TARGET_OBJECTS:rawsmusic_lyrico_quickjs>
    $<TARGET_OBJECTS:rawsmusic_dsp>
    $<TARGET_OBJECTS:rawsmusic_usb>
    $<TARGET_OBJECTS:rawsmusic_native_audio>
    $<TARGET_OBJECTS:rawsmusic_taglib_full>
    $<TARGET_OBJECTS:rawsmusic_ai_separation>
    $<TARGET_OBJECTS:rawsmusic_artwork_transition>
    $<TARGET_OBJECTS:rawsmusic_static_background>
    $<TARGET_OBJECTS:rawsmusic_file_spectrum>
)

target_link_libraries(
    rawscoreservice
    rawsmusic_quickjs
    usb
    taglib_static
    rawsmusic_ffmpeg
    android
    log
    z
    OpenSLES
    dl
)
