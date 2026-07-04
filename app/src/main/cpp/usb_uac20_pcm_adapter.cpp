#include "usb_uac20_pcm_adapter.h"

#include <algorithm>
#include <cstring>
#include <sstream>

namespace rawsmusic::usb {
namespace {

int safeFrameBytes(int channels, int subslotBytes) {
    if (channels <= 0 || subslotBytes <= 0) return 0;
    if (channels > 16 || subslotBytes > 8) return 0;
    return channels * subslotBytes;
}

bool is24In32(const Uac20PcmContainer& c) {
    return c.validBits == 24 && c.subslotBytes == 4;
}

bool isPacked24(const Uac20PcmContainer& c) {
    return c.validBits == 24 && c.subslotBytes == 3;
}

bool isSameContainer(const Uac20PcmContainer& a, const Uac20PcmContainer& b) {
    return a.validBits == b.validBits && a.subslotBytes == b.subslotBytes;
}

uint8_t signByteFromPacked24(const uint8_t* p) {
    return (p[2] & 0x80) ? 0xff : 0x00;
}

} // namespace

int uac20PcmFrameBytes(const Uac20PcmContainer& container, int channels) {
    return safeFrameBytes(channels, container.subslotBytes);
}

bool Uac20PcmAdapter::configure(const Uac20PcmAdapterConfig& config) {
    config_ = config;
    stats_ = Uac20PcmAdapterStats{};
    stats_.sourceFrameBytes = uac20PcmFrameBytes(config_.source, config_.channels);
    stats_.deviceFrameBytes = uac20PcmFrameBytes(config_.device, config_.channels);
    stats_.source24In32ShiftBits = config_.source24In32ShiftBits;

    if (config_.channels <= 0 || config_.channels > 16) {
        stats_.configured = false;
        stats_.lastError = "invalid channels";
        return false;
    }
    if (stats_.sourceFrameBytes <= 0 || stats_.deviceFrameBytes <= 0) {
        stats_.configured = false;
        stats_.lastError = "invalid frame size";
        return false;
    }
    if (config_.source24In32ShiftBits != 0 && config_.source24In32ShiftBits != 8) {
        stats_.configured = false;
        stats_.lastError = "source24In32ShiftBits must be 0 or 8";
        return false;
    }

    if (isSameContainer(config_.source, config_.device)) {
        stats_.mode = Uac20PcmAdapterMode::Passthrough;
    } else if (is24In32(config_.source) && isPacked24(config_.device)) {
        stats_.mode = Uac20PcmAdapterMode::Pcm24In32ToPacked24;
    } else if (isPacked24(config_.source) && is24In32(config_.device)) {
        stats_.mode = Uac20PcmAdapterMode::Packed24ToPcm24In32;
    } else {
        stats_.configured = false;
        stats_.mode = Uac20PcmAdapterMode::Unsupported;
        stats_.lastError = "unsupported PCM container conversion";
        return false;
    }

    stats_.configured = true;
    stats_.lastError.clear();
    return true;
}

int Uac20PcmAdapter::convert(const uint8_t* src, int srcBytes, uint8_t* dst, int dstCapacity) {
    if (!stats_.configured) {
        stats_.lastError = "adapter not configured";
        return -1;
    }
    if (src == nullptr || dst == nullptr || srcBytes < 0 || dstCapacity < 0) {
        stats_.lastError = "invalid buffer";
        return -2;
    }
    if (srcBytes == 0) {
        stats_.lastInputBytes = 0;
        stats_.lastOutputBytes = 0;
        stats_.lastRemainderBytes = 0;
        return 0;
    }

    const int srcFrame = stats_.sourceFrameBytes;
    const int dstFrame = stats_.deviceFrameBytes;
    if (srcFrame <= 0 || dstFrame <= 0) {
        stats_.lastError = "bad frame size";
        return -3;
    }

    const int frames = srcBytes / srcFrame;
    const int remainder = srcBytes - frames * srcFrame;
    const int needed = frames * dstFrame;
    if (needed > dstCapacity) {
        stats_.lastError = "dst capacity too small";
        stats_.lastInputBytes = srcBytes;
        stats_.lastOutputBytes = 0;
        stats_.lastRemainderBytes = remainder;
        return -4;
    }

    int produced = 0;
    switch (stats_.mode) {
        case Uac20PcmAdapterMode::Passthrough:
            std::memcpy(dst, src, static_cast<size_t>(frames * srcFrame));
            produced = frames * srcFrame;
            break;
        case Uac20PcmAdapterMode::Pcm24In32ToPacked24:
            produced = convert24In32ToPacked24(src, frames, dst);
            break;
        case Uac20PcmAdapterMode::Packed24ToPcm24In32:
            produced = convertPacked24To24In32(src, frames, dst);
            break;
        case Uac20PcmAdapterMode::Unsupported:
        default:
            stats_.lastError = "unsupported adapter mode";
            return -5;
    }

    stats_.totalInputBytes += srcBytes;
    stats_.totalOutputBytes += produced;
    stats_.totalFrames += frames;
    stats_.convertCalls += 1;
    if (remainder != 0) stats_.unalignedCalls += 1;
    stats_.lastInputBytes = srcBytes;
    stats_.lastOutputBytes = produced;
    stats_.lastRemainderBytes = remainder;
    stats_.lastError.clear();
    return produced;
}

int Uac20PcmAdapter::convert24In32ToPacked24(const uint8_t* src, int frames, uint8_t* dst) {
    const int channels = config_.channels;
    const int srcFrame = stats_.sourceFrameBytes;
    const int shift = config_.source24In32ShiftBits;
    int out = 0;
    for (int f = 0; f < frames; ++f) {
        const uint8_t* frame = src + f * srcFrame;
        for (int ch = 0; ch < channels; ++ch) {
            const uint8_t* s = frame + ch * 4;
            if (shift == 8) {
                // FFmpeg S32 sample carrying 24 valid bits: drop the low padding byte.
                dst[out++] = s[1];
                dst[out++] = s[2];
                dst[out++] = s[3];
            } else {
                // Low-24 variant used by some test paths or device-specific containers.
                dst[out++] = s[0];
                dst[out++] = s[1];
                dst[out++] = s[2];
            }
        }
    }
    return out;
}

int Uac20PcmAdapter::convertPacked24To24In32(const uint8_t* src, int frames, uint8_t* dst) {
    const int channels = config_.channels;
    const int srcFrame = stats_.sourceFrameBytes;
    const int shift = config_.source24In32ShiftBits;
    int out = 0;
    for (int f = 0; f < frames; ++f) {
        const uint8_t* frame = src + f * srcFrame;
        for (int ch = 0; ch < channels; ++ch) {
            const uint8_t* s = frame + ch * 3;
            if (shift == 8) {
                dst[out++] = 0x00;
                dst[out++] = s[0];
                dst[out++] = s[1];
                dst[out++] = s[2];
            } else {
                dst[out++] = s[0];
                dst[out++] = s[1];
                dst[out++] = s[2];
                dst[out++] = signByteFromPacked24(s);
            }
        }
    }
    return out;
}

Uac20PcmAdapterStats Uac20PcmAdapter::snapshot() const {
    return stats_;
}

const char* uac20PcmAdapterModeName(Uac20PcmAdapterMode mode) {
    switch (mode) {
        case Uac20PcmAdapterMode::Passthrough: return "Passthrough";
        case Uac20PcmAdapterMode::Pcm24In32ToPacked24: return "Pcm24In32ToPacked24";
        case Uac20PcmAdapterMode::Packed24ToPcm24In32: return "Packed24ToPcm24In32";
        case Uac20PcmAdapterMode::Unsupported: return "Unsupported";
        default: return "Unknown";
    }
}

std::string describeUac20PcmAdapterStats(const Uac20PcmAdapterStats& stats) {
    std::ostringstream os;
    os << "configured=" << (stats.configured ? 1 : 0)
       << " mode=" << uac20PcmAdapterModeName(stats.mode)
       << " srcFrame=" << stats.sourceFrameBytes
       << " devFrame=" << stats.deviceFrameBytes
       << " shift=" << stats.source24In32ShiftBits
       << " calls=" << stats.convertCalls
       << " frames=" << stats.totalFrames
       << " in=" << stats.totalInputBytes
       << " out=" << stats.totalOutputBytes
       << " lastIn=" << stats.lastInputBytes
       << " lastOut=" << stats.lastOutputBytes
       << " rem=" << stats.lastRemainderBytes
       << " unaligned=" << stats.unalignedCalls;
    if (!stats.lastError.empty()) {
        os << " error=" << stats.lastError;
    }
    return os.str();
}

} // namespace rawsmusic::usb
