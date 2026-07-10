#include "raw_pcm_pipeline.h"

#include <algorithm>
#include <cstring>
#include <sstream>

extern "C" {
#include <libavutil/channel_layout.h>
#include <libavutil/mathematics.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

namespace rawsmusic::usb {
namespace {

AVSampleFormat bitDepthToAvFormat(int validBits, int bytesPerSample) {
    if (validBits <= 8 && bytesPerSample <= 1) return AV_SAMPLE_FMT_U8;
    if (validBits <= 16 && bytesPerSample <= 2) return AV_SAMPLE_FMT_S16;
    // FFmpeg normally represents 24-bit integer PCM as S32LE.  We also use S32
    // for packed 24 before/after swr; device packing happens in the adapter.
    return AV_SAMPLE_FMT_S32;
}

int frameBytesOf(const RawPcmFormatSpec& spec) {
    return spec.channels > 0 && spec.containerBytesPerSample > 0
            ? spec.channels * spec.containerBytesPerSample
            : 0;
}

int alignDown(int value, int frameBytes) {
    if (value <= 0 || frameBytes <= 0) return 0;
    return value - (value % frameBytes);
}

uint8_t signByteFromPacked24(const uint8_t* p) {
    return (p[2] & 0x80) ? 0xff : 0x00;
}

} // namespace

RawPcmPipeline::RawPcmPipeline() = default;

RawPcmPipeline::~RawPcmPipeline() {
    closeResamplerLocked();
}

void RawPcmPipeline::reset() {
    closeResamplerLocked();
    config_ = RawPcmPipelineConfig{};
    stats_ = RawPcmPipelineStats{};
    plan_ = RawResamplerPlan{};
    adapter_ = RawPcmAdapterDecision{};
    swrOutputBuffer_.clear();
}

bool RawPcmPipeline::configure(const RawPcmPipelineConfig& config) {
    closeResamplerLocked();
    config_ = config;
    stats_ = RawPcmPipelineStats{};
    stats_.transport = config_.transport;
    stats_.sourceSampleRate = config_.source.sampleRate;
    stats_.sourceChannels = config_.source.channels;
    stats_.sourceBits = config_.source.validBits;
    stats_.sourceBytesPerSample = config_.source.containerBytesPerSample;
    stats_.sourceFrameBytes = frameBytesOf(config_.source);
    stats_.deviceSampleRate = config_.device.sampleRate;
    stats_.deviceChannels = config_.device.channels;
    stats_.deviceBits = config_.device.validBits;
    stats_.deviceSubslotBytes = config_.device.containerBytesPerSample;
    stats_.deviceFrameBytes = frameBytesOf(config_.device);

    plan_ = buildRawResamplerPlan(config_.source, config_.device, config_.transport, config_.bitPerfect);
    adapter_ = chooseRawPcmAdapter(
            config_.source.channels,
            config_.source.validBits,
            config_.source.containerBytesPerSample,
            config_.device.channels,
            config_.device.validBits,
            config_.device.containerBytesPerSample,
            config_.bitPerfect);
    stats_.adapterMode = adapter_.mode;
    stats_.adapterReason = adapter_.reason;
    stats_.swrFrameBytes = plan_.swrOutputFrameBytes;
    stats_.swrOutputBufferBytes = plan_.outputBufferBytes;

    if (config_.transport != RawAudioTransportKind::Pcm) {
        stats_.lastError = "transport-bypasses-raw-pcm-pipeline";
        stats_.configured = false;
        return false;
    }
    if (stats_.sourceFrameBytes <= 0 || stats_.deviceFrameBytes <= 0) {
        stats_.lastError = "invalid-frame-size";
        stats_.configured = false;
        return false;
    }
    if (!adapter_.supported) {
        stats_.lastError = adapter_.reason;
        stats_.configured = false;
        return false;
    }

    stats_.resamplerRequired = plan_.required;
    if (plan_.required) {
        if (!configureResamplerLocked()) {
            stats_.configured = false;
            return false;
        }
    }

    stats_.configured = true;
    stats_.lastError.clear();
    stats_.lastReason = plan_.required ? "configured-with-swr" : "configured-no-swr";
    return true;
}

bool RawPcmPipeline::configureResamplerLocked() {
    const int sourceRate = std::max(1, config_.source.sampleRate);
    const int deviceRate = std::max(1, config_.device.sampleRate);
    const int sourceChannels = std::max(1, config_.source.channels);
    const int deviceChannels = std::max(1, config_.device.channels);

    const AVSampleFormat inFmt = bitDepthToAvFormat(
            config_.source.validBits, config_.source.containerBytesPerSample);
    const AVSampleFormat outFmt = bitDepthToAvFormat(
            config_.source.validBits, config_.source.containerBytesPerSample);

    const int64_t inLayout = av_get_default_channel_layout(sourceChannels);
    const int64_t outLayout = av_get_default_channel_layout(deviceChannels);

    swr_ = swr_alloc_set_opts(
            nullptr,
            outLayout, outFmt, deviceRate,
            inLayout, inFmt, sourceRate,
            0, nullptr);
    if (swr_ == nullptr) {
        stats_.lastError = "swr_alloc_set_opts-failed";
        return false;
    }

    // Same quality choices as the original native engine: larger filter, more
    // phases, no linear interpolation, and a high cutoff.  Keep them centralized
    // so resampling behaviour is not silently changed during the UAC split.
    av_opt_set_int(swr_, "filter_size", 32, 0);
    av_opt_set_int(swr_, "phase_shift", 12, 0);
    av_opt_set_int(swr_, "linear_interp", 0, 0);
    av_opt_set_double(swr_, "cutoff", 0.99, 0);

    const int ret = swr_init(swr_);
    if (ret < 0) {
        stats_.lastError = "swr_init-failed:" + std::to_string(ret);
        closeResamplerLocked();
        return false;
    }

    const int swrFrame = std::max(1, plan_.swrOutputFrameBytes);
    int bufferBytes = plan_.outputBufferBytes;
    if (bufferBytes <= 0) {
        const int seconds = std::max(1, config_.outputBufferSeconds);
        bufferBytes = std::max(1, deviceRate * swrFrame * seconds);
    }
    bufferBytes = alignDown(bufferBytes, swrFrame);
    if (bufferBytes <= 0) bufferBytes = swrFrame * 1024;
    swrOutputBuffer_.assign(static_cast<size_t>(bufferBytes), 0);
    stats_.swrOutputBufferBytes = bufferBytes;
    stats_.resamplerInitialized = true;
    return true;
}

void RawPcmPipeline::closeResamplerLocked() {
    if (swr_ != nullptr) {
        swr_free(&swr_);
        swr_ = nullptr;
    }
    swrOutputBuffer_.clear();
    stats_.resamplerInitialized = false;
}

RawPcmPipelineProcessResult RawPcmPipeline::process(
        const uint8_t* input,
        int inputBytes,
        uint8_t* output,
        int outputCapacityBytes) {
    RawPcmPipelineProcessResult r{};
    if (!stats_.configured) {
        r.errorCode = -1;
        r.reason = "pipeline-not-configured";
        stats_.lastError = r.reason;
        return r;
    }
    if (input == nullptr || output == nullptr || inputBytes < 0 || outputCapacityBytes < 0) {
        r.errorCode = -2;
        r.reason = "invalid-buffer";
        stats_.lastError = r.reason;
        return r;
    }

    stats_.processCalls += 1;
    stats_.totalInputBytes += inputBytes;
    stats_.lastInputBytes = inputBytes;

    const int srcFrame = std::max(1, stats_.sourceFrameBytes);
    const int dstFrame = std::max(1, stats_.deviceFrameBytes);
    const int outCapacity = alignDown(outputCapacityBytes, dstFrame);
    if (inputBytes <= 0 || outCapacity <= 0) {
        stats_.zeroOutputCalls += 1;
        r.reason = outCapacity <= 0 ? "no-device-output-capacity" : "empty-input";
        stats_.lastReason = r.reason;
        return r;
    }

    int inputFrames = inputBytes / srcFrame;
    r.droppedRemainderBytes = inputBytes - inputFrames * srcFrame;
    if (r.droppedRemainderBytes != 0) {
        stats_.unalignedInputCalls += 1;
    }
    if (inputFrames <= 0) {
        r.reason = "input-smaller-than-source-frame";
        stats_.zeroOutputCalls += 1;
        stats_.lastReason = r.reason;
        stats_.lastDroppedRemainderBytes = r.droppedRemainderBytes;
        return r;
    }

    if (!plan_.required) {
        const int maxFramesByOut = outCapacity / dstFrame;
        inputFrames = std::min(inputFrames, maxFramesByOut);
        const int sourceBytes = inputFrames * srcFrame;
        const int produced = adaptPcmToDeviceLocked(input, sourceBytes, output, outCapacity);
        if (produced < 0) {
            r.errorCode = produced;
            r.reason = stats_.lastError;
            stats_.adapterErrorCalls += 1;
            return r;
        }
        r.consumedInputBytes = sourceBytes;
        r.producedDeviceBytes = produced;
        r.reason = "adapter-only";
    } else {
        if (swr_ == nullptr || !stats_.resamplerInitialized) {
            r.errorCode = -10;
            r.reason = "swr-not-initialized";
            stats_.swrErrorCalls += 1;
            stats_.lastError = r.reason;
            return r;
        }

        const int swrFrame = std::max(1, stats_.swrFrameBytes);
        const int maxDeviceFrames = std::min(outCapacity / dstFrame,
                static_cast<int>(swrOutputBuffer_.size()) / swrFrame);
        if (maxDeviceFrames <= 0) {
            r.reason = "no-swr-output-capacity";
            stats_.zeroOutputCalls += 1;
            stats_.lastReason = r.reason;
            return r;
        }

        const int64_t delay = swr_get_delay(swr_, std::max(1, config_.source.sampleRate));
        const int64_t estimatedOut = av_rescale_rnd(
                delay + inputFrames,
                std::max(1, config_.device.sampleRate),
                std::max(1, config_.source.sampleRate),
                AV_ROUND_UP);
        if (estimatedOut > maxDeviceFrames) {
            int64_t allowedInput = av_rescale_rnd(
                    maxDeviceFrames,
                    std::max(1, config_.source.sampleRate),
                    std::max(1, config_.device.sampleRate),
                    AV_ROUND_DOWN);
            if (allowedInput <= 0) {
                r.reason = "swr-input-would-overflow-output";
                stats_.zeroOutputCalls += 1;
                stats_.lastReason = r.reason;
                return r;
            }
            inputFrames = static_cast<int>(std::min<int64_t>(allowedInput, inputFrames));
        }

        const uint8_t* inBuf[1] = { input };
        uint8_t* outBuf[1] = { swrOutputBuffer_.data() };
        const int outSamples = swr_convert(
                swr_, outBuf, maxDeviceFrames,
                inBuf, inputFrames);
        const int sourceBytes = inputFrames * srcFrame;
        r.consumedInputBytes = sourceBytes;
        if (outSamples <= 0) {
            // swr may legitimately buffer initial input.  Consume the aligned
            // source frames exactly like the original engine did, but report
            // zero output so the caller does not write garbage into the FIFO.
            r.reason = outSamples < 0 ? "swr-convert-error" : "swr-buffered";
            if (outSamples < 0) {
                r.errorCode = outSamples;
                stats_.swrErrorCalls += 1;
            } else {
                stats_.zeroOutputCalls += 1;
            }
        } else {
            const int swrBytes = outSamples * swrFrame;
            const int produced = adaptPcmToDeviceLocked(
                    swrOutputBuffer_.data(), swrBytes, output, outCapacity);
            if (produced < 0) {
                r.errorCode = produced;
                r.reason = stats_.lastError;
                stats_.adapterErrorCalls += 1;
                return r;
            }
            r.producedDeviceBytes = produced;
            r.swrOutputFrames = outSamples;
            r.reason = "swr-then-adapter";
        }
    }

    stats_.totalConsumedInputBytes += r.consumedInputBytes;
    stats_.totalProducedDeviceBytes += r.producedDeviceBytes;
    stats_.totalDroppedRemainderBytes += r.droppedRemainderBytes;
    stats_.totalSWRFrames += r.swrOutputFrames;
    if (r.producedDeviceBytes == 0) stats_.zeroOutputCalls += 1;
    stats_.lastConsumedInputBytes = r.consumedInputBytes;
    stats_.lastProducedDeviceBytes = r.producedDeviceBytes;
    stats_.lastDroppedRemainderBytes = r.droppedRemainderBytes;
    stats_.lastSWROutputFrames = r.swrOutputFrames;
    stats_.lastErrorCode = r.errorCode;
    stats_.lastReason = r.reason;
    if (r.errorCode == 0) stats_.lastError.clear();
    return r;
}

int RawPcmPipeline::adaptPcmToDeviceLocked(
        const uint8_t* input,
        int inputBytes,
        uint8_t* output,
        int outputCapacityBytes) {
    const int srcFrame = plan_.required ? std::max(1, stats_.swrFrameBytes) : std::max(1, stats_.sourceFrameBytes);
    const int dstFrame = std::max(1, stats_.deviceFrameBytes);
    const int frames = inputBytes / srcFrame;
    const int maxFrames = outputCapacityBytes / dstFrame;
    const int useFrames = std::min(frames, maxFrames);
    if (useFrames <= 0) return 0;

    switch (adapter_.mode) {
        case RawPcmAdapterMode::None:
        case RawPcmAdapterMode::Pcm24In32ToContainer32:
            return copyExactLocked(input, useFrames * srcFrame, output, outputCapacityBytes);
        case RawPcmAdapterMode::S16ToS24:
            return convertS16ToPacked24Locked(input, useFrames, output, outputCapacityBytes);
        case RawPcmAdapterMode::S16ToS32:
            return convertS16To24In32Locked(input, useFrames, output, outputCapacityBytes);
        case RawPcmAdapterMode::S24ToS32:
        case RawPcmAdapterMode::Packed24ToPcm24In32:
            return convertPacked24To24In32Locked(input, useFrames, output, outputCapacityBytes);
        case RawPcmAdapterMode::S32ToS24:
        case RawPcmAdapterMode::Pcm24In32ToPacked24:
            return convert24In32ToPacked24Locked(input, useFrames, output, outputCapacityBytes);
        case RawPcmAdapterMode::S32ToS24InS32:
            return convert32To24In32Locked(input, useFrames, output, outputCapacityBytes);
        default:
            stats_.lastError = "unsupported-adapter-mode";
            return -20;
    }
}

int RawPcmPipeline::copyExactLocked(const uint8_t* input, int inputBytes, uint8_t* output, int outputCapacityBytes) const {
    const int bytes = std::min(inputBytes, outputCapacityBytes);
    if (bytes > 0) std::memcpy(output, input, static_cast<size_t>(bytes));
    return bytes;
}

int RawPcmPipeline::convertS16ToPacked24Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const {
    const int channels = std::max(1, config_.device.channels);
    const int needed = frames * channels * 3;
    if (needed > outputCapacityBytes) return -21;
    int out = 0;
    for (int f = 0; f < frames; ++f) {
        const uint8_t* frame = input + f * channels * 2;
        for (int ch = 0; ch < channels; ++ch) {
            const uint8_t* s = frame + ch * 2;
            output[out++] = 0x00;
            output[out++] = s[0];
            output[out++] = s[1];
        }
    }
    return out;
}

int RawPcmPipeline::convertS16To24In32Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const {
    const int channels = std::max(1, config_.device.channels);
    const int needed = frames * channels * 4;
    if (needed > outputCapacityBytes) return -22;
    int out = 0;
    for (int f = 0; f < frames; ++f) {
        const uint8_t* frame = input + f * channels * 2;
        for (int ch = 0; ch < channels; ++ch) {
            const uint8_t* s = frame + ch * 2;
            output[out++] = 0x00;
            output[out++] = 0x00;
            output[out++] = s[0];
            output[out++] = s[1];
        }
    }
    return out;
}

int RawPcmPipeline::convertPacked24To24In32Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const {
    const int channels = std::max(1, config_.device.channels);
    const int needed = frames * channels * 4;
    if (needed > outputCapacityBytes) return -23;
    int out = 0;
    for (int f = 0; f < frames; ++f) {
        const uint8_t* frame = input + f * channels * 3;
        for (int ch = 0; ch < channels; ++ch) {
            const uint8_t* s = frame + ch * 3;
            if (config_.source24In32ShiftBits == 8) {
                output[out++] = 0x00;
                output[out++] = s[0];
                output[out++] = s[1];
                output[out++] = s[2];
            } else {
                output[out++] = s[0];
                output[out++] = s[1];
                output[out++] = s[2];
                output[out++] = signByteFromPacked24(s);
            }
        }
    }
    return out;
}

int RawPcmPipeline::convert24In32ToPacked24Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const {
    const int channels = std::max(1, config_.device.channels);
    const int needed = frames * channels * 3;
    if (needed > outputCapacityBytes) return -24;
    int out = 0;
    for (int f = 0; f < frames; ++f) {
        const uint8_t* frame = input + f * channels * 4;
        for (int ch = 0; ch < channels; ++ch) {
            const uint8_t* s = frame + ch * 4;
            if (config_.source24In32ShiftBits == 8) {
                output[out++] = s[1];
                output[out++] = s[2];
                output[out++] = s[3];
            } else {
                output[out++] = s[0];
                output[out++] = s[1];
                output[out++] = s[2];
            }
        }
    }
    return out;
}

int RawPcmPipeline::convert32To24In32Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const {
    const int channels = std::max(1, config_.device.channels);
    const int needed = frames * channels * 4;
    if (needed > outputCapacityBytes) return -25;
    int out = 0;
    for (int f = 0; f < frames; ++f) {
        const uint8_t* frame = input + f * channels * 4;
        for (int ch = 0; ch < channels; ++ch) {
            const uint8_t* s = frame + ch * 4;
            output[out++] = 0x00;
            output[out++] = s[1];
            output[out++] = s[2];
            output[out++] = s[3];
        }
    }
    return out;
}

RawPcmPipelineStats RawPcmPipeline::snapshot() const {
    return stats_;
}

std::string RawPcmPipeline::summary() const {
    return describeRawPcmPipelineStats(stats_);
}

std::string describeRawPcmPipelineStats(const RawPcmPipelineStats& stats) {
    std::ostringstream os;
    os << "configured=" << (stats.configured ? "yes" : "no")
       << " transport=" << rawAudioTransportKindName(stats.transport)
       << " swrRequired=" << (stats.resamplerRequired ? "yes" : "no")
       << " swrReady=" << (stats.resamplerInitialized ? "yes" : "no")
       << " src=" << stats.sourceSampleRate << "Hz/" << stats.sourceChannels << "ch/"
       << stats.sourceBits << "bit/" << stats.sourceBytesPerSample << "B"
       << " dev=" << stats.deviceSampleRate << "Hz/" << stats.deviceChannels << "ch/"
       << stats.deviceBits << "bit/" << stats.deviceSubslotBytes << "B"
       << " srcFrame=" << stats.sourceFrameBytes
       << " swrFrame=" << stats.swrFrameBytes
       << " devFrame=" << stats.deviceFrameBytes
       << " adapter=" << rawPcmAdapterModeName(stats.adapterMode)
       << " adapterReason=" << stats.adapterReason
       << " calls=" << stats.processCalls
       << " in=" << stats.totalInputBytes
       << " consumed=" << stats.totalConsumedInputBytes
       << " produced=" << stats.totalProducedDeviceBytes
       << " rem=" << stats.totalDroppedRemainderBytes
       << " swrFrames=" << stats.totalSWRFrames
       << " unaligned=" << stats.unalignedInputCalls
       << " zeroOut=" << stats.zeroOutputCalls
       << " swrErr=" << stats.swrErrorCalls
       << " adapterErr=" << stats.adapterErrorCalls
       << " lastIn=" << stats.lastInputBytes
       << " lastConsumed=" << stats.lastConsumedInputBytes
       << " lastOut=" << stats.lastProducedDeviceBytes
       << " lastRem=" << stats.lastDroppedRemainderBytes
       << " lastSWR=" << stats.lastSWROutputFrames
       << " lastReason=" << stats.lastReason;
    if (!stats.lastError.empty()) os << " error=" << stats.lastError;
    return os.str();
}

} // namespace rawsmusic::usb
