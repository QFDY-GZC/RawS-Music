#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::usb {

// Best-effort scheduling helper for user-space USB audio threads.
//
// Keep the stable parts of the native USB design: named worker threads, very small timer
// slack and a best-effort nice boost.  CPU affinity is intentionally inherited
// from Android's current cpuset instead of being forced to a performance cluster.
// Fixed big-core affinity can strand these threads when an OEM parks that cluster
// after the app goes to the background.  It intentionally does NOT call
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
    int currentCpu = -1;
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
