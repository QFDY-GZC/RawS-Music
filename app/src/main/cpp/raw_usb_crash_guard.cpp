/**
 * raw_usb_crash_guard.cpp - Native USB breadcrumb 日志实现
 */

#include "raw_usb_crash_guard.h"
#include <cstdarg>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <android/log.h>

#define LOG_TAG "RawUsbCrashGuard"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace rawsmusic {

UsbCrashGuard& UsbCrashGuard::instance() {
    static UsbCrashGuard inst;
    return inst;
}

void UsbCrashGuard::setLogPath(const std::string& path) {
    mLogPath = path;
    LOGI("Breadcrumb log path: %s", path.c_str());
}

void UsbCrashGuard::breadcrumb(const char* tag, const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    // 时间戳
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long ms = ts.tv_nsec / 1000000;

    char line[768];
    snprintf(line, sizeof(line), "[%ld.%03ld] %s: %s\n",
             (long)ts.tv_sec, ms, tag ? tag : "?", buf);

    // 同时输出到 logcat
    LOGI("BC: %s %s", tag ? tag : "?", buf);

    // 写入文件
    static std::mutex fileMtx;
    std::lock_guard<std::mutex> lk(fileMtx);
    if (!mLogPath.empty()) {
        FILE* f = fopen(mLogPath.c_str(), "a");
        if (f) {
            fputs(line, f);
            fclose(f);
        }
    }
}

void UsbCrashGuard::begin(const char* tag) {
    breadcrumb(tag, "BEGIN");
}

void UsbCrashGuard::end(const char* tag) {
    breadcrumb(tag, "END");
}

void UsbCrashGuard::quarantine(const char* tag, const char* reason) {
    breadcrumb(tag, "QUARANTINE: %s", reason ? reason : "unknown");
}

} // namespace rawsmusic

// C 导出
extern "C" void raw_usb_crash_guard_set_path(const char* path) {
    rawsmusic::UsbCrashGuard::instance().setLogPath(path ? path : "");
}

extern "C" void raw_usb_crash_guard_begin(const char* tag) {
    rawsmusic::UsbCrashGuard::instance().begin(tag);
}

extern "C" void raw_usb_crash_guard_end(const char* tag) {
    rawsmusic::UsbCrashGuard::instance().end(tag);
}

extern "C" void raw_usb_crash_guard_quarantine(const char* tag, const char* reason) {
    rawsmusic::UsbCrashGuard::instance().quarantine(tag, reason);
}
