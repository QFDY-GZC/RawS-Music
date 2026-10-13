#pragma once

#include <array>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace rawsmusic::transcode {

inline constexpr std::array<int, 8> kSelectablePcmSampleRatesHz{
    44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
};

inline constexpr std::array<int, 3> kSelectablePcmBitDepths{16, 24, 32};
inline constexpr std::array<int, 5> kSelectableDsdMultipliers{64, 128, 256, 512, 1024};

inline bool isSelectablePcmSampleRate(int sampleRateHz) {
    return std::find(kSelectablePcmSampleRatesHz.begin(),
                     kSelectablePcmSampleRatesHz.end(),
                     sampleRateHz) != kSelectablePcmSampleRatesHz.end();
}

inline bool isSelectablePcmBitDepth(int bitDepth) {
    return std::find(kSelectablePcmBitDepths.begin(),
                     kSelectablePcmBitDepths.end(),
                     bitDepth) != kSelectablePcmBitDepths.end();
}

inline bool isSelectableDsdMultiplier(int multiplier) {
    return std::find(kSelectableDsdMultipliers.begin(),
                     kSelectableDsdMultipliers.end(),
                     multiplier) != kSelectableDsdMultipliers.end();
}

inline int dsdBaseRateForPcmSource(int sampleRateHz) {
    if (!isSelectablePcmSampleRate(sampleRateHz)) return 0;
    if (sampleRateHz % 48000 == 0) return 48000;
    if (sampleRateHz % 44100 == 0) return 44100;
    return 0;
}

inline int resolveDsdSampleRate(int sourceSampleRateHz, int multiplier) {
    if (!isSelectableDsdMultiplier(multiplier)) return 0;
    const int base = dsdBaseRateForPcmSource(sourceSampleRateHz);
    if (base <= 0) return 0;
    return base * multiplier;
}

inline int defaultPcmSampleRateForDsd(int logicalDsdSampleRateHz) {
    if (logicalDsdSampleRateHz <= 0) return 0;
    constexpr int kDsd64_441 = 44100 * 64;
    constexpr int kDsd64_48 = 48000 * 64;
    if (logicalDsdSampleRateHz % kDsd64_48 == 0) return 192000;
    if (logicalDsdSampleRateHz % kDsd64_441 == 0) return 176400;
    return 0;
}

/** FFmpeg raw-DSD demuxers expose one codec sample per byte (8 one-bit DSD samples). */
inline int logicalDsdRateFromCodecByteRate(int codecByteRateHz) {
    if (codecByteRateHz <= 0 || codecByteRateHz > std::numeric_limits<int>::max() / 8) return 0;
    return codecByteRateHz * 8;
}

// FFmpeg exposes the 34-byte FLAC STREAMINFO payload as codec extradata.
// RFC 9639 section 8.2: the lower 36 bits of bytes 10..17 are samples/channel;
// zero means unknown. Container duration estimates must not be used as this bound.
inline int64_t declaredFlacSampleCount(const uint8_t* streamInfo, size_t size) {
    if (!streamInfo || size != 34) return 0;
    return (static_cast<int64_t>(streamInfo[13] & 0x0fu) << 32u) |
        (static_cast<int64_t>(streamInfo[14]) << 24u) |
        (static_cast<int64_t>(streamInfo[15]) << 16u) |
        (static_cast<int64_t>(streamInfo[16]) << 8u) |
        static_cast<int64_t>(streamInfo[17]);
}

inline int boundedDecodedSampleCount(int64_t alreadyDecoded, int frameSamples, int64_t declaredSamples) {
    if (frameSamples <= 0) return 0;
    if (declaredSamples <= 0) return frameSamples;
    if (alreadyDecoded >= declaredSamples) return 0;
    return static_cast<int>(std::min<int64_t>(frameSamples, declaredSamples - alreadyDecoded));
}

inline int normalizeSourceBitDepth(int rawBits) {
    if (rawBits <= 0) return 0;
    if (rawBits <= 16) return 16;
    if (rawBits <= 24) return 24;
    // Offline decode is converted through FFmpeg/swresample. Sources stored above 32-bit
    // precision (for example 64-bit float PCM) are represented by the 32-bit target domain
    // rather than being rejected outright.
    if (rawBits <= 64) return 32;
    return 0;
}

inline int resolveTargetSampleRate(int sourceRateHz, int requestedRateHz) {
    const int resolved = requestedRateHz == 0 ? sourceRateHz : requestedRateHz;
    return isSelectablePcmSampleRate(resolved) ? resolved : 0;
}

inline int resolveTargetBitDepth(int sourceBitDepth, int requestedBitDepth) {
    const int normalizedSource = normalizeSourceBitDepth(sourceBitDepth);
    const int resolved = requestedBitDepth == 0 ? normalizedSource : requestedBitDepth;
    return isSelectablePcmBitDepth(resolved) ? resolved : 0;
}

inline bool shouldAutoDither(
    int sourceRateHz,
    int targetRateHz,
    int sourceBitDepth,
    int targetBitDepth
) {
    const int normalizedSourceBits = normalizeSourceBitDepth(sourceBitDepth);
    if (!isSelectablePcmBitDepth(targetBitDepth)) return false;
    if (targetBitDepth >= 32) return false;
    return (normalizedSourceBits > targetBitDepth) || (sourceRateHz != targetRateHz);
}

}  // namespace rawsmusic::transcode
