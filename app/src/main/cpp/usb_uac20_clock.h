#pragma once

#include <string>
#include <vector>

#include "usb_uac20_descriptors.h"

struct libusb_device_handle;

namespace rawsmusic::usb {

struct Uac20ClockPrepareResult {
    bool claimedControl = false;
    bool claimedStreaming = false;
    bool resetAlt0 = false;
    bool playbackAltSet = false;

    int selectedClockSource = 0;
    int setSampleRateResult = 0;
    int getSampleRateResult = 0;
    int verifiedSampleRate = 0;
    bool sampleRateSet = false;
    bool sampleRateVerified = false;

    std::string lastError;
};

bool claimUac20Interface(
        libusb_device_handle* handle,
        int interfaceNumber,
        const char* label,
        std::string* outError);

void releaseUac20Interface(
        libusb_device_handle* handle,
        int interfaceNumber,
        const char* label);

bool resetUac20StreamingAlt0(
        libusb_device_handle* handle,
        int streamingInterface,
        Uac20ClockPrepareResult* result);

bool setUac20PlaybackAlt(
        libusb_device_handle* handle,
        int streamingInterface,
        int altSetting,
        Uac20ClockPrepareResult* result);

bool prepareUac20ClockBestEffort(
        libusb_device_handle* handle,
        const Uac20DescriptorSnapshot& snapshot,
        const Uac20AltSnapshot& selectedAlt,
        int sampleRate,
        Uac20ClockPrepareResult* result);

} // namespace rawsmusic::usb
