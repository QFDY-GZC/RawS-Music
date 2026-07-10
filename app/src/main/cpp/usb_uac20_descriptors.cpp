#include "usb_uac20_descriptors.h"

#include <android/log.h>

#include <algorithm>
#include <cstdlib>
#include <iomanip>
#include <sstream>

#include "libusb.h"

#define TAG "RawUac20Descriptors"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {
namespace {

constexpr uint8_t USB_CLASS_AUDIO = 0x01;
constexpr uint8_t USB_SUBCLASS_AUDIOCONTROL = 0x01;
constexpr uint8_t USB_SUBCLASS_AUDIOSTREAMING = 0x02;
constexpr uint8_t CS_INTERFACE = 0x24;
constexpr uint8_t CS_ENDPOINT = 0x25;
constexpr uint8_t UAC_HEADER = 0x01;
constexpr uint8_t UAC_AS_GENERAL = 0x01;
constexpr uint8_t UAC_FORMAT_TYPE = 0x02;
constexpr uint8_t UAC_INPUT_TERMINAL = 0x02;
constexpr uint8_t UAC2_CLOCK_SOURCE = 0x0A;
constexpr uint8_t UAC_FORMAT_TYPE_I = 0x01;

int endpointTransferType(int attributes) {
    return attributes & 0x03;
}

int endpointSyncType(int attributes) {
    return (attributes >> 2) & 0x03;
}

int endpointUsageType(int attributes) {
    return (attributes >> 4) & 0x03;
}

bool isEndpointIn(int address) {
    return (address & LIBUSB_ENDPOINT_IN) != 0;
}

Uac20EndpointSnapshot snapshotEndpoint(const libusb_endpoint_descriptor& ep) {
    Uac20EndpointSnapshot out;
    out.address = ep.bEndpointAddress;
    out.attributes = ep.bmAttributes;
    out.maxPacketSize = ep.wMaxPacketSize;
    out.interval = ep.bInterval;
    out.refresh = ep.bRefresh;
    out.synchAddress = ep.bSynchAddress;
    out.isIsochronous = endpointTransferType(ep.bmAttributes) == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
    out.isInput = isEndpointIn(ep.bEndpointAddress);
    out.syncType = endpointSyncType(ep.bmAttributes);
    out.usageType = endpointUsageType(ep.bmAttributes);
    return out;
}

void addUniqueClockSource(Uac20DescriptorSnapshot* snapshot, int id) {
    if (snapshot == nullptr || id <= 0) return;
    if (std::find(snapshot->clockSourceIds.begin(), snapshot->clockSourceIds.end(), id) ==
            snapshot->clockSourceIds.end()) {
        snapshot->clockSourceIds.push_back(id);
    }
}

void parseAudioControlExtra(const libusb_interface_descriptor& desc, Uac20DescriptorSnapshot* snapshot) {
    const auto* p = desc.extra;
    int remaining = desc.extra_length;
    while (p != nullptr && remaining >= 3) {
        const uint8_t len = p[0];
        const uint8_t dtype = p[1];
        const uint8_t subtype = p[2];
        if (len < 3 || len > remaining) break;

        if (dtype == CS_INTERFACE && subtype == UAC_HEADER && len >= 5) {
            snapshot->audioDeviceClassVersion = static_cast<int>(p[3]) | (static_cast<int>(p[4]) << 8);
        } else if (dtype == CS_INTERFACE && subtype == UAC2_CLOCK_SOURCE && len >= 8) {
            addUniqueClockSource(snapshot, p[3]);
        } else if (dtype == CS_INTERFACE && subtype == UAC_INPUT_TERMINAL) {
            // UAC2 Input Terminal exposes bCSourceID. For UAC1 this offset is not
            // a clock source, so only trust it when ADC version says UAC2.
            if (snapshot->audioDeviceClassVersion >= 0x0200 && len >= 17) {
                addUniqueClockSource(snapshot, p[7]);
            }
        }
        p += len;
        remaining -= len;
    }
}

void parseAudioStreamingExtra(const libusb_interface_descriptor& desc, Uac20AltSnapshot* alt) {
    const auto* p = desc.extra;
    int remaining = desc.extra_length;
    while (p != nullptr && remaining >= 3) {
        const uint8_t len = p[0];
        const uint8_t dtype = p[1];
        const uint8_t subtype = p[2];
        if (len < 3 || len > remaining) break;

        if (dtype == CS_INTERFACE && subtype == UAC_AS_GENERAL) {
            // UAC2 AS_GENERAL includes bFormatType, bmFormats and bNrChannels.
            // UAC1 layout differs, so only fill what can be safely inferred.
            if (len >= 11) {
                alt->terminalLink = p[3];
                alt->formatType = p[5];
                alt->formatMask = static_cast<uint32_t>(p[6]) |
                        (static_cast<uint32_t>(p[7]) << 8) |
                        (static_cast<uint32_t>(p[8]) << 16) |
                        (static_cast<uint32_t>(p[9]) << 24);
                alt->channels = p[10];
            } else if (len >= 7) {
                alt->formatType = p[3];
            }
        } else if (dtype == CS_INTERFACE && subtype == UAC_FORMAT_TYPE) {
            if (len == 6 && p[3] == UAC_FORMAT_TYPE_I) {
                // UAC2 Type I: bSubslotSize / bBitResolution at offsets 4/5.
                alt->formatType = p[3];
                alt->subslotBytes = p[4];
                alt->validBits = p[5];
            } else if (len >= 8 && p[3] == UAC_FORMAT_TYPE_I) {
                // UAC1 Type I: bNrChannels / bSubframeSize / bBitResolution.
                alt->formatType = p[3];
                alt->channels = p[4];
                alt->subslotBytes = p[5];
                alt->validBits = p[6];
            }
        }

        p += len;
        remaining -= len;
    }
}

int scoreAlt(const Uac20AltSnapshot& alt, const Uac20StreamRequest& request) {
    if (!alt.hasOutEndpoint) return -100000;

    int score = 0;
    if (request.channels <= 0 || alt.channels == 0 || alt.channels == request.channels) {
        score += 100;
    } else {
        score -= 200;
    }

    if (request.validBits <= 0 || alt.validBits == 0 || alt.validBits == request.validBits) {
        score += 100;
    } else {
        score -= std::abs(alt.validBits - request.validBits) * 8;
    }

    if (request.subslotBytes > 0) {
        if (alt.subslotBytes == request.subslotBytes) score += 120;
        else if (alt.subslotBytes == 0) score += 10;
        else score -= std::abs(alt.subslotBytes - request.subslotBytes) * 30;
    } else if (request.validBits == 24 && request.prefer24In32) {
        if (alt.subslotBytes == 4) score += 80;
        if (alt.subslotBytes == 3) score -= 30;
    }

    if (request.preferExplicitFeedback && alt.hasFeedbackEndpoint) score += 150;
    if (alt.outEndpoint.syncType == 1 /* async */ && alt.hasFeedbackEndpoint) score += 80;
    if (alt.outEndpoint.maxPacketSize > 0) score += 10;

    return score;
}

} // namespace

bool parseUac20DescriptorSnapshot(
        libusb_device_handle* handle,
        Uac20DescriptorSnapshot* outSnapshot) {
    if (outSnapshot == nullptr) return false;
    *outSnapshot = Uac20DescriptorSnapshot{};

    if (handle == nullptr) {
        outSnapshot->lastError = "null-device-handle";
        return false;
    }

    libusb_device* device = libusb_get_device(handle);
    if (device == nullptr) {
        outSnapshot->lastError = "libusb_get_device failed";
        return false;
    }

    libusb_config_descriptor* config = nullptr;
    int rc = libusb_get_active_config_descriptor(device, &config);
    if (rc != 0 || config == nullptr) {
        outSnapshot->lastError = "libusb_get_active_config_descriptor failed";
        LOGE("get_active_config_descriptor failed rc=%d", rc);
        return false;
    }

    outSnapshot->configValue = config->bConfigurationValue;

    for (int i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& desc = iface.altsetting[a];
            if (desc.bInterfaceClass != USB_CLASS_AUDIO) continue;

            if (desc.bInterfaceSubClass == USB_SUBCLASS_AUDIOCONTROL) {
                if (outSnapshot->audioControlInterface < 0) {
                    outSnapshot->audioControlInterface = desc.bInterfaceNumber;
                }
                parseAudioControlExtra(desc, outSnapshot);
                continue;
            }

            if (desc.bInterfaceSubClass != USB_SUBCLASS_AUDIOSTREAMING) continue;

            Uac20AltSnapshot alt;
            alt.interfaceNumber = desc.bInterfaceNumber;
            alt.altSetting = desc.bAlternateSetting;
            alt.interfaceClass = desc.bInterfaceClass;
            alt.interfaceSubClass = desc.bInterfaceSubClass;
            alt.interfaceProtocol = desc.bInterfaceProtocol;
            parseAudioStreamingExtra(desc, &alt);
            if (alt.clockSourceId == 0 && outSnapshot->clockSourceIds.size() == 1) {
                alt.clockSourceId = outSnapshot->clockSourceIds.front();
            }

            for (int e = 0; e < desc.bNumEndpoints; ++e) {
                const Uac20EndpointSnapshot ep = snapshotEndpoint(desc.endpoint[e]);
                if (!ep.isIsochronous) continue;

                if (!ep.isInput && ep.usageType == 0) {
                    alt.outEndpoint = ep;
                    alt.hasOutEndpoint = true;
                } else if (ep.isInput && ep.usageType == 1) {
                    alt.feedbackEndpoint = ep;
                    alt.hasFeedbackEndpoint = true;
                }
            }

            // Some descriptors bind feedback through bSynchAddress on the OUT endpoint.
            if (alt.hasOutEndpoint && !alt.hasFeedbackEndpoint && alt.outEndpoint.synchAddress != 0) {
                for (int e = 0; e < desc.bNumEndpoints; ++e) {
                    const Uac20EndpointSnapshot ep = snapshotEndpoint(desc.endpoint[e]);
                    if (ep.address == alt.outEndpoint.synchAddress) {
                        alt.feedbackEndpoint = ep;
                        alt.hasFeedbackEndpoint = true;
                        break;
                    }
                }
            }

            if (alt.hasOutEndpoint) {
                outSnapshot->streamingAlternates.push_back(alt);
                LOGI("AS alt snapshot iface=%d alt=%d term=%d clock=%d ch=%d bits=%d subslot=%d out=0x%x fb=0x%x outSync=%d fbUsage=%d maxPkt=%d interval=%d",
                     alt.interfaceNumber, alt.altSetting, alt.terminalLink, alt.clockSourceId, alt.channels, alt.validBits, alt.subslotBytes,
                     alt.outEndpoint.address,
                     alt.hasFeedbackEndpoint ? alt.feedbackEndpoint.address : 0,
                     alt.outEndpoint.syncType,
                     alt.hasFeedbackEndpoint ? alt.feedbackEndpoint.usageType : 0,
                     alt.outEndpoint.maxPacketSize,
                     alt.outEndpoint.interval);
            }
        }
    }

    libusb_free_config_descriptor(config);

    if (outSnapshot->streamingAlternates.empty()) {
        outSnapshot->lastError = "no-audio-streaming-out-alt";
        return false;
    }
    return true;
}

bool selectBestUac20Alt(
        const Uac20DescriptorSnapshot& snapshot,
        const Uac20StreamRequest& request,
        Uac20AltSnapshot* outAlt) {
    if (outAlt == nullptr) return false;
    int bestScore = -1000000;
    const Uac20AltSnapshot* best = nullptr;
    for (const Uac20AltSnapshot& alt : snapshot.streamingAlternates) {
        const int score = scoreAlt(alt, request);
        if (score > bestScore) {
            bestScore = score;
            best = &alt;
        }
    }
    if (best == nullptr || bestScore < -1000) return false;
    *outAlt = *best;
    return true;
}

std::string describeUac20DescriptorSnapshot(const Uac20DescriptorSnapshot& snapshot) {
    std::ostringstream os;
    os << "config=" << snapshot.configValue
       << " ac=" << snapshot.audioControlInterface
       << " adc=0x" << std::hex << snapshot.audioDeviceClassVersion << std::dec
       << " clocks=";
    for (size_t i = 0; i < snapshot.clockSourceIds.size(); ++i) {
        if (i != 0) os << "/";
        os << snapshot.clockSourceIds[i];
    }
    os << " alts=" << snapshot.streamingAlternates.size();
    for (const Uac20AltSnapshot& alt : snapshot.streamingAlternates) {
        os << " [if=" << alt.interfaceNumber
           << " alt=" << alt.altSetting
           << " term=" << alt.terminalLink
           << " clock=" << alt.clockSourceId
           << " ch=" << alt.channels
           << " bits=" << alt.validBits
           << " subslot=" << alt.subslotBytes
           << " out=0x" << std::hex << alt.outEndpoint.address
           << " fb=0x" << (alt.hasFeedbackEndpoint ? alt.feedbackEndpoint.address : 0)
           << std::dec
           << " sync=" << alt.outEndpoint.syncType
           << " usage=" << alt.outEndpoint.usageType
           << "]";
    }
    if (!snapshot.lastError.empty()) {
        os << " error=" << snapshot.lastError;
    }
    return os.str();
}

} // namespace rawsmusic::usb
