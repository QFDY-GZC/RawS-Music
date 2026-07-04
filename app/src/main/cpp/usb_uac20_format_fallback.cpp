#include "usb_uac20_format_fallback.h"

#include <sstream>

namespace rawsmusic::usb {
namespace {

void addCandidate(Uac20FormatFallbackPlan& plan, int sr, int bits, int subslot, int channels, const char* reason) {
    if (sr <= 0 || channels <= 0) return;
    if (!plan.candidates.empty()) {
        const auto& last = plan.candidates.back();
        if (last.sampleRate == sr && last.bits == bits && last.subslotBytes == subslot && last.channels == channels) return;
    }
    Uac20FormatFallbackCandidate c;
    c.index = static_cast<int>(plan.candidates.size());
    c.sampleRate = sr;
    c.bits = bits;
    c.subslotBytes = subslot;
    c.channels = channels;
    c.keepExplicitFeedback = true;
    c.reason = reason;
    plan.candidates.push_back(c);
}

} // namespace

Uac20FormatFallbackPlan buildUac20FormatFallbackPlan(const Uac20FormatFallbackInput& input) {
    Uac20FormatFallbackPlan plan;
    plan.initialized = true;
    plan.executionEnabled = input.executionEnabled;
    const int channels = input.channels > 0 ? input.channels : 2;
    const int currentBits = input.currentBits > 0 ? input.currentBits : 24;
    const int currentSubslot = input.currentSubslotBytes > 0 ? input.currentSubslotBytes : (currentBits == 24 ? 4 : 2);

    if (input.lowerFormatSuggested || input.currentSampleRate > 96000) {
        addCandidate(plan, 96000, currentBits, currentSubslot, channels, "halve-sample-rate");
    }
    if (input.currentSampleRate > 48000 || input.lowerFormatSuggested) {
        addCandidate(plan, 48000, currentBits, currentSubslot, channels, "safe-sample-rate");
    }
    if (currentBits > 16 || currentSubslot > 2) {
        addCandidate(plan, 48000, 16, 2, channels, "safe-16bit-container");
    }

    plan.candidateCount = static_cast<int>(plan.candidates.size());
    plan.hasCandidates = plan.candidateCount > 0;
    plan.selectedIndex = plan.hasCandidates ? 0 : -1;

    std::ostringstream list;
    for (size_t i = 0; i < plan.candidates.size(); ++i) {
        if (i) list << " | ";
        const auto& c = plan.candidates[i];
        list << "#" << c.index << " " << c.sampleRate << "/" << c.bits
             << "/subslot=" << c.subslotBytes << "/ch=" << c.channels
             << " keepExplicitFb=" << (c.keepExplicitFeedback ? "yes" : "no")
             << " reason=" << c.reason;
    }
    plan.candidateSummary = list.str();
    plan.summary = describeUac20FormatFallbackPlan(plan);
    return plan;
}

std::string describeUac20FormatFallbackPlan(const Uac20FormatFallbackPlan& plan) {
    std::ostringstream os;
    os << "initialized=" << (plan.initialized ? "yes" : "no")
       << " executionEnabled=" << (plan.executionEnabled ? "yes" : "no")
       << " candidates=" << plan.candidateCount
       << " selected=" << plan.selectedIndex;
    if (!plan.candidateSummary.empty()) os << " list={" << plan.candidateSummary << "}";
    return os.str();
}

} // namespace rawsmusic::usb
