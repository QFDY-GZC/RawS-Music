#include "usb_uac20_out_feeder.h"

#include <algorithm>
#include <sstream>

namespace rawsmusic::usb {

bool Uac20ShadowToRealOutFeeder::configure(const Uac20OutFeederConfig& config) {
    config_ = config;
    stats_ = Uac20OutFeederStats{};
    stats_.dryRunOnly = true;

    if (config.transferBytes <= 0 || config.frameBytes <= 0) {
        stats_.summary = "invalid transfer/frame bytes";
        return false;
    }
    if (config.maxConcurrentTransfers <= 0) {
        stats_.summary = "invalid max concurrent transfers";
        return false;
    }

    stats_.initialized = true;
    stats_.transferBudgetBytes = config.transferBytes;
    return true;
}

Uac20OutFeederStats Uac20ShadowToRealOutFeeder::evaluate(const Uac20WriteRingStats& ringStats) {
    if (!stats_.initialized) return stats_;

    stats_ = Uac20OutFeederStats{};
    stats_.initialized = true;
    stats_.dryRunOnly = true;
    stats_.transferBudgetBytes = config_.transferBytes;

    stats_.ringLevelBytes = ringStats.levelBytes;
    if (config_.transferBytes <= 0 || config_.frameBytes <= 0) {
        stats_.summary = "not-configured";
        return stats_;
    }

    const int maxTransfers = config_.maxConcurrentTransfers;
    const int transfersFromLevel = ringStats.levelBytes / config_.transferBytes;
    stats_.wouldSubmitTransfers = std::min(maxTransfers, transfersFromLevel);
    stats_.scheduledBytes = stats_.wouldSubmitTransfers * config_.transferBytes;
    stats_.scheduledFrames = config_.frameBytes > 0 ? stats_.scheduledBytes / config_.frameBytes : 0;
    stats_.alignmentRemainder = ringStats.levelBytes % config_.frameBytes;

    stats_.underflowRisk = stats_.wouldSubmitTransfers == 0 && ringStats.levelBytes > 0;
    stats_.ready = stats_.wouldSubmitTransfers > 0 && stats_.alignmentRemainder == 0;

    std::ostringstream os;
    os << "initialized=yes dryRun=yes ready=" << (stats_.ready ? "yes" : "no")
       << " underflowRisk=" << (stats_.underflowRisk ? "yes" : "no")
       << " ringLevel=" << stats_.ringLevelBytes
       << " transferBudget=" << stats_.transferBudgetBytes
       << " wouldSubmit=" << stats_.wouldSubmitTransfers
       << " scheduledBytes=" << stats_.scheduledBytes
       << " scheduledFrames=" << stats_.scheduledFrames
       << " alignmentRem=" << stats_.alignmentRemainder;
    stats_.summary = os.str();
    return stats_;
}

Uac20OutFeederStats Uac20ShadowToRealOutFeeder::snapshot() const {
    return stats_;
}

void Uac20ShadowToRealOutFeeder::prepareRealFeederPlan(
        const Uac20WriteRingStats& ring,
        int outRingTransferBytes,
        int outRingQueueBytes,
        bool outRingReadyForFeeder,
        int schedulerInitialized,
        int prebufferMs) {
    Uac20OutFeederConfig cfg;
    cfg.transferBytes = outRingTransferBytes > 0 ? outRingTransferBytes : config_.transferBytes;
    cfg.maxConcurrentTransfers = config_.maxConcurrentTransfers;
    cfg.frameBytes = config_.frameBytes;
    configure(cfg);
    evaluate(ring);

    stats_.attempted = true;
    stats_.dryRunOnly = false;
    stats_.debugOnly = true;
    stats_.realFeederReady = stats_.ready && outRingReadyForFeeder && schedulerInitialized != 0 && ring.initialized;
    stats_.targetTransferBytes = outRingTransferBytes;
    stats_.targetQueueBytes = outRingQueueBytes;
    stats_.feederFrameBytes = config_.frameBytes;

    const int prebufferTransfers = prebufferMs > 0 ? std::max(1, prebufferMs / 2) : 1;
    stats_.prebufferTargetBytes = std::max(outRingTransferBytes, outRingTransferBytes * prebufferTransfers);
    stats_.prebufferMissingBytes = std::max(0, stats_.prebufferTargetBytes - ring.levelBytes);
    stats_.underflowRisk = stats_.underflowRisk || stats_.prebufferMissingBytes > 0;
    stats_.summary = describeUac20OutFeederStats(stats_);
}

std::string describeUac20OutFeederStats(const Uac20OutFeederStats& s) {
    std::ostringstream os;
    os << "initialized=" << (s.initialized ? "yes" : "no")
       << " attempted=" << (s.attempted ? "yes" : "no")
       << " dryRun=" << (s.dryRunOnly ? "yes" : "no")
       << " ready=" << (s.ready ? "yes" : "no")
       << " realFeederReady=" << (s.realFeederReady ? "yes" : "no")
       << " underflowRisk=" << (s.underflowRisk ? "yes" : "no")
       << " ringLevel=" << s.ringLevelBytes
       << " transferBudget=" << s.transferBudgetBytes
       << " wouldSubmit=" << s.wouldSubmitTransfers
       << " scheduledBytes=" << s.scheduledBytes
       << " scheduledFrames=" << s.scheduledFrames
       << " alignmentRem=" << s.alignmentRemainder
       << " targetTransferBytes=" << s.targetTransferBytes
       << " targetQueueBytes=" << s.targetQueueBytes
       << " prebufferTarget=" << s.prebufferTargetBytes
       << " prebufferMissing=" << s.prebufferMissingBytes
       << " frameBytes=" << s.feederFrameBytes;
    return os.str();
}

} // namespace rawsmusic::usb
