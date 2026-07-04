/**
 * USB HID Remote Control Implementation for RawSMusic
 * 
 * Based on reverse-engineering of Kugou Music's libkugouplayer.so
 * Implements HID interface discovery, reading, and media key event parsing
 */

#include "usb_hid.h"
#include "libusb.h"
#include <android/log.h>
#include <cstring>
#include <chrono>
#include <algorithm>

#define TAG "UsbHid"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic {

UsbHidManager::UsbHidManager() = default;

UsbHidManager::~UsbHidManager() {
    stopListening();
}

bool UsbHidManager::init(libusb_context* libusbCtx, libusb_device_handle* devHandle) {
    if (!libusbCtx || !devHandle) {
        LOGE("init failed: null context or handle");
        return false;
    }
    
    libusbCtx_ = libusbCtx;
    devHandle_ = devHandle;
    
    // Try to find HID interface
    if (!findHidInterface(devHandle)) {
        LOGI("No HID interface found on this device");
        return false;
    }
    
    LOGI("HID interface found: iface=%d, endpoint=0x%02x, maxPacket=%d, interval=%d",
         endpointInfo_.interfaceNumber, endpointInfo_.endpointAddress,
         endpointInfo_.maxPacketSize, endpointInfo_.interval);
    
    return true;
}

bool UsbHidManager::findHidInterface(libusb_device_handle* devHandle) {
    struct libusb_device* dev = libusb_get_device(devHandle);
    if (!dev) {
        LOGE("Failed to get device from handle");
        return false;
    }
    
    struct libusb_config_descriptor* config;
    int rc = libusb_get_active_config_descriptor(dev, &config);
    if (rc < 0) {
        LOGE("Failed to get config descriptor: %s", libusb_error_name(rc));
        return false;
    }
    
    bool found = false;
    
    // Iterate through all interfaces
    for (int i = 0; i < config->bNumInterfaces && !found; i++) {
        const struct libusb_interface& iface = config->interface[i];
        
        for (int j = 0; j < iface.num_altsetting && !found; j++) {
            const struct libusb_interface_descriptor& altsetting = iface.altsetting[j];
            
            // Check if this is a HID interface
            if (altsetting.bInterfaceClass == USB_CLASS_HID) {
                LOGI("Found HID interface: num=%d, alt=%d, class=0x%02x, subclass=0x%02x, protocol=0x%02x",
                     altsetting.bInterfaceNumber, altsetting.bAlternateSetting,
                     altsetting.bInterfaceClass, altsetting.bInterfaceSubClass,
                     altsetting.bInterfaceProtocol);
                
                // Look for an IN interrupt endpoint (for receiving key events)
                for (int k = 0; k < altsetting.bNumEndpoints; k++) {
                    const struct libusb_endpoint_descriptor& ep = altsetting.endpoint[k];
                    
                    // Check for interrupt IN endpoint
                    if ((ep.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN &&
                        (ep.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) == LIBUSB_TRANSFER_TYPE_INTERRUPT) {
                        
                        endpointInfo_.interfaceNumber = altsetting.bInterfaceNumber;
                        endpointInfo_.endpointAddress = ep.bEndpointAddress;
                        endpointInfo_.maxPacketSize = ep.wMaxPacketSize;
                        endpointInfo_.interval = ep.bInterval;
                        endpointInfo_.transferType = LIBUSB_TRANSFER_TYPE_INTERRUPT;
                        endpointInfo_.found = true;
                        
                        LOGI("HID endpoint found: addr=0x%02x, maxPacket=%d, interval=%d",
                             ep.bEndpointAddress, ep.wMaxPacketSize, ep.bInterval);
                        
                        found = true;
                        break;
                    }
                }
                
                // If no interrupt endpoint, try bulk transfer (like Kugou does)
                if (!found) {
                    for (int k = 0; k < altsetting.bNumEndpoints; k++) {
                        const struct libusb_endpoint_descriptor& ep = altsetting.endpoint[k];
                        
                        // Check for bulk IN endpoint
                        if ((ep.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN &&
                            (ep.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) == LIBUSB_TRANSFER_TYPE_BULK) {
                            
                            endpointInfo_.interfaceNumber = altsetting.bInterfaceNumber;
                            endpointInfo_.endpointAddress = ep.bEndpointAddress;
                            endpointInfo_.maxPacketSize = ep.wMaxPacketSize;
                            endpointInfo_.interval = ep.bInterval;
                            endpointInfo_.transferType = LIBUSB_TRANSFER_TYPE_BULK;
                            endpointInfo_.found = true;
                            
                            LOGI("HID endpoint found (bulk): addr=0x%02x, maxPacket=%d, interval=%d",
                                 ep.bEndpointAddress, ep.wMaxPacketSize, ep.bInterval);
                            
                            found = true;
                            break;
                        }
                    }
                }
            }
        }
    }
    
    libusb_free_config_descriptor(config);
    return found;
}

bool UsbHidManager::claimHidInterface(libusb_device_handle* devHandle, uint8_t interfaceNumber) {
    // Try to detach kernel driver if active
    if (libusb_kernel_driver_active(devHandle, interfaceNumber) == 1) {
        int rc = libusb_detach_kernel_driver(devHandle, interfaceNumber);
        if (rc < 0) {
            LOGW("Failed to detach kernel driver: %s", libusb_error_name(rc));
        }
    }
    
    int rc = libusb_claim_interface(devHandle, interfaceNumber);
    if (rc < 0) {
        LOGE("Failed to claim HID interface %d: %s", interfaceNumber, libusb_error_name(rc));
        return false;
    }
    
    hidInterfaceClaimed_ = true;
    claimedInterfaceNumber_ = interfaceNumber;
    LOGI("HID interface %d claimed successfully", interfaceNumber);
    return true;
}

void UsbHidManager::releaseHidInterface(libusb_device_handle* devHandle, uint8_t interfaceNumber) {
    if (hidInterfaceClaimed_ && claimedInterfaceNumber_ == interfaceNumber) {
        libusb_release_interface(devHandle, interfaceNumber);
        hidInterfaceClaimed_ = false;
        LOGI("HID interface %d released", interfaceNumber);
    }
}

bool UsbHidManager::startListening(HidKeyEventCallback callback) {
    if (!devHandle_ || !endpointInfo_.found) {
        LOGE("Cannot start listening: no device handle or HID endpoint");
        return false;
    }
    
    if (listening_.load()) {
        LOGW("Already listening for HID events");
        return true;
    }
    
    // Claim HID interface
    if (!claimHidInterface(devHandle_, endpointInfo_.interfaceNumber)) {
        return false;
    }
    
    // Store callback
    {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        callback_ = callback;
    }
    
    // Start read thread
    shouldStop_.store(false);
    listening_.store(true);
    
    readThread_ = std::thread(&UsbHidManager::hidReadThreadFunc, this);
    
    LOGI("HID listening started");
    return true;
}

void UsbHidManager::stopListening() {
    if (!listening_.load()) {
        return;
    }
    
    LOGI("Stopping HID listening...");
    shouldStop_.store(true);
    
    if (readThread_.joinable()) {
        readThread_.join();
    }
    
    listening_.store(false);
    
    // Release interface
    if (devHandle_) {
        releaseHidInterface(devHandle_, endpointInfo_.interfaceNumber);
    }
    
    LOGI("HID listening stopped");
}

void UsbHidManager::hidReadThreadFunc() {
    LOGI("HID read thread started");
    
    // Set thread priority for real-time response
    struct sched_param param;
    param.sched_priority = 10; // Lower than audio thread but still high
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
    
    uint8_t buffer[64]; // HID reports are typically small
    int transferred = 0;
    
    while (!shouldStop_.load()) {
        const int transferLen = std::min<int>(
            sizeof(buffer),
            endpointInfo_.maxPacketSize > 0 ? endpointInfo_.maxPacketSize : sizeof(buffer)
        );
        int rc;
        if (endpointInfo_.transferType == LIBUSB_TRANSFER_TYPE_INTERRUPT) {
            rc = libusb_interrupt_transfer(
                devHandle_,
                endpointInfo_.endpointAddress,
                buffer,
                transferLen,
                &transferred,
                100 // 100ms timeout for responsive stop
            );
        } else {
            rc = libusb_bulk_transfer(
                devHandle_,
                endpointInfo_.endpointAddress,
                buffer,
                transferLen,
                &transferred,
                100 // 100ms timeout for responsive stop
            );
        }
        
        if (rc == LIBUSB_ERROR_TIMEOUT) {
            // Timeout is normal, just continue
            continue;
        }
        
        if (rc == LIBUSB_ERROR_NO_DEVICE) {
            LOGW("USB device disconnected");
            break;
        }
        
        if (rc == LIBUSB_ERROR_IO) {
            LOGW("USB I/O error");
            break;
        }
        
        if (rc < 0) {
            LOGE("HID transfer failed: %s", libusb_error_name(rc));
            break;
        }
        
        if (transferred > 0) {
            LOGD("HID report received: %d bytes", transferred);
            
            // Parse HID report
            bool pressed = false;
            HidMediaKey key = parseHidReport(buffer, transferred, pressed);
            
            if (key != HidMediaKey::NONE) {
                HidKeyEvent event;
                event.key = key;
                event.pressed = pressed;
                event.timestamp = std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::system_clock::now().time_since_epoch()
                ).count();
                
                LOGI("HID key event: key=0x%02x, pressed=%d", static_cast<uint8_t>(key), pressed);
                
                // Call callback
                std::lock_guard<std::mutex> lock(callbackMutex_);
                if (callback_) {
                    callback_(event);
                }
            }
        }
    }
    
    LOGI("HID read thread exiting");
}

HidMediaKey UsbHidManager::parseHidReport(const uint8_t* data, int length, bool& pressed) {
    if (!data || length <= 0) {
        pressed = false;
        return HidMediaKey::NONE;
    }

    bool allZero = true;
    for (int i = 0; i < length; ++i) {
        const uint8_t keyCode = data[i];
        if (keyCode != 0) allZero = false;

        switch (keyCode) {
            case 0xCD: // Play/Pause
            case 0xB0: // Pause
            case 0xB5: // Next Track
            case 0xB6: // Previous Track
            case 0xB7: // Stop
            case 0xE9: // Volume Up
            case 0xEA: // Volume Down
            case 0xE2: // Mute
                pressed = true;
                return static_cast<HidMediaKey>(keyCode);
            default:
                break;
        }
    }

    pressed = false;
    if (!allZero) {
        LOGD("Unknown HID report: len=%d b0=0x%02x b1=0x%02x",
             length, data[0], length > 1 ? data[1] : 0);
    }
    return HidMediaKey::NONE;
}

} // namespace rawsmusic
