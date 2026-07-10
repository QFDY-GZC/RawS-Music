#pragma once

#include <string>

#include "raw_audio_safety.h"
#include "raw_uac20_format_matcher.h"
#include "raw_uac20_stream.h"
#include "usb_uac20_transfers.h"

namespace rawsmusic::usb {

// Step 93: migration bridge from the existing split Uac20Session facts to the
// new RawUac20StreamConfig. This object intentionally does not submit
// USB transfers. It only standardizes every numeric choice that a future
// RawUac20Stream playback path will consume.
struct RawUac20StreamBuildInput {
    Uac20AltSnapshot selectedAlt{};
    RawUac20FormatMatchResult formatMatch{};
    Uac20OutTransferPlan outTransferPlan{};

    int sampleRate = 0;
    int channels = 0;
    int validBits = 0;
    int subslotBytes = 0;
    int frameBytes = 0;
    int bytesPerSecond = 0;

    int outEndpoint = 0;
    int feedbackEndpoint = 0;
    bool explicitFeedback = false;
    bool feedbackRequired = false;
    bool allowFeedback = true;

    bool allowOutSubmit = false; // stays false until the real-path switch step
    bool allowZeroFill = true;
    bool dynamicPacketSizing = true;
    bool autoResubmit = true;
    bool debugSmokeTest = false;

    int timeoutMs = 1000;
    int cancelWaitMs = 1000;
    RawAudioSafetyPolicy safetyPolicy = defaultRawAudioSafetyPolicy();
};

struct RawUac20StreamBuildResult {
    bool built = false;
    bool dryRunOnly = true;
    RawUac20StreamConfig config{};
    RawUac20QueueSizing queueSizing{};
    std::string reason;
    std::string summary;
};

RawUac20StreamBuildResult buildRawUac20StreamConfig(
        const RawUac20StreamBuildInput& input);

std::string describeRawUac20StreamBuildInput(const RawUac20StreamBuildInput& input);
std::string describeRawUac20StreamBuildResult(const RawUac20StreamBuildResult& result);

} // namespace rawsmusic::usb
