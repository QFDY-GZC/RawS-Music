#include "raw_uac20_stream_builder.h"

#include <algorithm>
#include <initializer_list>
#include <sstream>

namespace rawsmusic::usb {
namespace {

int firstPositive(std::initializer_list<int> values) {
    for (int v : values) {
        if (v > 0) return v;
    }
    return 0;
}

int fallbackIntervalsPerSecond(const Uac20OutTransferPlan& plan) {
    if (plan.intervalsPerSecond > 0) return plan.intervalsPerSecond;
    // The current Android/libusb path schedules full-speed/high-speed UAC OUT
    // as millisecond service windows. Keep the fallback explicit so it does not
    // become another hidden magic value in session code.
    return 1000;
}

} // namespace

RawUac20StreamBuildResult buildRawUac20StreamConfig(
        const RawUac20StreamBuildInput& input) {
    RawUac20StreamBuildResult result{};
    result.dryRunOnly = !input.allowOutSubmit;

    if (!input.formatMatch.matched) {
        result.reason = "format-match-not-ready";
        result.summary = describeRawUac20StreamBuildResult(result);
        return result;
    }
    if (!input.outTransferPlan.prepared) {
        result.reason = "out-transfer-plan-not-ready";
        result.summary = describeRawUac20StreamBuildResult(result);
        return result;
    }

    RawUac20StreamConfig config{};
    config.safetyPolicy = input.safetyPolicy;
    config.formatMatch = input.formatMatch;

    config.sampleRate = firstPositive({input.sampleRate, input.formatMatch.sampleRate});
    config.channels = firstPositive({input.channels, input.formatMatch.channels, input.selectedAlt.channels});
    config.validBits = firstPositive({input.validBits, input.formatMatch.validBits, input.selectedAlt.validBits});
    config.subslotBytes = firstPositive({input.subslotBytes, input.formatMatch.subslotBytes, input.selectedAlt.subslotBytes});
    config.frameBytes = firstPositive({input.frameBytes, input.formatMatch.frameBytes, config.channels * config.subslotBytes});
    config.bytesPerSecond = firstPositive({
            input.bytesPerSecond,
            input.formatMatch.bytesPerSecond,
            config.sampleRate * config.frameBytes});

    config.audioStreamingInterface = input.selectedAlt.interfaceNumber;
    config.altSetting = input.selectedAlt.altSetting;
    config.outEndpoint = firstPositive({
            input.outEndpoint,
            input.outTransferPlan.endpointAddress,
            input.selectedAlt.outEndpoint.address});
    config.feedbackEndpoint = firstPositive({
            input.feedbackEndpoint,
            input.selectedAlt.hasFeedbackEndpoint ? input.selectedAlt.feedbackEndpoint.address : 0});
    config.endpointMaxPacketSize = firstPositive({
            input.outTransferPlan.endpointMaxPacketSize,
            input.selectedAlt.outEndpoint.maxPacketSize});
    config.endpointInterval = firstPositive({
            input.outTransferPlan.endpointInterval,
            input.selectedAlt.outEndpoint.interval,
            1});
    config.intervalsPerSecond = fallbackIntervalsPerSecond(input.outTransferPlan);

    config.explicitFeedback = input.explicitFeedback && config.feedbackEndpoint != 0;
    config.feedbackRequired = input.feedbackRequired && config.explicitFeedback;
    config.allowFeedback = input.allowFeedback;
    config.allowOutSubmit = input.allowOutSubmit;
    config.allowZeroFill = input.allowZeroFill;
    config.dynamicPacketSizing = input.dynamicPacketSizing && !config.explicitFeedback;
    config.autoResubmit = input.autoResubmit;
    config.debugSmokeTest = input.debugSmokeTest;

    config.packetsPerTransfer = firstPositive({
            input.outTransferPlan.packetsPerTransfer,
            input.safetyPolicy.isoPacketsPerTransfer,
            16});
    config.transferBytes = firstPositive({input.outTransferPlan.transferBytes});
    if (config.transferBytes <= 0 && config.frameBytes > 0) {
        config.transferBytes = rawFrameAlignUp(config.frameBytes, config.frameBytes);
    }
    config.transferBytes = rawFrameAlignDown(config.transferBytes, std::max(1, config.frameBytes));
    if (config.transferBytes <= 0) {
        config.transferBytes = std::max(1, config.frameBytes);
    }

    result.queueSizing = rawComputeUac20QueueSizing(
            config.bytesPerSecond,
            config.transferBytes,
            config.frameBytes,
            config.explicitFeedback,
            input.safetyPolicy);

    config.transferCount = firstPositive({
            input.outTransferPlan.transferCount,
            result.queueSizing.transferCount});
    config.queueBytes = firstPositive({
            input.outTransferPlan.queueBytes,
            result.queueSizing.queueBytes,
            config.transferCount * config.transferBytes});
    config.queueBytes = rawFrameAlignUp(config.queueBytes, std::max(1, config.frameBytes));
    config.startupPrebufferBytes = firstPositive({
            result.queueSizing.prebufferBytes,
            rawStartupPrebufferBytes(
                    config.bytesPerSecond,
                    config.transferBytes,
                    config.frameBytes,
                    config.explicitFeedback,
                    input.safetyPolicy)});
    config.startupPrebufferBytes = std::min(
            rawFrameAlignUp(config.startupPrebufferBytes, std::max(1, config.frameBytes)),
            config.queueBytes > 0 ? config.queueBytes : config.startupPrebufferBytes);

    config.timeoutMs = std::max(1, input.timeoutMs);
    config.cancelWaitMs = std::max(1, input.cancelWaitMs);
    config.packetBytes = config.dynamicPacketSizing ? std::vector<int>{} : input.outTransferPlan.packetBytes;

    if (config.sampleRate <= 0 || config.channels <= 0 || config.frameBytes <= 0 ||
        config.bytesPerSecond <= 0 || config.outEndpoint == 0) {
        result.reason = "invalid-stream-config";
        result.config = config;
        result.summary = describeRawUac20StreamBuildResult(result);
        return result;
    }
    if (!config.dynamicPacketSizing && config.packetBytes.empty()) {
        result.reason = "static-packet-pattern-empty";
        result.config = config;
        result.summary = describeRawUac20StreamBuildResult(result);
        return result;
    }

    result.built = true;
    result.reason = "ok";
    result.config = config;
    result.summary = describeRawUac20StreamBuildResult(result);
    return result;
}

std::string describeRawUac20StreamBuildInput(const RawUac20StreamBuildInput& input) {
    std::ostringstream os;
    os << "sr=" << input.sampleRate
       << " ch=" << input.channels
       << " bits=" << input.validBits
       << " subslot=" << input.subslotBytes
       << " frame=" << input.frameBytes
       << " bps=" << input.bytesPerSecond
       << " iface=" << input.selectedAlt.interfaceNumber
       << " alt=" << input.selectedAlt.altSetting
       << " out=0x" << std::hex << input.outEndpoint
       << " fb=0x" << input.feedbackEndpoint << std::dec
       << " explicitFb=" << (input.explicitFeedback ? "yes" : "no")
       << " allowOut=" << (input.allowOutSubmit ? "yes" : "no")
       << " transferPlan=" << (input.outTransferPlan.prepared ? "yes" : "no")
       << " match=" << (input.formatMatch.matched ? "yes" : "no");
    return os.str();
}

std::string describeRawUac20StreamBuildResult(const RawUac20StreamBuildResult& result) {
    std::ostringstream os;
    os << "built=" << (result.built ? "yes" : "no")
       << " dryRunOnly=" << (result.dryRunOnly ? "yes" : "no")
       << " reason=" << result.reason;
    if (result.config.sampleRate > 0 || result.config.frameBytes > 0) {
        os << " config{" << describeRawUac20StreamConfig(result.config) << "}";
    }
    if (!result.queueSizing.summary.empty()) {
        os << " queue{" << result.queueSizing.summary << "}";
    }
    return os.str();
}

} // namespace rawsmusic::usb
