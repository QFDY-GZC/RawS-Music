@echo off
setlocal enabledelayedexpansion

set NDK_VERSION=25.1.8937393
set NDK_PATH=%LOCALAPPDATA%\Android\Sdk\ndk\%NDK_VERSION%
set TOOLCHAIN=%NDK_PATH%\toolchains\llvm\prebuilt\windows-x86_64
set API=21
set FFMPEG_VERSION=6.0
set FFMPEG_SRC=ffmpeg-%FFMPEG_VERSION%
set OUTPUT_DIR=%~dp0build

echo =========================================
echo  RawSMusic Custom FFmpeg Build Script
echo  Target: arm64-v8a (Android API %API%)
echo =========================================
echo.

if not exist "%FFMPEG_SRC%" (
    echo [1/3] Downloading FFmpeg %FFMPEG_VERSION%...
    curl -L -o "ffmpeg-%FFMPEG_VERSION%.tar.bz2" "https://ffmpeg.org/releases/ffmpeg-%FFMPEG_VERSION%.tar.bz2"
    if errorlevel 1 (
        echo Download failed! Trying mirror...
        curl -L -o "ffmpeg-%FFMPEG_VERSION%.tar.bz2" "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n%FFMPEG_VERSION%.tar.gz"
    )
    echo Extracting...
    tar xjf "ffmpeg-%FFMPEG_VERSION%.tar.bz2" 2>nul || tar xzf "ffmpeg-%FFMPEG_VERSION%.tar.gz" 2>nul
) else (
    echo [1/3] FFmpeg source already exists, skipping download.
)

cd "%FFMPEG_SRC%"

echo [2/3] Configuring FFmpeg (audio-only minimal build)...

set CC=%TOOLCHAIN%\bin\aarch64-linux-android%API%-clang.cmd
set CXX=%TOOLCHAIN%\bin\aarch64-linux-android%API%-clang++.cmd
set CROSS=%TOOLCHAIN%\bin\aarch64-linux-android%API%-
set SYSROOT=%TOOLCHAIN%\sysroot
set AR=%TOOLCHAIN%\bin\llvm-ar.exe
set NM=%TOOLCHAIN%\bin\llvm-nm.exe
set RANLIB=%TOOLCHAIN%\bin\llvm-ranlib.exe
set STRIP=%TOOLCHAIN%\bin\llvm-strip.exe

bash ./configure ^
    --prefix="%OUTPUT_DIR%/arm64-v8a" ^
    --target-os=android ^
    --arch=aarch64 ^
    --cpu=armv8-a ^
    --enable-cross-compile ^
    --cross-prefix="%CROSS%" ^
    --cc="%CC%" ^
    --cxx="%CXX%" ^
    --ar="%AR%" ^
    --nm="%NM%" ^
    --ranlib="%RANLIB%" ^
    --strip="%STRIP%" ^
    --sysroot="%SYSROOT%" ^
    --enable-shared ^
    --disable-static ^
    --disable-everything ^
    --enable-decoder=mp3float,mp3on4float,mp3adufloat,flac,aac,aac_latm,alac,ape,wavpack,tta ^
    --enable-decoder=wmalossless,wmapro,wmav1,wmav2,wmavoice ^
    --enable-decoder=pcm_s16le,pcm_s16be,pcm_u16le,pcm_u16be,pcm_s24le,pcm_s24be,pcm_u24le,pcm_u24be,pcm_s32le,pcm_s32be,pcm_u32le,pcm_u32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be ^
    --enable-decoder=pcm_u8,pcm_s24daud ^
    --enable-decoder=dsd_lsbf_planar,dsd_msbf_planar,dsd_lsbf,dsd_msbf ^
    --enable-decoder=opus,vorbis,truehd,mlp,ac3,eac3,dca ^
    --enable-decoder=amrnb,amrwb ^
    --enable-decoder=adpcm_ima_qt,adpcm_ima_wav,adpcm_ms,adpcm_yamaha,adpcm_adx ^
    --enable-decoder=mpc7,mpc8,cook,qdm2,shorten ^
    --enable-demuxer=mp3,aac,ac3,flac,wav,ape,tta,wv,asf,ogg,opus ^
    --enable-demuxer=truehd,mlp,dts,dsf,dff,aiff,mpc,mpc8,mp4,mov,matroska ^
    --enable-demuxer=pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le ^
    --enable-demuxer=pcm_s16be,pcm_s24be,pcm_s32be,pcm_f32be,pcm_f64be ^
    --enable-demuxer=amr,rm,xwma,caf,voc,adx,rawvideo ^
    --enable-protocol=file,pipe ^
    --enable-parser=mpegaudio,aac,aac_latm,flac,dca,ac3,vorbis,opus,mlp,cook ^
    --disable-network ^
    --disable-avfilter ^
    --disable-avdevice ^
    --disable-postproc ^
    --disable-programs ^
    --disable-doc ^
    --disable-swscale ^
    --disable-encoders ^
    --disable-muxers ^
    --disable-bsfs ^
    --disable-indevs ^
    --disable-outdevs ^
    --enable-optimizations ^
    --enable-small ^
    --enable-swresample ^
    --disable-debug ^
    --disable-ffplay ^
    --disable-ffprobe ^
    --disable-ffmpeg ^
    --disable-version3 ^
    --disable-gpl ^
    --disable-nonfree ^
    --disable-iconv ^
    --disable-zlib ^
    --disable-bzlib ^
    --disable-lzma ^
    --disable-sdl2 ^
    --disable-v4l2-m2m ^
    --disable-mediacodec

if errorlevel 1 (
    echo Configure failed!
    pause
    exit /b 1
)

echo [3/3] Building FFmpeg...
make -j%NUMBER_OF_PROCESSORS%
make install

echo.
echo =========================================
echo  Build complete!
echo  Output: %OUTPUT_DIR%\arm64-v8a\lib\
echo =========================================

dir "%OUTPUT_DIR%\arm64-v8a\lib\*.so" 2>nul

echo.
echo Copy .so files to your project:
echo   copy "%OUTPUT_DIR%\arm64-v8a\lib\*.so" "d:\RawSMusic\app\src\main\jniLibs\arm64-v8a\"

pause
