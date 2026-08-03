#include "usb_uac20_policy.h"

namespace rawsmusic::usb {

int uac20DefaultSubslotBytes(int validBits, bool prefer24In32) {
    if (validBits <= 0) return 0;
    if (validBits == 24 && prefer24In32) return 4;
    return (validBits + 7) / 8;
}

bool uac20ShouldUseExplicitFeedback(const Uac20Params& params, bool descriptorHasFeedbackEndpoint) {
    if (!descriptorHasFeedbackEndpoint) return false;
    if (params.forbidLearnedNoFeedback) return true;
    return params.preferExplicitFeedback;
}

UacRecoveryAction uac20UnderOutputRecoveryAction(
        const Uac20Params& params,
        bool descriptorHasFeedbackEndpoint) {
    // UsbNotOutputting means OUT is not draining; it does not prove feedback is
    // wrong. When a feedback endpoint exists, prefer a full stream rebuild over
    // learning a no-feedback profile.
    if (descriptorHasFeedbackEndpoint && params.fullReopenOnNotOutputting) {
        return UacRecoveryAction::FullReopen;
    }
    if (params.resetAltBeforeStart) {
        return UacRecoveryAction::ResetAltAndRestart;
    }
    return UacRecoveryAction::LowerFormat;
}

const char* uac20PolicySummary(const Uac20Params& params, bool descriptorHasFeedbackEndpoint) {
    if (descriptorHasFeedbackEndpoint && params.forbidLearnedNoFeedback) {
        return "explicit-feedback-forced; no-feedback-learning-forbidden; under-output=full-reopen";
    }
    if (descriptorHasFeedbackEndpoint && params.preferExplicitFeedback) {
        return "explicit-feedback-preferred; no-feedback-fallback-late";
    }
    return "no-explicit-feedback; fixed-pacer-allowed";
}

} // namespace rawsmusic::usb
