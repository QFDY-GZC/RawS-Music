#include "raw_audio_format_policy.h"

#include <algorithm>
#include <sstream>

namespace rawsmusic::usb {

const char* rawAudioTransportKindName(RawAudioTransportKind transport) {
    switch (transport) {
        case RawAudioTransportKind::Pcm: return "PCM";
        case RawAudioTransportKind::DoP: return "DoP";
        case RawAudioTransportKind::NativeDsd: return "NativeDSD";
        default: return "Unknown";
    }
}

const char* rawPcmAdapterModeName(RawPcmAdapterMode mode) {
    switch (mode) {
        case RawPcmAdapterMode::None: return "NONE";
        case RawPcmAdapterMode::S16ToS24: return "S16_TO_S24_ZERO_PAD";
        case RawPcmAdapterMode::S16ToS32: return "S16_TO_S32_ZERO_PAD";
        case RawPcmAdapterMode::S24ToS32: return "S24_TO_S32_ZERO_PAD";
        case RawPcmAdapterMode::S32ToS24: return "S32_TO_S24_TRUNCATE";
        case RawPcmAdapterMode::S32ToS24InS32: return "S32_TO_S24_IN_S32_ZERO_LSB";
        case RawPcmAdapterMode::Pcm24In32ToPacked24: return "PCM24_IN_S32_TO_PACKED24";
        case RawPcmAdapterMode::Packed24ToPcm24In32: return "PACKED24_TO_PCM24_IN_S32";
        case RawPcmAdapterMode::Pcm24In32ToContainer32: return "PCM24_IN_S32_TO_CONTAINER32";
        default: return "UNSUPPORTED";
    }
}

RawPcmAdapterDecision chooseRawPcmAdapter(
        int sourceChannels,
        int sourceBitDepth,
        int sourceBytesPerSample,
        int deviceChannels,
        int deviceBitDepth,
        int deviceSubslotSize,
        bool bitPerfect) {
    RawPcmAdapterDecision d{};
    d.mode = RawPcmAdapterMode::Unsupported;
    d.reason = "unsupported";

    if (sourceChannels <= 0 || deviceChannels <= 0 || sourceBytesPerSample <= 0 || deviceSubslotSize <= 0) {
        d.reason = "invalid-format";
        return d;
    }
    if (sourceChannels != deviceChannels) {
        d.reason = "channel-remix-required";
        return d;
    }

    if (sourceBitDepth == deviceBitDepth && sourceBytesPerSample == deviceSubslotSize) {
        d.mode = RawPcmAdapterMode::None;
        d.supported = true;
        d.losslessIntegerTransform = true;
        d.reason = "exact";
        return d;
    }

    // Original engine rule: bit-perfect still allows lossless integer widening,
    // but never lossy down-conversion, float conversion, or channel remixing.
    if (sourceBitDepth == 16 && sourceBytesPerSample == 2 && deviceBitDepth == 24 && deviceSubslotSize == 3) {
        d.mode = RawPcmAdapterMode::S16ToS24;
        d.supported = true;
        d.losslessIntegerTransform = true;
        d.reason = "lossless-integer-widen";
        return d;
    }
    if (sourceBitDepth == 16 && sourceBytesPerSample == 2 && deviceBitDepth == 32 && deviceSubslotSize == 4) {
        d.mode = RawPcmAdapterMode::S16ToS32;
        d.supported = true;
        d.losslessIntegerTransform = true;
        d.reason = "lossless-integer-widen";
        return d;
    }
    if (sourceBitDepth == 24 && sourceBytesPerSample == 3 && deviceBitDepth == 32 && deviceSubslotSize == 4) {
        d.mode = RawPcmAdapterMode::S24ToS32;
        d.supported = true;
        d.losslessIntegerTransform = true;
        d.reason = "lossless-integer-widen";
        return d;
    }
    if (sourceBitDepth == 24 && sourceBytesPerSample == 4 && deviceBitDepth == 24 && deviceSubslotSize == 3) {
        d.mode = RawPcmAdapterMode::Pcm24In32ToPacked24;
        d.supported = true;
        d.losslessIntegerTransform = true;
        d.reason = "24-valid-in-32-to-packed24";
        return d;
    }
    if (sourceBitDepth == 24 && sourceBytesPerSample == 4 && deviceBitDepth == 32 && deviceSubslotSize == 4) {
        // FFmpeg/RawSMusic carries 24-bit PCM in an S32LE container.  Many
        // UAC2 DACs expose a 32-bit alt setting; for that case the safest
        // bit-perfect transport is to keep the 4-byte S32LE container instead
        // of repacking to 3-byte S24.  This mirrors the user-requested
        // prefer24In32 path and avoids byte-shift ambiguity on packed24 alts.
        d.mode = RawPcmAdapterMode::Pcm24In32ToContainer32;
        d.supported = true;
        d.losslessIntegerTransform = true;
        d.reason = "24-valid-in-32-direct-container";
        return d;
    }
    if (sourceBitDepth == 24 && sourceBytesPerSample == 3 && deviceBitDepth == 24 && deviceSubslotSize == 4) {
        d.mode = RawPcmAdapterMode::Packed24ToPcm24In32;
        d.supported = true;
        d.losslessIntegerTransform = true;
        d.reason = "packed24-to-24-valid-in-32";
        return d;
    }

    if (sourceBitDepth == 32 && sourceBytesPerSample == 4 && deviceBitDepth == 24 && deviceSubslotSize == 3) {
        d.mode = RawPcmAdapterMode::S32ToS24;
        d.supported = !bitPerfect;
        d.losslessIntegerTransform = false;
        d.dropsLowBits = true;
        d.reason = bitPerfect ? "bit-perfect-rejects-truncate" : "truncate-low-8-bits";
        return d;
    }
    if (sourceBitDepth == 32 && sourceBytesPerSample == 4 && deviceBitDepth == 24 && deviceSubslotSize == 4) {
        d.mode = RawPcmAdapterMode::S32ToS24InS32;
        d.supported = true;
        d.losslessIntegerTransform = true;
        d.reason = "24-valid-in-32-container";
        return d;
    }

    d.reason = "no-integer-adapter";
    return d;
}

RawResamplerPlan buildRawResamplerPlan(
        const RawPcmFormatSpec& source,
        const RawPcmFormatSpec& device,
        RawAudioTransportKind transport,
        bool bitPerfect) {
    RawResamplerPlan p{};
    p.transport = transport;
    p.sourceSampleRate = source.sampleRate;
    p.sourceChannels = source.channels;
    p.sourceValidBits = source.validBits;
    p.sourceBytesPerSample = source.containerBytesPerSample;
    p.sourceFrameBytes = source.frameBytes();
    p.deviceSampleRate = device.sampleRate;
    p.deviceChannels = device.channels;
    p.deviceValidBits = device.validBits;
    p.deviceSubslotBytes = device.containerBytesPerSample;
    p.deviceFrameBytes = device.frameBytes();

    if (transport != RawAudioTransportKind::Pcm) {
        p.valid = true;
        p.required = false;
        p.reason = "transport-bypasses-pcm-resampler";
        p.postResampleAdapter = RawPcmAdapterMode::None;
        return p;
    }

    if (p.sourceSampleRate <= 0 || p.deviceSampleRate <= 0 ||
        p.sourceChannels <= 0 || p.deviceChannels <= 0 ||
        p.sourceBytesPerSample <= 0 || p.deviceSubslotBytes <= 0) {
        p.reason = "invalid-format";
        return p;
    }

    const auto adapter = chooseRawPcmAdapter(
            source.channels,
            source.validBits,
            source.containerBytesPerSample,
            device.channels,
            device.validBits,
            device.containerBytesPerSample,
            bitPerfect);
    p.postResampleAdapter = adapter.mode;

    if (!adapter.supported) {
        p.reason = adapter.reason;
        return p;
    }

    p.required = source.sampleRate != device.sampleRate || source.channels != device.channels;
    // Original engine rule: swr only performs sample-rate/channel conversion.
    // It outputs in the decoder source container; device subslot packing happens
    // afterwards through the PCM adapter.
    p.swrOutputFrameBytes = std::max(1, device.channels) * std::max(1, source.containerBytesPerSample);
    const int outBytesPerSecond = std::max(1, device.sampleRate) * std::max(1, p.swrOutputFrameBytes);
    p.outputBufferBytes = outBytesPerSecond * 4;
    p.valid = true;
    p.reason = p.required ? "swr-rate-or-channel-conversion" : "no-resampling-needed";
    return p;
}

std::string describeRawPcmAdapterDecision(const RawPcmAdapterDecision& decision) {
    std::ostringstream os;
    os << "mode=" << rawPcmAdapterModeName(decision.mode)
       << " supported=" << (decision.supported ? "yes" : "no")
       << " lossless=" << (decision.losslessIntegerTransform ? "yes" : "no")
       << " dropsLowBits=" << (decision.dropsLowBits ? "yes" : "no")
       << " reason=" << decision.reason;
    return os.str();
}

std::string describeRawResamplerPlan(const RawResamplerPlan& plan) {
    std::ostringstream os;
    os << "valid=" << (plan.valid ? "yes" : "no")
       << " required=" << (plan.required ? "yes" : "no")
       << " transport=" << rawAudioTransportKindName(plan.transport)
       << " src=" << plan.sourceSampleRate << "Hz/" << plan.sourceChannels << "ch/"
       << plan.sourceValidBits << "bit/" << plan.sourceBytesPerSample << "B"
       << " dev=" << plan.deviceSampleRate << "Hz/" << plan.deviceChannels << "ch/"
       << plan.deviceValidBits << "bit/" << plan.deviceSubslotBytes << "B"
       << " swrFrame=" << plan.swrOutputFrameBytes
       << " devFrame=" << plan.deviceFrameBytes
       << " postAdapter=" << rawPcmAdapterModeName(plan.postResampleAdapter)
       << " outBuf=" << plan.outputBufferBytes
       << " reason=" << plan.reason;
    return os.str();
}

} // namespace rawsmusic::usb
