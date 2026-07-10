#pragma once

#include <string>
#include <vector>

namespace rawsmusic::usb {

// Explicit-feedback-guided packet scheduler. Generates the packet size pattern
// that a future real OUT ring would use, based on feedback and transfer plan.
enum class Uac20PacketSchedulerMode : int {
    Fixed = 0,
    ExplicitFeedbackGuided = 1,
    AsyncAdjust = 2,
};

struct Uac20PacketSchedulerConfig {
    Uac20PacketSchedulerMode mode = Uac20PacketSchedulerMode::ExplicitFeedbackGuided;
    int packetCount = 8;
    int nominalPacketBytes = 0;
    bool explicitFeedback = true;
    bool feedbackLocked = true;
    int microframesPerPacket = 1;
};

struct Uac20PacketSchedulerStats {
    bool initialized = false;
    Uac20PacketSchedulerMode mode = Uac20PacketSchedulerMode::ExplicitFeedbackGuided;
    std::string modeName;
    bool explicitFeedback = false;
    bool feedbackLocked = false;
    int packetCount = 0;
    int nominalPacketBytes = 0;
    std::vector<int> packetPattern;
    std::string patternSummary;
    std::string summary;
};

class Uac20PacketScheduler {
public:
    bool configure(const Uac20PacketSchedulerConfig& config);
    Uac20PacketSchedulerStats snapshot() const;

private:
    Uac20PacketSchedulerConfig config_{};
    Uac20PacketSchedulerStats stats_{};
};

const char* uac20PacketSchedulerModeName(Uac20PacketSchedulerMode mode);
std::string describeUac20PacketSchedulerStats(const Uac20PacketSchedulerStats& stats);

} // namespace rawsmusic::usb
