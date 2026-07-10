#include "usb_uac20_real_out_ring.h"

#include <sstream>

namespace rawsmusic::usb {

bool Uac20RealOutRing::configure(const Uac20RealOutRingConfig& config) {
    config_ = config;
    stats_ = Uac20RealOutRingStats{};
    stats_.dryRunOnly = true;

    if (config.endpoint <= 0) {
        stats_.summary = "invalid endpoint";
        return false;
    }
    if (config.transferCount <= 0 || config.packetsPerTransfer <= 0) {
        stats_.summary = "invalid transfer/packet count";
        return false;
    }
    if (config.transferBytes <= 0 || config.frameBytes <= 0) {
        stats_.summary = "invalid transfer/frame bytes";
        return false;
    }

    stats_.initialized = true;
    stats_.endpoint = config.endpoint;
    stats_.transferCount = config.transferCount;
    stats_.packetsPerTransfer = config.packetsPerTransfer;
    stats_.transferBytes = config.transferBytes;
    stats_.queueBytes = config.queueBytes > 0 ? config.queueBytes : config.transferCount * config.transferBytes;
    stats_.preallocatedBytes = config.preallocatedBytes > 0 ? config.preallocatedBytes : stats_.queueBytes;
    stats_.frameBytes = config.frameBytes;
    stats_.readyForFeeder = true;

    std::ostringstream os;
    os << "initialized=yes dryRun=yes endpoint=0x" << std::hex << (config.endpoint & 0xff)
       << std::dec << " transfers=" << stats_.transferCount
       << " packets=" << stats_.packetsPerTransfer
       << " transferBytes=" << stats_.transferBytes
       << " queueBytes=" << stats_.queueBytes
       << " preallocated=" << stats_.preallocatedBytes
       << " frameBytes=" << stats_.frameBytes
       << " readyForFeeder=yes";
    stats_.summary = os.str();
    return true;
}

Uac20RealOutRingStats Uac20RealOutRing::snapshot() const {
    return stats_;
}

std::string describeUac20RealOutRingStats(const Uac20RealOutRingStats& stats) {
    return stats.summary;
}

} // namespace rawsmusic::usb
