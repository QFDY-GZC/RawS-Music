#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::usb {

// Best-effort scheduling helper for user-space USB audio threads.
//
// This mirrors the stable parts of the UAPP-style design: named native USB
// threads, very small timer slack, best-effort nice boost, and CPU-affinity
// selection based on big/mid/little clusters.  It intentionally does NOT call
// sched_setscheduler(SCHED_FIFO/SCHED_RR): those calls are frequently denied to
// normal apps and, on some OEM kernels, successful RT scheduling can starve
// other work badly enough to trigger watchdog-style instability.
struct UsbThreadScheduleSnapshot {
    int tid = -1;
    int requestedNice = 0;
    int setNiceRc = 0;
    int setNiceErrno = 0;
    int actualNice = 0;
    int actualNiceErrno = 0;
    int schedulerPolicy = -1;
    int schedulerErrno = 0;
    int affinityRc = 0;
    int affinityErrno = 0;
    int affinityCpuCount = 0;
    int affinityTargetFreqKHz = 0;
    int affinityHighestFreqKHz = 0;
    bool avoidedUniquePrimeCore = false;
    bool timerSlackRequested = false;
    bool threadNamed = false;
    std::string affinityMask;
};

UsbThreadScheduleSnapshot applyUsbThreadScheduling(
        const char* threadName,
        int requestedNice,
        bool preferStableBigCluster);

std::string formatUsbThreadScheduleSnapshot(const UsbThreadScheduleSnapshot& snapshot);

const char* linuxSchedulerPolicyName(int policy);

} // namespace rawsmusic::usb
