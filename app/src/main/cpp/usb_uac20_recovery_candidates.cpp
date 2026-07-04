#include "usb_uac20_recovery_candidates.h"

#include <sstream>

namespace rawsmusic::usb {
namespace {

Uac20RecoveryCandidate makeCandidate(
        int index,
        Uac20RecoveryDecisionAction action,
        const char* label,
        int sr,
        int bits,
        int subslot,
        bool keepFb,
        const char* reason) {
    Uac20RecoveryCandidate c;
    c.index = index;
    c.action = action;
    c.label = label ? label : "";
    c.sampleRate = sr;
    c.bits = bits;
    c.subslotBytes = subslot;
    c.keepExplicitFeedback = keepFb;
    c.reason = reason ? reason : "";
    switch (action) {
        case Uac20RecoveryDecisionAction::FullReopenSameFormat:
            c.requireFullReopen = true;
            break;
        case Uac20RecoveryDecisionAction::ResetAltAndRestart:
            c.requireAltReset = true;
            break;
        case Uac20RecoveryDecisionAction::LowerFormatSameTransport:
            c.lowerFormat = true;
            break;
        case Uac20RecoveryDecisionAction::AndroidHalFallback:
            c.androidFallback = true;
            c.keepExplicitFeedback = false;
            c.disableFeedback = true;
            break;
        case Uac20RecoveryDecisionAction::MarkTransportLost:
            c.transportLost = true;
            break;
        default:
            break;
    }
    return c;
}

std::string describeCandidate(const Uac20RecoveryCandidate& c, bool includeNext) {
    std::ostringstream os;
    os << "#" << c.index
       << " " << c.label
       << " sr=" << c.sampleRate
       << " bits=" << c.bits
       << " subslot=" << c.subslotBytes
       << " keepFb=" << (c.keepExplicitFeedback ? "yes" : "no")
       << " fullReopen=" << (c.requireFullReopen ? "yes" : "no")
       << " resetAlt=" << (c.requireAltReset ? "yes" : "no")
       << " lowerFmt=" << (c.lowerFormat ? "yes" : "no")
       << " androidFallback=" << (c.androidFallback ? "yes" : "no")
       << " transportLost=" << (c.transportLost ? "yes" : "no");
    (void)includeNext;
    return os.str();
}

} // namespace

void Uac20RecoveryCandidatePlanner::initialize() {
    initialized_ = true;
    reset();
}

void Uac20RecoveryCandidatePlanner::reset() {
    candidates_.clear();
    selectedIndex_ = -1;
}

void Uac20RecoveryCandidatePlanner::build(
        Uac20RecoveryDecisionAction action,
        int currentSampleRate,
        int currentBits,
        int currentSubslotBytes,
        bool descriptorHasExplicitFeedback,
        bool keepExplicitFeedback) {
    candidates_.clear();
    selectedIndex_ = -1;
    if (!initialized_) return;

    const int sr = currentSampleRate > 0 ? currentSampleRate : 192000;
    const int bits = currentBits > 0 ? currentBits : 24;
    const int subslot = currentSubslotBytes > 0 ? currentSubslotBytes : 4;
    const bool keepFb = keepExplicitFeedback && descriptorHasExplicitFeedback;
    int idx = 0;

    switch (action) {
        case Uac20RecoveryDecisionAction::FullReopenSameFormat:
            candidates_.push_back(makeCandidate(idx++, action,
                    "same-format-full-reopen", sr, bits, subslot, keepFb,
                    "full reopen with same format, keep explicit feedback"));
            candidates_.push_back(makeCandidate(idx++, Uac20RecoveryDecisionAction::ResetAltAndRestart,
                    "reset-alt-restart", sr, bits, subslot, keepFb,
                    "reset alt and restart as lighter alternative"));
            // Lower-format candidates
            if (sr > 96000) candidates_.push_back(makeCandidate(idx++,
                    Uac20RecoveryDecisionAction::LowerFormatSameTransport,
                    "lower-format-96k", 96000, bits, subslot, keepFb,
                    "lower to 96k same transport"));
            candidates_.push_back(makeCandidate(idx++,
                    Uac20RecoveryDecisionAction::LowerFormatSameTransport,
                    "lower-format-48k", 48000, bits, subslot, keepFb,
                    "lower to 48k same transport"));
            candidates_.push_back(makeCandidate(idx++,
                    Uac20RecoveryDecisionAction::LowerFormatSameTransport,
                    "lower-format-48k-16bit", 48000, 16, 2, keepFb,
                    "lower to 48k/16bit as last-resort same transport"));
            candidates_.push_back(makeCandidate(idx++,
                    Uac20RecoveryDecisionAction::AndroidHalFallback,
                    "android-hal-fallback", sr, bits, subslot, false,
                    "fall back to Android HAL"));
            break;

        case Uac20RecoveryDecisionAction::ResetAltAndRestart:
            candidates_.push_back(makeCandidate(idx++, action,
                    "reset-alt-restart", sr, bits, subslot, keepFb,
                    "reset alt and restart with same format"));
            candidates_.push_back(makeCandidate(idx++,
                    Uac20RecoveryDecisionAction::FullReopenSameFormat,
                    "same-format-full-reopen", sr, bits, subslot, keepFb,
                    "full reopen as heavier alternative"));
            candidates_.push_back(makeCandidate(idx++,
                    Uac20RecoveryDecisionAction::AndroidHalFallback,
                    "android-hal-fallback", sr, bits, subslot, false,
                    "fall back to Android HAL"));
            break;

        case Uac20RecoveryDecisionAction::LowerFormatSameTransport:
            candidates_.push_back(makeCandidate(idx++, action,
                    "lower-format-primary", sr > 96000 ? 96000 : 48000, bits, subslot, keepFb,
                    "lower format same transport"));
            candidates_.push_back(makeCandidate(idx++,
                    Uac20RecoveryDecisionAction::LowerFormatSameTransport,
                    "lower-format-48k", 48000, bits, subslot, keepFb,
                    "lower to 48k"));
            candidates_.push_back(makeCandidate(idx++,
                    Uac20RecoveryDecisionAction::AndroidHalFallback,
                    "android-hal-fallback", sr, bits, subslot, false,
                    "fall back to Android HAL"));
            break;

        case Uac20RecoveryDecisionAction::AndroidHalFallback:
            candidates_.push_back(makeCandidate(idx++, action,
                    "android-hal-fallback", sr, bits, subslot, false,
                    "native choices exhausted, use Android HAL"));
            break;

        case Uac20RecoveryDecisionAction::MarkTransportLost:
            candidates_.push_back(makeCandidate(idx++, action,
                    "transport-lost", sr, bits, subslot, false,
                    "transport lost, wait for reattach"));
            break;

        default:
            candidates_.push_back(makeCandidate(idx++, action,
                    "no-action", sr, bits, subslot, keepFb,
                    "no recovery action needed"));
            break;
    }

    selectedIndex_ = candidates_.empty() ? -1 : 0;
}

Uac20RecoveryCandidatePlanStats Uac20RecoveryCandidatePlanner::snapshot() const {
    Uac20RecoveryCandidatePlanStats stats;
    stats.initialized = initialized_;
    stats.candidateCount = static_cast<int>(candidates_.size());
    stats.hasCandidates = !candidates_.empty();
    stats.selectedIndex = selectedIndex_;
    stats.hasSelected = selectedIndex_ >= 0 && selectedIndex_ < static_cast<int>(candidates_.size());
    if (stats.hasSelected) {
        stats.selected = candidates_[selectedIndex_];
    }

    std::ostringstream cl;
    for (size_t i = 0; i < candidates_.size(); ++i) {
        if (i > 0) cl << " | ";
        cl << describeCandidate(candidates_[i], false);
    }
    stats.candidateList = cl.str();

    std::ostringstream os;
    os << "initialized=" << (initialized_ ? "yes" : "no")
       << " candidates=" << stats.candidateCount
       << " selected=" << selectedIndex_;
    if (stats.hasSelected) {
        os << " action=" << uac20RecoveryDecisionName(stats.selected.action)
           << " sr=" << stats.selected.sampleRate
           << " bits=" << stats.selected.bits
           << " subslot=" << stats.selected.subslotBytes
           << " keepFb=" << (stats.selected.keepExplicitFeedback ? "yes" : "no")
           << " fullReopen=" << (stats.selected.requireFullReopen ? "yes" : "no")
           << " resetAlt=" << (stats.selected.requireAltReset ? "yes" : "no");
    }
    stats.report = os.str();
    return stats;
}

std::string describeUac20RecoveryCandidatePlanStats(const Uac20RecoveryCandidatePlanStats& stats) {
    return stats.report;
}

} // namespace rawsmusic::usb
