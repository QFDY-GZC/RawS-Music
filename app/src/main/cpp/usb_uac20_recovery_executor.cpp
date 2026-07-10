#include "usb_uac20_recovery_executor.h"

#include <sstream>

namespace rawsmusic::usb {
namespace {

void appendReason(std::string& out, const char* reason) {
    if (out.empty()) out = reason;
    else {
        out += ";";
        out += reason;
    }
}

} // namespace

Uac20RecoveryExecutorResult evaluateUac20RecoveryExecutor(const Uac20RecoveryExecutorInput& input) {
    Uac20RecoveryExecutorResult r;
    r.initialized = true;
    r.enabled = input.executionEnabled;
    r.candidateAction = input.candidateAction;
    r.destructive = input.requiresClose || input.requiresReopen;
    r.sameSessionRestartEligible = input.sessionOpened && input.interfacesClaimed && !input.requiresReopen;
    r.fullReopenEligible = input.sessionOpened && input.requiresReopen;
    r.stepBudget = 0;
    if (input.requiresStop) r.stepBudget += 1;
    if (input.requiresClose) r.stepBudget += 1;
    if (input.requiresReopen) r.stepBudget += 1;
    if (input.requiresAltReset) r.stepBudget += 1;
    if (input.requiresClockSet) r.stepBudget += 1;
    if (input.requiresPlaybackAlt) r.stepBudget += 1;
    if (input.requiresFeedbackRestart) r.stepBudget += 1;
    if (input.requiresOutRestart) r.stepBudget += 1;

    if (!input.executionEnabled) appendReason(r.blockedReason, "debug-execution-disabled");
    if (!input.sessionOpened) appendReason(r.blockedReason, "session-not-opened");
    if (input.candidateAction == 0) appendReason(r.blockedReason, "no-candidate-action");
    if (!input.explicitFeedbackSelected) appendReason(r.blockedReason, "explicit-feedback-not-selected");

    r.attempted = input.executionEnabled && input.candidateAction != 0;
    r.executed = false; // 0052-0059 still does not mutate USB ownership from the executor.
    r.blocked = !r.attempted || !r.blockedReason.empty();
    if (r.blockedReason.empty()) r.blockedReason = "armed-dry-run-only";
    r.summary = describeUac20RecoveryExecutorResult(r);
    return r;
}

std::string describeUac20RecoveryExecutorResult(const Uac20RecoveryExecutorResult& r) {
    std::ostringstream os;
    os << "initialized=" << (r.initialized ? "yes" : "no")
       << " enabled=" << (r.enabled ? "yes" : "no")
       << " attempted=" << (r.attempted ? "yes" : "no")
       << " executed=" << (r.executed ? "yes" : "no")
       << " blocked=" << (r.blocked ? "yes" : "no")
       << " action=" << r.candidateAction
       << " sameSessionEligible=" << (r.sameSessionRestartEligible ? "yes" : "no")
       << " fullReopenEligible=" << (r.fullReopenEligible ? "yes" : "no")
       << " destructive=" << (r.destructive ? "yes" : "no")
       << " stepBudget=" << r.stepBudget
       << " reason=" << r.blockedReason;
    return os.str();
}

} // namespace rawsmusic::usb
