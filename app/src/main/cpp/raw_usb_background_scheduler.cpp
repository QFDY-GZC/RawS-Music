#include "raw_usb_background_scheduler.h"

#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <set>
#include <sstream>
#include <string>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sched.h>
#include <unistd.h>
#include <vector>
#include <pthread.h>

#define TAG "RawUsbScheduler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace rawsmusic::usb {
namespace {

int currentTid() {
    return static_cast<int>(syscall(SYS_gettid));
}

std::string cpuSetToString(const cpu_set_t& set) {
    std::ostringstream os;
    bool first = true;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (CPU_ISSET(cpu, &set)) {
            if (!first) os << ',';
            os << cpu;
            first = false;
        }
    }
    if (first) return "none";
    return os.str();
}

void setThreadNameBestEffort(const char* threadName, UsbThreadScheduleSnapshot* out) {
    if (threadName == nullptr || threadName[0] == '\0') return;
    char name[16]{};
    std::snprintf(name, sizeof(name), "%s", threadName);
    const int rc = pthread_setname_np(pthread_self(), name);
    if (rc == 0 && out) out->threadNamed = true;
}

} // namespace

const char* linuxSchedulerPolicyName(int policy) {
    switch (policy) {
        case SCHED_OTHER: return "OTHER";
        case SCHED_FIFO: return "FIFO";
        case SCHED_RR: return "RR";
#ifdef SCHED_BATCH
        case SCHED_BATCH: return "BATCH";
#endif
#ifdef SCHED_IDLE
        case SCHED_IDLE: return "IDLE";
#endif
        default: return "UNKNOWN";
    }
}

UsbThreadScheduleSnapshot applyUsbThreadScheduling(
        const char* threadName,
        int requestedNice,
        bool preferStableBigCluster) {
    UsbThreadScheduleSnapshot snapshot{};
    snapshot.tid = currentTid();
    snapshot.requestedNice = requestedNice;

    setThreadNameBestEffort(threadName, &snapshot);

#ifdef PR_SET_TIMERSLACK
    if (prctl(PR_SET_TIMERSLACK, 1, 0, 0, 0) == 0) {
        snapshot.timerSlackRequested = true;
    }
#endif

    errno = 0;
    snapshot.setNiceRc = setpriority(PRIO_PROCESS, snapshot.tid, requestedNice);
    snapshot.setNiceErrno = errno;

    // Do not force a performance-cluster mask here.  On OPlus/ColorOS the high
    // performance cluster may be parked while a media app is backgrounded.  A
    // thread pinned to that cluster then cannot service libusb even though its
    // event loop uses a 1 ms timeout. The scheduler does not select the highest-frequency
    // cluster this way; let Android migrate the worker inside the process cpuset.
    (void) preferStableBigCluster;
    snapshot.currentCpu = sched_getcpu();
    cpu_set_t allowed{};
    CPU_ZERO(&allowed);
    errno = 0;
    snapshot.affinityRc = sched_getaffinity(snapshot.tid, sizeof(cpu_set_t), &allowed);
    snapshot.affinityErrno = errno;
    if (snapshot.affinityRc == 0) {
        snapshot.affinityMask = cpuSetToString(allowed);
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (CPU_ISSET(cpu, &allowed)) ++snapshot.affinityCpuCount;
        }
    } else {
        snapshot.affinityMask = "query-failed";
    }

    errno = 0;
    snapshot.schedulerPolicy = sched_getscheduler(0);
    snapshot.schedulerErrno = errno;

    errno = 0;
    snapshot.actualNice = getpriority(PRIO_PROCESS, snapshot.tid);
    snapshot.actualNiceErrno = errno;

    LOGI("USB_THREAD_SCHED name=%s tid=%d niceReq=%d niceRc=%d niceErr=%d(%s) actualNice=%d actualNiceErr=%d "
         "policy=%s(%d) policyErr=%d affinityMode=inherited affinityQueryRc=%d affinityErr=%d(%s) allowed=%s currentCpu=%d timerSlack=%d named=%d",
         threadName ? threadName : "null",
         snapshot.tid,
         snapshot.requestedNice,
         snapshot.setNiceRc,
         snapshot.setNiceErrno,
         strerror(snapshot.setNiceErrno),
         snapshot.actualNice,
         snapshot.actualNiceErrno,
         linuxSchedulerPolicyName(snapshot.schedulerPolicy),
         snapshot.schedulerPolicy,
         snapshot.schedulerErrno,
         snapshot.affinityRc,
         snapshot.affinityErrno,
         strerror(snapshot.affinityErrno),
         snapshot.affinityMask.c_str(),
         snapshot.currentCpu,
         snapshot.timerSlackRequested ? 1 : 0,
         snapshot.threadNamed ? 1 : 0);

    return snapshot;
}

std::string formatUsbThreadScheduleSnapshot(const UsbThreadScheduleSnapshot& snapshot) {
    std::ostringstream os;
    os << "tid=" << snapshot.tid
       << " niceReq=" << snapshot.requestedNice
       << " niceRc=" << snapshot.setNiceRc
       << " niceErr=" << snapshot.setNiceErrno
       << " actualNice=" << snapshot.actualNice
       << " policy=" << linuxSchedulerPolicyName(snapshot.schedulerPolicy)
       << '(' << snapshot.schedulerPolicy << ')'
       << " affinityMode=inherited"
       << " affinityQueryRc=" << snapshot.affinityRc
       << " affinityErr=" << snapshot.affinityErrno
       << " allowed=" << snapshot.affinityMask
       << " currentCpu=" << snapshot.currentCpu
       << " timerSlack=" << (snapshot.timerSlackRequested ? 1 : 0)
       << " named=" << (snapshot.threadNamed ? 1 : 0);
    return os.str();
}

} // namespace rawsmusic::usb
