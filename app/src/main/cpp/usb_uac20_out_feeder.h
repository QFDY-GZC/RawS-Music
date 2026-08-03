#pragma once

#include <cstdint>
#include <string>

#include "usb_uac20_write_ring.h"

namespace rawsmusic::usb {

// Dry-run feeder: evaluates whether the shadow write ring could feed a real
// OUT ring without underflow. This stage does NOT submit transfers.
struct Uac20OutFeederConfig {
    int transferBytes = 0;
    int maxConcurrentTransfers = 8;
    int frameBytes = 0;
};

struct Uac20OutFeederStats {
    bool initialized = false;
    bool dryRunOnly = true;
    bool ready = false;
    bool underflowRisk = false;
    int ringLevelBytes = 0;
    int transferBudgetBytes = 0;
    int wouldSubmitTransfers = 0;
    int scheduledBytes = 0;
    int scheduledFrames = 0;
    int alignmentRemainder = 0;
    bool attempted = false;
    bool realFeederReady = false;
    bool debugOnly = true;
    int targetTransferBytes = 0;
    int targetQueueBytes = 0;
    int prebufferTargetBytes = 0;
    int prebufferMissingBytes = 0;
    int feederFrameBytes = 0;
    std::string summary;
};

class Uac20ShadowToRealOutFeeder {
public:
    bool configure(const Uac20OutFeederConfig& config);
    Uac20OutFeederStats evaluate(const Uac20WriteRingStats& ringStats);
    Uac20OutFeederStats snapshot() const;

    void updateDryRun(
            const Uac20WriteRingStats& ring,
            int outRingTransferBytes,
            int outRingQueueBytes,
            bool outRingReadyForFeeder,
            int schedulerPacketCount,
            int schedulerNominalPacketBytes,
            bool schedulerInitialized) {
        Uac20OutFeederConfig cfg;
        cfg.transferBytes = outRingTransferBytes;
        cfg.maxConcurrentTransfers = schedulerPacketCount > 0 ? schedulerPacketCount : 8;
        cfg.frameBytes = feederFrameBytesFromConfig();
        configure(cfg);
        evaluate(ring);
    }

    void prepareRealFeederPlan(
            const Uac20WriteRingStats& ring,
            int outRingTransferBytes,
            int outRingQueueBytes,
            bool outRingReadyForFeeder,
            int schedulerInitialized,
            int prebufferMs);

private:
    int feederFrameBytesFromConfig() const { return config_.frameBytes; }

private:
    Uac20OutFeederConfig config_{};
    Uac20OutFeederStats stats_{};
};

std::string describeUac20OutFeederStats(const Uac20OutFeederStats& stats);

} // namespace rawsmusic::usb
