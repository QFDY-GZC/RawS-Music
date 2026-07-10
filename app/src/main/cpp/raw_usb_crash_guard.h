/**
 * raw_usb_crash_guard.h - Native USB breadcrumb 日志
 *
 * 记录关键 USB 生命周期点到文件，用于突发重启后的崩溃定位。
 * 日志路径由 Kotlin 层通过 nativeSetBreadcrumbPath() 配置：
 *   /data/data/com.rawsmusic/files/usb_native_breadcrumb.log
 */

#ifndef RAW_USB_CRASH_GUARD_H
#define RAW_USB_CRASH_GUARD_H

#include <string>

namespace rawsmusic {

class UsbCrashGuard {
public:
    static UsbCrashGuard& instance();

    // 配置日志文件路径（由 Kotlin 启动时调用）
    void setLogPath(const std::string& path);

    // 记录 breadcrumb（BEGIN / END / QUARANTINE 等）
    void breadcrumb(const char* tag, const char* fmt, ...);

    // 便捷方法
    void begin(const char* tag);
    void end(const char* tag);
    void quarantine(const char* tag, const char* reason);

private:
    UsbCrashGuard() = default;
    std::string mLogPath;
};

} // namespace rawsmusic

// C 导出，供 JNI 调用
extern "C" void raw_usb_crash_guard_set_path(const char* path);
extern "C" void raw_usb_crash_guard_begin(const char* tag);
extern "C" void raw_usb_crash_guard_end(const char* tag);
extern "C" void raw_usb_crash_guard_quarantine(const char* tag, const char* reason);

#endif // RAW_USB_CRASH_GUARD_H
