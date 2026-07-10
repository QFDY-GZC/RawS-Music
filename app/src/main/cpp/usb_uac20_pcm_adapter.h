#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::usb {

struct Uac20PcmContainer {
    int validBits = 0;
    int subslotBytes = 0;
};

enum class Uac20PcmAdapterMode : int {
    Unsupported = 0,
    Passthrough = 1,
    Pcm24In32ToPacked24 = 2,
    Packed24ToPcm24In32 = 3,
};

struct Uac20PcmAdapterConfig {
    Uac20PcmContainer source{};
    Uac20PcmContainer device{};
    int channels = 0;

    // 8 = FFmpeg-style signed 32-bit sample containing 24 valid upper bits.
    // 0 = low-24 variant. Keep configurable for device-specific quirks.
    int source24In32ShiftBits = 8;
};

struct Uac20PcmAdapterStats {
    bool configured = false;
    Uac20PcmAdapterMode mode = Uac20PcmAdapterMode::Unsupported;
    int sourceFrameBytes = 0;
    int deviceFrameBytes = 0;
    int source24In32ShiftBits = 8;

    int64_t totalInputBytes = 0;
    int64_t totalOutputBytes = 0;
    int64_t totalFrames = 0;
    int64_t convertCalls = 0;
    int64_t unalignedCalls = 0;

    int lastInputBytes = 0;
    int lastOutputBytes = 0;
    int lastRemainderBytes = 0;
    std::string lastError;
};

class Uac20PcmAdapter {
public:
    bool configure(const Uac20PcmAdapterConfig& config);

    // Converts whole frames only. Remainder bytes are reported and ignored.
    // Returns output bytes, or a negative error code.
    int convert(const uint8_t* src, int srcBytes, uint8_t* dst, int dstCapacity);

    Uac20PcmAdapterStats snapshot() const;

private:
    int convert24In32ToPacked24(const uint8_t* src, int frames, uint8_t* dst);
    int convertPacked24To24In32(const uint8_t* src, int frames, uint8_t* dst);

private:
    Uac20PcmAdapterConfig config_{};
    Uac20PcmAdapterStats stats_{};
};

int uac20PcmFrameBytes(const Uac20PcmContainer& container, int channels);
const char* uac20PcmAdapterModeName(Uac20PcmAdapterMode mode);
std::string describeUac20PcmAdapterStats(const Uac20PcmAdapterStats& stats);

} // namespace rawsmusic::usb
