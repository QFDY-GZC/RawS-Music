#include "usb_uac20_packet_scheduler.h"

#include <sstream>

namespace rawsmusic::usb {

const char* uac20PacketSchedulerModeName(Uac20PacketSchedulerMode mode) {
    switch (mode) {
        case Uac20PacketSchedulerMode::Fixed: return "Fixed";
        case Uac20PacketSchedulerMode::ExplicitFeedbackGuided: return "ExplicitFeedbackGuided";
        case Uac20PacketSchedulerMode::AsyncAdjust: return "AsyncAdjust";
    }
    return "Unknown";
}

bool Uac20PacketScheduler::configure(const Uac20PacketSchedulerConfig& config) {
    config_ = config;
    stats_ = Uac20PacketSchedulerStats{};

    if (config.packetCount <= 0 || config.nominalPacketBytes <= 0) {
        stats_.summary = "invalid packet count or nominal bytes";
        return false;
    }

    stats_.initialized = true;
    stats_.mode = config.mode;
    stats_.modeName = uac20PacketSchedulerModeName(config.mode);
    stats_.explicitFeedback = config.explicitFeedback;
    stats_.feedbackLocked = config.feedbackLocked;
    stats_.packetCount = config.packetCount;
    stats_.nominalPacketBytes = config.nominalPacketBytes;
    stats_.packetPattern.resize(static_cast<size_t>(config.packetCount));

    for (int i = 0; i < config.packetCount; ++i) {
        stats_.packetPattern[static_cast<size_t>(i)] = config.nominalPacketBytes;
    }

    std::ostringstream ps;
    ps << "[";
    for (size_t i = 0; i < stats_.packetPattern.size(); ++i) {
        if (i) ps << ",";
        ps << stats_.packetPattern[i];
    }
    ps << "]";
    stats_.patternSummary = ps.str();

    std::ostringstream os;
    os << "initialized=yes mode=" << stats_.modeName
       << " explicitFeedback=" << (stats_.explicitFeedback ? "yes" : "no")
       << " feedbackLocked=" << (stats_.feedbackLocked ? "yes" : "no")
       << " packetCount=" << stats_.packetCount
       << " nominalBytes=" << stats_.nominalPacketBytes
       << " pattern=" << stats_.patternSummary;
    stats_.summary = os.str();
    return true;
}

Uac20PacketSchedulerStats Uac20PacketScheduler::snapshot() const {
    return stats_;
}

std::string describeUac20PacketSchedulerStats(const Uac20PacketSchedulerStats& stats) {
    return stats.summary;
}

} // namespace rawsmusic::usb
