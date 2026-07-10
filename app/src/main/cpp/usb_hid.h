/**
 * USB HID Remote Control Support for RawSMusic
 * 
 * Based on reverse-engineering of Kugou Music's libkugouplayer.so
 * Supports media key events from USB HID devices (DACs with remote controls)
 * 
 * HID Report Format (typical media keys):
 *   Byte 0: Modifier keys (not used for media keys)
 *   Byte 1: Key code
 *     - 0xE9 (233): Volume Up
 *     - 0xEA (234): Volume Down  
 *     - 0xCD (205): Play/Pause
 *     - 0xB5 (181): Next Track
 *     - 0xB6 (182): Previous Track
 *     - 0xB7 (183): Stop
 */

#ifndef USB_HID_H
#define USB_HID_H

#include <cstdint>
#include <atomic>
#include <thread>
#include <functional>
#include <mutex>

// Forward declarations
struct libusb_device_handle;
struct libusb_context;

namespace rawsmusic {

// USB HID Class constants
constexpr uint8_t USB_CLASS_HID = 0x03;
constexpr uint8_t USB_SUBCLASS_BOOT = 0x01;
constexpr uint8_t USB_PROTOCOL_KEYBOARD = 0x01;
constexpr uint8_t USB_PROTOCOL_MOUSE = 0x02;

// HID Descriptor types
constexpr uint8_t HID_DT_HID = 0x21;
constexpr uint8_t HID_DT_REPORT = 0x22;
constexpr uint8_t HID_DT_PHYSICAL = 0x23;

// Standard media key codes (HID Usage Tables - Consumer Page)
enum class HidMediaKey : uint8_t {
    NONE = 0x00,
    PLAY = 0xCD,        // 205
    PAUSE = 0xB0,       // 176
    RECORD = 0xB2,      // 178
    FAST_FORWARD = 0xB3,// 179
    REWIND = 0xB4,      // 180
    NEXT_TRACK = 0xB5,  // 181
    PREV_TRACK = 0xB6,  // 182
    STOP = 0xB7,        // 183
    EJECT = 0xB8,       // 184
    RANDOM_PLAY = 0xB9, // 185
    PLAY_PAUSE = 0xCD,  // 205 (same as PLAY in many implementations)
    VOLUME_UP = 0xE9,   // 233
    VOLUME_DOWN = 0xEA, // 234
    MUTE = 0xE2,        // 226
};

// HID Key Event structure
struct HidKeyEvent {
    HidMediaKey key;
    bool pressed;       // true = key down, false = key up
    uint64_t timestamp; // microseconds since epoch
};

// HID Endpoint info
struct HidEndpointInfo {
    uint8_t interfaceNumber;
    uint8_t endpointAddress;
    uint16_t maxPacketSize;
    uint8_t interval;
    uint8_t transferType;
    bool found;
};

// Callback type for HID key events
using HidKeyEventCallback = std::function<void(const HidKeyEvent&)>;

/**
 * USB HID Manager
 * Handles HID interface discovery, reading, and event parsing
 */
class UsbHidManager {
public:
    UsbHidManager();
    ~UsbHidManager();
    
    // Non-copyable
    UsbHidManager(const UsbHidManager&) = delete;
    UsbHidManager& operator=(const UsbHidManager&) = delete;
    
    /**
     * Initialize HID manager with existing libusb device handle
     * @param libusbCtx libusb context (from UsbAudioContext)
     * @param devHandle libusb device handle (already opened)
     * @return true if HID interface found and initialized
     */
    bool init(libusb_context* libusbCtx, libusb_device_handle* devHandle);
    
    /**
     * Start listening for HID events
     * @param callback Function to call when key events are received
     * @return true if started successfully
     */
    bool startListening(HidKeyEventCallback callback);
    
    /**
     * Stop listening for HID events
     */
    void stopListening();
    
    /**
     * Check if HID is currently listening
     */
    bool isListening() const { return listening_.load(); }
    
    /**
     * Check if HID interface was found during init
     */
    bool hasHidInterface() const { return endpointInfo_.found; }
    
    /**
     * Get HID endpoint info
     */
    const HidEndpointInfo& getEndpointInfo() const { return endpointInfo_; }

private:
    // HID interface discovery
    bool findHidInterface(libusb_device_handle* devHandle);
    
    // HID read thread function
    void hidReadThreadFunc();
    
    // Parse HID report and extract key events
    HidMediaKey parseHidReport(const uint8_t* data, int length, bool& pressed);
    
    // Claim HID interface
    bool claimHidInterface(libusb_device_handle* devHandle, uint8_t interfaceNumber);
    
    // Release HID interface
    void releaseHidInterface(libusb_device_handle* devHandle, uint8_t interfaceNumber);
    
    // Members
    libusb_context* libusbCtx_ = nullptr;
    libusb_device_handle* devHandle_ = nullptr;
    
    HidEndpointInfo endpointInfo_ = {};
    HidKeyEventCallback callback_;
    
    std::thread readThread_;
    std::atomic<bool> listening_{false};
    std::atomic<bool> shouldStop_{false};
    std::mutex callbackMutex_;
    
    // HID interface state
    bool hidInterfaceClaimed_ = false;
    uint8_t claimedInterfaceNumber_ = 0;
};

} // namespace rawsmusic

#endif // USB_HID_H
