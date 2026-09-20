#include <jni.h>
#include <android/log.h>
#include <libusb.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstdint>
#include <cstdlib>

#define LOG_TAG "UsbAudioNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct UsbAudioNativeHandle {
    libusb_context* ctx = nullptr;
    libusb_device_handle* devh = nullptr;
    int originalFd = -1;
    int dupFd = -1;
    int ifaceNumber = -1;
    int altSetting = 0;
    uint8_t outEndpoint = 0;
    uint8_t feedbackEndpoint = 0;
    int sampleRate = 0;
    int channels = 0;
    int bitsPerSample = 0;
    int subslotSize = 0;
    int frameSize = 0;
    std::atomic<bool> streaming{false};
    std::mutex writeMutex;
};

static UsbAudioNativeHandle* fromHandle(jlong handle) {
    return reinterpret_cast<UsbAudioNativeHandle*>(handle);
}

static const char* libusbErrName(int r) {
    return libusb_error_name(r);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_usb_NativeUsbAudio_nativeOpenUsbDevice(
        JNIEnv* env,
        jobject thiz,
        jint fd,
        jint ifaceNumber,
        jint altSetting,
        jint outEndpoint,
        jint feedbackEndpoint,
        jint sampleRate,
        jint channels,
        jint bitsPerSample,
        jint subslotSize
) {
    (void) env;
    (void) thiz;

    LOGI("nativeOpenUsbDevice: fd=%d iface=%d alt=%d outEp=0x%02X fbEp=0x%02X sr=%d ch=%d bits=%d subslot=%d",
         fd, ifaceNumber, altSetting, outEndpoint, feedbackEndpoint,
         sampleRate, channels, bitsPerSample, subslotSize);

    if (fd < 0) {
        LOGE("invalid fd=%d", fd);
        return 0;
    }
    if (ifaceNumber < 0) {
        LOGE("invalid ifaceNumber=%d", ifaceNumber);
        return 0;
    }
    if (altSetting < 0) {
        LOGE("invalid altSetting=%d", altSetting);
        return 0;
    }
    if (outEndpoint <= 0) {
        LOGE("invalid outEndpoint=0x%02X", outEndpoint);
        return 0;
    }
    if (sampleRate <= 0 || channels <= 0 || subslotSize <= 0) {
        LOGE("invalid format sr=%d ch=%d subslot=%d", sampleRate, channels, subslotSize);
        return 0;
    }

    auto* h = new UsbAudioNativeHandle();
    h->originalFd = fd;
    h->ifaceNumber = ifaceNumber;
    h->altSetting = altSetting;
    h->outEndpoint = static_cast<uint8_t>(outEndpoint & 0xFF);
    h->feedbackEndpoint = static_cast<uint8_t>(feedbackEndpoint & 0xFF);
    h->sampleRate = sampleRate;
    h->channels = channels;
    h->bitsPerSample = bitsPerSample;
    h->subslotSize = subslotSize;
    h->frameSize = channels * subslotSize;

    // dup Android 给的 fd，native 使用自己的 fd
    h->dupFd = dup(fd);
    if (h->dupFd < 0) {
        LOGE("dup(fd=%d) failed: errno=%d %s", fd, errno, strerror(errno));
        delete h;
        return 0;
    }

    int r = libusb_init(&h->ctx);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_init failed: %s", libusbErrName(r));
        close(h->dupFd);
        delete h;
        return 0;
    }
    libusb_set_option(h->ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);

    // libusb_wrap_sys_device 直接包装 Android UsbDeviceConnection 的 fd
    r = libusb_wrap_sys_device(
            h->ctx,
            static_cast<intptr_t>(h->dupFd),
            &h->devh
    );
    if (r != LIBUSB_SUCCESS || h->devh == nullptr) {
        LOGE("libusb_wrap_sys_device failed: r=%d %s", r, libusbErrName(r));
        libusb_exit(h->ctx);
        close(h->dupFd);
        delete h;
        return 0;
    }
    LOGI("libusb_wrap_sys_device ok: dupFd=%d devh=%p", h->dupFd, h->devh);

    // Android 上一般没有标准 kernel driver detach 需求，但设置 auto detach 不影响
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000102)
    libusb_set_auto_detach_kernel_driver(h->devh, 1);
#endif

    // 由 native 统一 claim interface
    r = libusb_claim_interface(h->devh, h->ifaceNumber);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_claim_interface(iface=%d) failed: %s",
             h->ifaceNumber, libusbErrName(r));
        libusb_close(h->devh);
        libusb_exit(h->ctx);
        close(h->dupFd);
        delete h;
        return 0;
    }
    LOGI("libusb_claim_interface(iface=%d) ok", h->ifaceNumber);

    // 由 native 统一 set alt
    r = libusb_set_interface_alt_setting(h->devh, h->ifaceNumber, h->altSetting);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_set_interface_alt_setting(iface=%d, alt=%d) failed: %s",
             h->ifaceNumber, h->altSetting, libusbErrName(r));
        libusb_release_interface(h->devh, h->ifaceNumber);
        libusb_close(h->devh);
        libusb_exit(h->ctx);
        close(h->dupFd);
        delete h;
        return 0;
    }
    LOGI("libusb_set_interface_alt_setting(iface=%d, alt=%d) ok", h->ifaceNumber, h->altSetting);

    LOGI("USB native opened:");
    LOGI("  iface=%d alt=%d outEp=0x%02X fbEp=0x%02X",
         h->ifaceNumber, h->altSetting, h->outEndpoint, h->feedbackEndpoint);
    LOGI("  format=%dch/%dbit/subslot%d frameSize=%d sr=%d",
         h->channels, h->bitsPerSample, h->subslotSize, h->frameSize, h->sampleRate);

    return reinterpret_cast<jlong>(h);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_NativeUsbAudio_nativeCloseUsbDevice(
        JNIEnv* env,
        jobject thiz,
        jlong handle
) {
    (void) env;
    (void) thiz;
    auto* h = fromHandle(handle);
    if (!h) return;
    LOGI("nativeCloseUsbDevice: handle=%p", h);
    h->streaming.store(false);

    if (h->devh) {
        // 关闭前切回 alt0，释放带宽
        if (h->ifaceNumber >= 0) {
            int r = libusb_set_interface_alt_setting(h->devh, h->ifaceNumber, 0);
            if (r == LIBUSB_SUCCESS) {
                LOGI("set alt0 ok: iface=%d", h->ifaceNumber);
            } else {
                LOGW("set alt0 failed: iface=%d err=%s", h->ifaceNumber, libusbErrName(r));
            }
            r = libusb_release_interface(h->devh, h->ifaceNumber);
            if (r == LIBUSB_SUCCESS) {
                LOGI("release_interface ok: iface=%d", h->ifaceNumber);
            } else {
                LOGW("release_interface failed: iface=%d err=%s", h->ifaceNumber, libusbErrName(r));
            }
        }
        libusb_close(h->devh);
        h->devh = nullptr;
    }
    if (h->ctx) {
        libusb_exit(h->ctx);
        h->ctx = nullptr;
    }
    if (h->dupFd >= 0) {
        close(h->dupFd);
        h->dupFd = -1;
    }
    delete h;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_NativeUsbAudio_nativeStartStreaming(
        JNIEnv* env,
        jobject thiz,
        jlong handle
) {
    (void) env;
    (void) thiz;
    auto* h = fromHandle(handle);
    if (!h || !h->devh) {
        LOGE("nativeStartStreaming failed: invalid handle");
        return JNI_FALSE;
    }
    h->streaming.store(true);
    LOGI("nativeStartStreaming:");
    LOGI("  iface=%d alt=%d outEp=0x%02X format=%dch/%dbit/subslot%d sr=%d",
         h->ifaceNumber, h->altSetting, h->outEndpoint,
         h->channels, h->bitsPerSample, h->subslotSize, h->sampleRate);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_NativeUsbAudio_nativeStopStreaming(
        JNIEnv* env,
        jobject thiz,
        jlong handle
) {
    (void) env;
    (void) thiz;
    auto* h = fromHandle(handle);
    if (!h) return;
    h->streaming.store(false);
    LOGI("nativeStopStreaming");
}

/**
 * 同步写占位 — 不适合 USB Audio ISO 最终播放。
 * 如果已有 libusb async ISO transfers，保留原来的 ISO 调度器，
 * 只需把 open/claim/set_alt 部分替换成上面的 nativeOpenUsbDevice。
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_NativeUsbAudio_nativeWrite(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jbyteArray data,
        jint offset,
        jint length
) {
    (void) thiz;
    auto* h = fromHandle(handle);
    if (!h || !h->devh) {
        LOGE("nativeWrite failed: invalid handle");
        return -1;
    }
    if (!h->streaming.load()) {
        LOGW("nativeWrite ignored: not streaming");
        return 0;
    }
    if (!data) {
        LOGE("nativeWrite failed: data=null");
        return -1;
    }
    const jsize arrayLen = env->GetArrayLength(data);
    if (offset < 0 || length < 0 || offset + length > arrayLen) {
        LOGE("nativeWrite invalid range: offset=%d length=%d arrayLen=%d", offset, length, arrayLen);
        return -1;
    }
    if (h->frameSize > 0 && (length % h->frameSize) != 0) {
        LOGW("nativeWrite length not frame aligned: len=%d frameSize=%d remainder=%d",
             length, h->frameSize, length % h->frameSize);
        length -= length % h->frameSize;
        if (length <= 0) {
            return 0;
        }
    }
    std::vector<uint8_t> buffer(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte*>(buffer.data()));

    LOGI("nativeWrite received: len=%d first=%02X %02X %02X %02X %02X %02X",
         length,
         length > 0 ? buffer[0] : 0,
         length > 1 ? buffer[1] : 0,
         length > 2 ? buffer[2] : 0,
         length > 3 ? buffer[3] : 0,
         length > 4 ? buffer[4] : 0,
         length > 5 ? buffer[5] : 0);
    return length;
}
