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

struct CpuInfo {
    int cpu = -1;
    int maxFreqKHz = 0;
};

int currentTid() {
    return static_cast<int>(syscall(SYS_gettid));
}

bool parseIntFile(const std::string& path, int* out) {
    if (out == nullptr) return false;
    std::ifstream in(path);
    if (!in.good()) return false;
    long long value = 0;
    in >> value;
    if (!in.good() && !in.eof()) return false;
    if (value < 0 || value > INT_MAX) return false;
    *out = static_cast<int>(value);
    return true;
}

bool cpuOnline(int cpu) {
    if (cpu == 0) return true; // cpu0 normally has no online file.
    int online = 1;
    const std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/online";
    if (!parseIntFile(path, &online)) return true;
    return online != 0;
}

int readCpuMaxFreqKHz(int cpu) {
    const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/";
    int khz = 0;
    if (parseIntFile(base + "cpuinfo_max_freq", &khz) && khz > 0) return khz;
    if (parseIntFile(base + "scaling_max_freq", &khz) && khz > 0) return khz;
    return 0;
}

std::vector<CpuInfo> readCpus() {
    std::vector<CpuInfo> result;
    const long configured = sysconf(_SC_NPROCESSORS_CONF);
    const int count = configured > 0 ? static_cast<int>(configured) : 0;
    for (int cpu = 0; cpu < count && cpu < CPU_SETSIZE; ++cpu) {
        if (!cpuOnline(cpu)) continue;
        CpuInfo info{};
        info.cpu = cpu;
        info.maxFreqKHz = readCpuMaxFreqKHz(cpu);
        result.push_back(info);
    }
    return result;
}

int selectTargetFreq(const std::vector<CpuInfo>& cpus,
                     bool preferStableBigCluster,
                     bool* avoidedUniquePrimeCore,
                     int* highestFreqOut) {
    if (avoidedUniquePrimeCore) *avoidedUniquePrimeCore = false;
    if (highestFreqOut) *highestFreqOut = 0;
    if (cpus.empty()) return 0;

    int highest = 0;
    for (const auto& cpu : cpus) highest = std::max(highest, cpu.maxFreqKHz);
    if (highestFreqOut) *highestFreqOut = highest;
    if (highest <= 0) return 0;

    int highestCount = 0;
    for (const auto& cpu : cpus) {
        if (cpu.maxFreqKHz == highest) ++highestCount;
    }

    // UAPP-style detail: if there is a single prime core on a 5+ core SoC,
    // prefer the next cluster down.  USB event handling wants stable low-jitter
    // scheduling more than one-core peak frequency.
    if (preferStableBigCluster && highestCount == 1 && cpus.size() >= 5) {
        int second = 0;
        for (const auto& cpu : cpus) {
            if (cpu.maxFreqKHz > second && cpu.maxFreqKHz < highest) {
                second = cpu.maxFreqKHz;
            }
        }
        if (second > 0) {
            if (avoidedUniquePrimeCore) *avoidedUniquePrimeCore = true;
            return second;
        }
    }
    return highest;
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

int countCpuSet(const cpu_set_t& set) {
    int count = 0;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (CPU_ISSET(cpu, &set)) ++count;
    }
    return count;
}

cpu_set_t buildAffinitySet(const std::vector<CpuInfo>& cpus, int targetFreqKHz) {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (cpus.empty()) return set;

    if (targetFreqKHz > 0) {
        for (const auto& cpu : cpus) {
            if (cpu.maxFreqKHz == targetFreqKHz && cpu.cpu >= 0 && cpu.cpu < CPU_SETSIZE) {
                CPU_SET(cpu.cpu, &set);
            }
        }
    }

    if (countCpuSet(set) == 0) {
        // Fallback: do not fail hard on kernels that hide cpufreq from apps.
        for (const auto& cpu : cpus) {
            if (cpu.cpu >= 0 && cpu.cpu < CPU_SETSIZE) CPU_SET(cpu.cpu, &set);
        }
    }
    return set;
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

    const auto cpus = readCpus();
    bool avoidedPrime = false;
    int highestFreq = 0;
    const int targetFreq = selectTargetFreq(cpus, preferStableBigCluster, &avoidedPrime, &highestFreq);
    cpu_set_t affinity = buildAffinitySet(cpus, targetFreq);
    snapshot.affinityTargetFreqKHz = targetFreq;
    snapshot.affinityHighestFreqKHz = highestFreq;
    snapshot.avoidedUniquePrimeCore = avoidedPrime;
    snapshot.affinityCpuCount = countCpuSet(affinity);
    snapshot.affinityMask = cpuSetToString(affinity);

    if (snapshot.affinityCpuCount > 0) {
        errno = 0;
        snapshot.affinityRc = sched_setaffinity(snapshot.tid, sizeof(cpu_set_t), &affinity);
        snapshot.affinityErrno = errno;
    }

    errno = 0;
    snapshot.schedulerPolicy = sched_getscheduler(0);
    snapshot.schedulerErrno = errno;

    errno = 0;
    snapshot.actualNice = getpriority(PRIO_PROCESS, snapshot.tid);
    snapshot.actualNiceErrno = errno;

    LOGI("USB_THREAD_SCHED name=%s tid=%d niceReq=%d niceRc=%d niceErr=%d(%s) actualNice=%d actualNiceErr=%d "
         "policy=%s(%d) policyErr=%d affinityRc=%d affinityErr=%d(%s) cpus=%s targetKHz=%d highestKHz=%d avoidedPrime=%d timerSlack=%d named=%d",
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
         snapshot.affinityTargetFreqKHz,
         snapshot.affinityHighestFreqKHz,
         snapshot.avoidedUniquePrimeCore ? 1 : 0,
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
       << " affinityRc=" << snapshot.affinityRc
       << " affinityErr=" << snapshot.affinityErrno
       << " cpus=" << snapshot.affinityMask
       << " targetKHz=" << snapshot.affinityTargetFreqKHz
       << " highestKHz=" << snapshot.affinityHighestFreqKHz
       << " avoidedPrime=" << (snapshot.avoidedUniquePrimeCore ? 1 : 0)
       << " timerSlack=" << (snapshot.timerSlackRequested ? 1 : 0)
       << " named=" << (snapshot.threadNamed ? 1 : 0);
    return os.str();
}

} // namespace rawsmusic::usb
