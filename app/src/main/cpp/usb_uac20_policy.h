#pragma once

#include "usb_uac20_session.h"

namespace rawsmusic::usb {

// Conservative UAC2 policy helpers. These are intentionally kept outside
// usb_audio_engine.cpp so new transport/recovery decisions do not grow the
// legacy monolith.
int uac20DefaultSubslotBytes(int validBits, bool prefer24In32);
bool uac20ShouldUseExplicitFeedback(const Uac20Params& params, bool descriptorHasFeedbackEndpoint);
UacRecoveryAction uac20UnderOutputRecoveryAction(const Uac20Params& params, bool descriptorHasFeedbackEndpoint);
const char* uac20PolicySummary(const Uac20Params& params, bool descriptorHasFeedbackEndpoint);

} // namespace rawsmusic::usb
