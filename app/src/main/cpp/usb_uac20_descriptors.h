#pragma once

#include <cstdint>
#include <string>
#include <vector>

struct libusb_device_handle;

namespace rawsmusic::usb {

struct Uac20EndpointSnapshot {
    int address = 0;
    int attributes = 0;
    int maxPacketSize = 0;
    int interval = 0;
    int refresh = 0;
    int synchAddress = 0;

    bool isIsochronous = false;
    bool isInput = false;
    int syncType = 0;
    int usageType = 0;
};

struct Uac20AltSnapshot {
    int interfaceNumber = -1;
    int altSetting = 0;
    int interfaceClass = 0;
    int interfaceSubClass = 0;
    int interfaceProtocol = 0;

    int terminalLink = 0;
    int clockSourceId = 0;
    int channels = 0;
    int validBits = 0;
    int subslotBytes = 0;
    int formatType = 0;
    uint32_t formatMask = 0;

    Uac20EndpointSnapshot outEndpoint;
    Uac20EndpointSnapshot feedbackEndpoint;

    bool hasOutEndpoint = false;
    bool hasFeedbackEndpoint = false;
};

struct Uac20DescriptorSnapshot {
    int configValue = -1;
    int audioControlInterface = -1;
    int audioDeviceClassVersion = 0;
    std::vector<int> clockSourceIds;
    std::vector<Uac20AltSnapshot> streamingAlternates;
    std::string lastError;
};

struct Uac20StreamRequest {
    int sampleRate = 0;
    int channels = 0;
    int validBits = 0;
    int subslotBytes = 0;
    bool prefer24In32 = true;
    bool preferExplicitFeedback = true;
};

bool parseUac20DescriptorSnapshot(
        libusb_device_handle* handle,
        Uac20DescriptorSnapshot* outSnapshot);

bool selectBestUac20Alt(
        const Uac20DescriptorSnapshot& snapshot,
        const Uac20StreamRequest& request,
        Uac20AltSnapshot* outAlt);

std::string describeUac20DescriptorSnapshot(const Uac20DescriptorSnapshot& snapshot);

} // namespace rawsmusic::usb
