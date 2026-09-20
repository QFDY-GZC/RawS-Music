#include "../../main/cpp/audio_transcode_policy.h"

#include <cassert>

int main() {
    using namespace rawsmusic::transcode;

    static_assert(kSelectablePcmSampleRatesHz.front() == 44100);
    static_assert(kSelectablePcmSampleRatesHz.back() == 384000);

    assert(isSelectablePcmSampleRate(44100));
    assert(isSelectablePcmSampleRate(384000));
    assert(!isSelectablePcmSampleRate(22050));
    assert(!isSelectablePcmSampleRate(768000));

    assert(normalizeSourceBitDepth(16) == 16);
    assert(normalizeSourceBitDepth(20) == 24);
    assert(normalizeSourceBitDepth(24) == 24);
    assert(normalizeSourceBitDepth(32) == 32);
    assert(normalizeSourceBitDepth(64) == 32);
    assert(normalizeSourceBitDepth(65) == 0);

    assert(resolveTargetSampleRate(96000, 0) == 96000);
    assert(resolveTargetSampleRate(96000, 192000) == 192000);
    assert(resolveTargetSampleRate(22050, 0) == 0);

    assert(resolveTargetBitDepth(24, 0) == 24);
    assert(resolveTargetBitDepth(24, 16) == 16);
    assert(resolveTargetBitDepth(24, 20) == 0);

    assert(!shouldAutoDither(96000, 96000, 24, 24));
    assert(shouldAutoDither(96000, 96000, 24, 16));
    assert(shouldAutoDither(96000, 192000, 24, 24));
    assert(!shouldAutoDither(96000, 192000, 24, 32));

    assert(isSelectableDsdMultiplier(64));
    assert(isSelectableDsdMultiplier(1024));
    assert(!isSelectableDsdMultiplier(32));
    assert(!isSelectableDsdMultiplier(2048));
    assert(dsdBaseRateForPcmSource(44100) == 44100);
    assert(dsdBaseRateForPcmSource(176400) == 44100);
    assert(dsdBaseRateForPcmSource(48000) == 48000);
    assert(dsdBaseRateForPcmSource(384000) == 48000);
    assert(resolveDsdSampleRate(44100, 64) == 2822400);
    assert(resolveDsdSampleRate(96000, 256) == 12288000);
    assert(resolveDsdSampleRate(352800, 1024) == 45158400);
    assert(resolveDsdSampleRate(22050, 64) == 0);

    assert(defaultPcmSampleRateForDsd(2822400) == 176400);
    assert(defaultPcmSampleRateForDsd(5644800) == 176400);
    assert(defaultPcmSampleRateForDsd(45158400) == 176400);
    assert(defaultPcmSampleRateForDsd(3072000) == 192000);
    assert(defaultPcmSampleRateForDsd(6144000) == 192000);
    assert(defaultPcmSampleRateForDsd(49152000) == 192000);
    assert(defaultPcmSampleRateForDsd(1234567) == 0);

    assert(logicalDsdRateFromCodecByteRate(352800) == 2822400);    // DSD64/44.1
    assert(logicalDsdRateFromCodecByteRate(384000) == 3072000);    // DSD64/48
    assert(logicalDsdRateFromCodecByteRate(2822400) == 22579200);  // DSD512/44.1
    assert(logicalDsdRateFromCodecByteRate(3072000) == 24576000);  // DSD512/48
    assert(logicalDsdRateFromCodecByteRate(5644800) == 45158400);  // DSD1024/44.1
    assert(logicalDsdRateFromCodecByteRate(6144000) == 49152000);  // DSD1024/48
    assert(logicalDsdRateFromCodecByteRate(0) == 0);

    return 0;
}
