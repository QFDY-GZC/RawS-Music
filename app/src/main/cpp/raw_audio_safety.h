#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::usb {

// RawSMusic native audio safety constants extracted from the original
// usb_audio_engine.cpp and made explicit so the split UAC2 path does not lose
// the hard-won anti-pop / anti-slowdown / anti-starvation behaviour.
struct RawAudioSafetyPolicy {
    int isoPacketsPerTransfer = 16;
    int maxTransfers = 256;
    int initialTransfers = 128;

    int startupFadeMs = 30;
    int sessionDefaultFadeMs = 80;
    int startupGuardMs = 350;
    float startupVolumeCap = 0.25f;
    float startupGuardCap = 0.25f;

    int hardwareVolumeRestoreAfterDataMs = 40;
    int hardwareVolumeRestoreMaxDelayMs = 250;
    int safeStartupDb = -35;

    int starvationEmptyTransferThreshold = 3;
    int starvationRecoveryMs = 25;

    int pcmWriteSoftLimitMs = 760;
    int dsdWriteSoftLimitMs = 900;

    int queueDepthMsNoFeedback = 320;
    int queueDepthMsFeedback = 180;
    int minTransferPool = 24;
};

struct RawFrameAlignmentResult {
    bool valid = false;
    bool aligned = false;
    int frameBytes = 0;
    int inputBytes = 0;
    int alignedBytes = 0;
    int droppedTailBytes = 0;
    std::string reason;
};

struct RawUac20QueueSizing {
    int packetsPerTransfer = 16;
    int transferCount = 0;
    int queueBytes = 0;
    int queueMs = 0;
    int prebufferBytes = 0;
    int transferBytes = 0;
    bool explicitFeedback = false;
    std::string summary;
};

RawAudioSafetyPolicy defaultRawAudioSafetyPolicy();

int rawFrameAlignDown(int bytes, int frameBytes);
int rawFrameAlignUp(int bytes, int frameBytes);
bool rawIsFrameAligned(int bytes, int frameBytes);
RawFrameAlignmentResult rawValidateFrameAlignedBytes(int bytes, int frameBytes, const char* source);

// Time-based queue sizing mirrors the original native engine: no-feedback USB
// needs a deeper queue (~320ms) than feedback-driven paths (~180ms), but the
// pool is still bounded by the original 256 transfer cap.
RawUac20QueueSizing rawComputeUac20QueueSizing(
        int bytesPerSecond,
        int transferBytes,
        int frameBytes,
        bool explicitFeedback,
        const RawAudioSafetyPolicy& policy = defaultRawAudioSafetyPolicy());

int rawStartupPrebufferBytes(
        int bytesPerSecond,
        int transferBytes,
        int frameBytes,
        bool explicitFeedback,
        const RawAudioSafetyPolicy& policy = defaultRawAudioSafetyPolicy());

bool rawShouldRestoreHardwareVolumeAfterData(
        int64_t nowMs,
        int64_t firstDataMs,
        int64_t startMs,
        bool hasIsoCompletion,
        const RawAudioSafetyPolicy& policy = defaultRawAudioSafetyPolicy());

std::string describeRawAudioSafetyPolicy(const RawAudioSafetyPolicy& policy);
std::string describeRawUac20QueueSizing(const RawUac20QueueSizing& sizing);

} // namespace rawsmusic::usb
