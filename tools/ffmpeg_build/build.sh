#!/bin/bash
set -e

NDK_PATH="/mnt/c/Users/QFDY/AppData/Local/Android/Sdk/ndk/25.1.8937393"
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/windows-x86_64"
API=21
OUTPUT_DIR="/mnt/d/RawSMusic/tools/ffmpeg_build/build"

export PATH="$TOOLCHAIN/bin:$PATH"

echo "Toolchain: $TOOLCHAIN"
ls "$TOOLCHAIN/bin/aarch64-linux-android21-clang" || { echo "ERROR: NDK clang not found!"; exit 1; }
echo "NDK OK"

echo "Configuring FFmpeg..."

./configure \
    --prefix="$OUTPUT_DIR/arm64-v8a" \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --enable-cross-compile \
    --cross-prefix="$TOOLCHAIN/bin/aarch64-linux-android$API-" \
    --cc="$TOOLCHAIN/bin/aarch64-linux-android$API-clang" \
    --cxx="$TOOLCHAIN/bin/aarch64-linux-android$API-clang++" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --enable-shared \
    --disable-static \
    --disable-everything \
    --enable-decoder=mp3float,mp3on4float,mp3adufloat \
    --enable-decoder=flac \
    --enable-decoder=aac,aac_latm \
    --enable-decoder=alac \
    --enable-decoder=ape \
    --enable-decoder=wavpack \
    --enable-decoder=tta \
    --enable-decoder=wmalossless,wmapro,wmav1,wmav2,wmavoice \
    --enable-decoder=pcm_s16le,pcm_s16be,pcm_u16le,pcm_u16be,pcm_s24le,pcm_s24be,pcm_u24le,pcm_u24be,pcm_s32le,pcm_s32be,pcm_u32le,pcm_u32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be \
    --enable-decoder=pcm_u8,pcm_s24daud,pcm_zork \
    --enable-decoder=dsd_lsbf_planar,dsd_msbf_planar,dsd_lsbf,dsd_msbf \
    --enable-decoder=opus \
    --enable-decoder=vorbis \
    --enable-decoder=truehd,mlp \
    --enable-decoder=ac3,eac3 \
    --enable-decoder=dca \
    --enable-decoder=amrnb,amrwb \
    --enable-decoder=g723_1,g729 \
    --enable-decoder=adpcm_ima_qt,adpcm_ima_wav,adpcm_ms,adpcm_yamaha,adpcm_adx \
    --enable-decoder=mpc7,mpc8 \
    --enable-decoder=ra_144,ra_288 \
    --enable-decoder=shorten \
    --enable-decoder=nellymoser \
    --enable-decoder=cook \
    --enable-decoder=qdm2 \
    --enable-decoder=sonic \
    --enable-demuxer=mp3 \
    --enable-demuxer=aac,ac3 \
    --enable-demuxer=flac \
    --enable-demuxer=wav \
    --enable-demuxer=ape \
    --enable-demuxer=tta \
    --enable-demuxer=wv \
    --enable-demuxer=asf \
    --enable-demuxer=ogg \
    --enable-demuxer=opus \
    --enable-demuxer=truehd,mlp \
    --enable-demuxer=dts \
    --enable-demuxer=dsf,dff \
    --enable-demuxer=aiff \
    --enable-demuxer=mpc,mpc8 \
    --enable-demuxer=mp4,mov \
    --enable-demuxer=matroska \
    --enable-demuxer=pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le \
    --enable-demuxer=pcm_s16be,pcm_s24be,pcm_s32be,pcm_f32be,pcm_f64be \
    --enable-demuxer=amr \
    --enable-demuxer=rm \
    --enable-demuxer=xwma \
    --enable-demuxer=sox \
    --enable-demuxer=shorten \
    --enable-demuxer=nistsphere \
    --enable-demuxer=caf \
    --enable-demuxer=rsd \
    --enable-demuxer=svag \
    --enable-demuxer=voc \
    --enable-demuxer=adx \
    --enable-demuxer=ea \
    --enable-demuxer=idf \
    --enable-demuxer=ircam \
    --enable-demuxer=rawvideo \
    --enable-protocol=file \
    --enable-protocol=pipe \
    --enable-parser=mpegaudio \
    --enable-parser=aac \
    --enable-parser=aac_latm \
    --enable-parser=flac \
    --enable-parser=dca \
    --enable-parser=ac3 \
    --enable-parser=vorbis \
    --enable-parser=opus \
    --enable-parser=mlp \
    --enable-parser=cook \
    --disable-network \
    --disable-avfilter \
    --disable-avdevice \
    --disable-postproc \
    --disable-programs \
    --disable-doc \
    --disable-swscale \
    --disable-encoders \
    --disable-muxers \
    --disable-bsfs \
    --disable-indevs \
    --disable-outdevs \
    --enable-optimizations \
    --enable-small \
    --enable-swresample \
    --disable-debug \
    --disable-ffplay \
    --disable-ffprobe \
    --disable-ffmpeg \
    --disable-version3 \
    --disable-gpl \
    --disable-nonfree \
    --disable-iconv \
    --disable-zlib \
    --disable-bzlib \
    --disable-lzma \
    --disable-sdl2 \
    --disable-xlib \
    --disable-v4l2-m2m \
    --disable-mediacodec \
    --disable-alsa \
    --enable-pthreads

echo "Building FFmpeg..."
make -j$(nproc)
make install

echo ""
echo "========================================="
echo " Build complete!"
echo "========================================="

ls -lh "$OUTPUT_DIR/arm64-v8a/lib/"*.so 2>/dev/null || echo "No .so files found!"

echo ""
echo "Copying .so files to jniLibs..."
mkdir -p /mnt/d/RawSMusic/app/src/main/jniLibs/arm64-v8a/
cp "$OUTPUT_DIR/arm64-v8a/lib/"*.so /mnt/d/RawSMusic/app/src/main/jniLibs/arm64-v8a/
echo "Done! .so files copied."
ls -lh /mnt/d/RawSMusic/app/src/main/jniLibs/arm64-v8a/
