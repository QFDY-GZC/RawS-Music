#include "usb_uac20_clock.h"

#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <sstream>

#include "libusb.h"

#define TAG "RawUac20Clock"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {
namespace {

constexpr uint8_t REQ_TYPE_SET_CUR_ENTITY =
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE;
constexpr uint8_t REQ_TYPE_GET_CUR_ENTITY =
        LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE;
constexpr uint8_t UAC_SET_CUR = 0x01;
// UAC2 uses the same bRequest value (CUR = 0x01) for both SET_CUR and
// GET_CUR; direction is carried by bmRequestType.  Using the UAC1-style
// GET_CUR value 0x81 makes some Android/libusb devices return PIPE even though
// SET_CUR succeeded.
constexpr uint8_t UAC2_REQ_CUR = 0x01;
constexpr uint8_t UAC2_CS_CONTROL_SAM_FREQ = 0x01;
constexpr unsigned int CONTROL_TIMEOUT_MS = 1000;

void writeLe32(uint8_t* out, int value) {
    out[0] = static_cast<uint8_t>(value & 0xff);
    out[1] = static_cast<uint8_t>((value >> 8) & 0xff);
    out[2] = static_cast<uint8_t>((value >> 16) & 0xff);
    out[3] = static_cast<uint8_t>((value >> 24) & 0xff);
}

int readLe32(const uint8_t* in) {
    return static_cast<int>(in[0]) |
           (static_cast<int>(in[1]) << 8) |
           (static_cast<int>(in[2]) << 16) |
           (static_cast<int>(in[3]) << 24);
}

std::string libusbError(const char* prefix, int rc) {
    std::ostringstream os;
    os << prefix << " rc=" << rc;
    return os.str();
}

bool trySetClockSampleRate(
        libusb_device_handle* handle,
        int controlInterface,
        int clockSourceId,
        int sampleRate,
        Uac20ClockPrepareResult* result) {
    uint8_t data[4] = {};
    writeLe32(data, sampleRate);

    const uint16_t wValue = static_cast<uint16_t>(UAC2_CS_CONTROL_SAM_FREQ << 8);
    const uint16_t wIndex = static_cast<uint16_t>((clockSourceId << 8) | (controlInterface & 0xff));

    const int rc = libusb_control_transfer(
            handle,
            REQ_TYPE_SET_CUR_ENTITY,
            UAC_SET_CUR,
            wValue,
            wIndex,
            data,
            sizeof(data),
            CONTROL_TIMEOUT_MS);

    if (result != nullptr) {
        result->selectedClockSource = clockSourceId;
        result->setSampleRateResult = rc;
    }

    if (rc < 0) {
        LOGW("SET_CUR sample rate failed clock=%d iface=%d rate=%d rc=%d",
             clockSourceId, controlInterface, sampleRate, rc);
        return false;
    }

    if (result != nullptr) result->sampleRateSet = true;
    LOGI("SET_CUR sample rate ok clock=%d iface=%d rate=%d rc=%d",
         clockSourceId, controlInterface, sampleRate, rc);
    return true;
}

bool tryGetClockSampleRate(
        libusb_device_handle* handle,
        int controlInterface,
        int clockSourceId,
        int expectedSampleRate,
        Uac20ClockPrepareResult* result) {
    uint8_t data[4] = {};
    const uint16_t wValue = static_cast<uint16_t>(UAC2_CS_CONTROL_SAM_FREQ << 8);
    const uint16_t wIndex = static_cast<uint16_t>((clockSourceId << 8) | (controlInterface & 0xff));

    const int rc = libusb_control_transfer(
            handle,
            REQ_TYPE_GET_CUR_ENTITY,
            UAC2_REQ_CUR,
            wValue,
            wIndex,
            data,
            sizeof(data),
            CONTROL_TIMEOUT_MS);

    if (result != nullptr) result->getSampleRateResult = rc;

    if (rc < 0) {
        LOGW("UAC2 GET_CUR sample rate failed clock=%d iface=%d request=CUR(0x01) wValue=0x%04x wIndex=0x%04x rc=%d",
             clockSourceId, controlInterface, wValue, wIndex, rc);
        return false;
    }

    const int gotRate = readLe32(data);
    if (result != nullptr) {
        result->verifiedSampleRate = gotRate;
        result->sampleRateVerified = gotRate == expectedSampleRate;
    }

    LOGI("UAC2 GET_CUR sample rate clock=%d iface=%d got=%d expected=%d rc=%d data=%02x %02x %02x %02x",
         clockSourceId, controlInterface, gotRate, expectedSampleRate, rc,
         data[0], data[1], data[2], data[3]);
    return gotRate == expectedSampleRate;
}

std::vector<int> clockCandidatesForAlt(
        const Uac20DescriptorSnapshot& snapshot,
        const Uac20AltSnapshot& selectedAlt) {
    std::vector<int> candidates;

    if (selectedAlt.clockSourceId > 0) candidates.push_back(selectedAlt.clockSourceId);
    for (const int id : snapshot.clockSourceIds) {
        if (id <= 0) continue;
        bool seen = false;
        for (const int existing : candidates) {
            if (existing == id) {
                seen = true;
                break;
            }
        }
        if (!seen) candidates.push_back(id);
    }

    // Last-resort guesses. Some descriptors expose terminal link but not a parsed
    // clock source in the active-config snapshot. Trying these is best-effort and
    // non-fatal; failure simply means the legacy path should keep owning clock.
    if (selectedAlt.terminalLink > 0) {
        bool seen = false;
        for (const int existing : candidates) {
            if (existing == selectedAlt.terminalLink) {
                seen = true;
                break;
            }
        }
        if (!seen) candidates.push_back(selectedAlt.terminalLink);
    }
    return candidates;
}

} // namespace

bool claimUac20Interface(
        libusb_device_handle* handle,
        int interfaceNumber,
        const char* label,
        std::string* outError) {
    if (handle == nullptr || interfaceNumber < 0) {
        if (outError != nullptr) *outError = "claim interface called with invalid handle/interface";
        return false;
    }

    const int rc = libusb_claim_interface(handle, interfaceNumber);
    if (rc != 0) {
        if (outError != nullptr) {
            *outError = libusbError("libusb_claim_interface failed", rc);
        }
        LOGE("libusb_claim_interface %s iface=%d failed rc=%d", label ? label : "?", interfaceNumber, rc);
        return false;
    }

    LOGI("libusb_claim_interface %s iface=%d ok", label ? label : "?", interfaceNumber);
    return true;
}

void releaseUac20Interface(
        libusb_device_handle* handle,
        int interfaceNumber,
        const char* label) {
    if (handle == nullptr || interfaceNumber < 0) return;
    const int rc = libusb_release_interface(handle, interfaceNumber);
    LOGI("libusb_release_interface %s iface=%d rc=%d", label ? label : "?", interfaceNumber, rc);
}

bool resetUac20StreamingAlt0(
        libusb_device_handle* handle,
        int streamingInterface,
        Uac20ClockPrepareResult* result) {
    if (handle == nullptr || streamingInterface < 0) {
        if (result != nullptr) result->lastError = "reset alt0 invalid handle/interface";
        return false;
    }

    const int rc = libusb_set_interface_alt_setting(handle, streamingInterface, 0);
    if (rc != 0) {
        if (result != nullptr) result->lastError = libusbError("set alt0 failed", rc);
        LOGE("set AS alt0 failed iface=%d rc=%d", streamingInterface, rc);
        return false;
    }

    if (result != nullptr) result->resetAlt0 = true;
    LOGI("set AS alt0 ok iface=%d", streamingInterface);
    return true;
}

bool setUac20PlaybackAlt(
        libusb_device_handle* handle,
        int streamingInterface,
        int altSetting,
        Uac20ClockPrepareResult* result) {
    if (handle == nullptr || streamingInterface < 0 || altSetting <= 0) {
        if (result != nullptr) result->lastError = "set playback alt invalid handle/interface/alt";
        return false;
    }

    const int rc = libusb_set_interface_alt_setting(handle, streamingInterface, altSetting);
    if (rc != 0) {
        if (result != nullptr) result->lastError = libusbError("set playback alt failed", rc);
        LOGE("set playback alt failed iface=%d alt=%d rc=%d", streamingInterface, altSetting, rc);
        return false;
    }

    if (result != nullptr) result->playbackAltSet = true;
    LOGI("set playback alt ok iface=%d alt=%d", streamingInterface, altSetting);
    return true;
}

bool prepareUac20ClockBestEffort(
        libusb_device_handle* handle,
        const Uac20DescriptorSnapshot& snapshot,
        const Uac20AltSnapshot& selectedAlt,
        int sampleRate,
        Uac20ClockPrepareResult* result) {
    if (result != nullptr) {
        result->selectedClockSource = 0;
        result->setSampleRateResult = 0;
        result->getSampleRateResult = 0;
        result->verifiedSampleRate = 0;
        result->sampleRateSet = false;
        result->sampleRateVerified = false;
    }

    if (handle == nullptr || sampleRate <= 0 || snapshot.audioControlInterface < 0) {
        if (result != nullptr) result->lastError = "clock prepare missing handle/rate/control interface";
        return false;
    }

    const std::vector<int> candidates = clockCandidatesForAlt(snapshot, selectedAlt);
    if (candidates.empty()) {
        if (result != nullptr) result->lastError = "no parsed clock source candidates";
        LOGW("clock prepare skipped: no parsed clock source candidates");
        return false;
    }

    for (const int clockId : candidates) {
        if (clockId <= 0) continue;
        if (!trySetClockSampleRate(handle, snapshot.audioControlInterface, clockId, sampleRate, result)) {
            continue;
        }
        // GET_CUR verification is useful but not required for bring-up: some DACs
        // reject GET_CUR while still accepting SET_CUR. Keep the set result.
        tryGetClockSampleRate(handle, snapshot.audioControlInterface, clockId, sampleRate, result);
        return true;
    }

    if (result != nullptr && result->lastError.empty()) {
        result->lastError = "all clock SET_CUR candidates failed";
    }
    return false;
}

} // namespace rawsmusic::usb
