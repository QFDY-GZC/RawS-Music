package com.rawsmusic.module.player.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rawsmusic.core.common.utils.AppLogger

/** Android-side USB discovery. Permission and lifecycle stay outside native by design. */
internal class UsbDeviceDiscovery(private val usbManager: UsbManager) {
    companion object {
        private const val TAG = "UsbExclusiveManager"
        private const val USB_CLASS_AUDIO = UsbConstants.USB_CLASS_AUDIO
        private const val USB_SUBCLASS_AUDIOSTREAMING = 0x02
    }

    fun findUsbAudioDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values.toList()
        AppLogger.d(TAG, "Scanning ${devices.size} USB devices...")
        for (device in devices) {
            AppLogger.d(
                TAG,
                "Device: ${device.deviceName}, VID=${String.format("%04X", device.vendorId)}, " +
                    "PID=${String.format("%04X", device.productId)}, interfaces=${device.interfaceCount}"
            )
            if ((0 until device.interfaceCount).any { index ->
                    val intf = device.getInterface(index)
                    intf.interfaceClass == USB_CLASS_AUDIO ||
                        intf.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING
                }
            ) {
                dumpInterfaces(device)
            }
        }

        // Prefer the strongest Android-visible proof: an AudioStreaming alternate setting with
        // an isochronous OUT endpoint. This remains the normal UAC1/UAC2 path.
        devices.firstOrNull(::isUsbAudioOutputDevice)?.let { device ->
            AppLogger.i(TAG, "Found USB audio output device: ${device.productName}")
            return device
        }

        // The Java interface view does not need to expose an ISO OUT endpoint before
        // it asks for permission; native descriptor parsing is authoritative after openDevice().
        // Some UAC1/composite devices expose only alt0 (or otherwise incomplete alternate-setting
        // data) through UsbDevice on specific OEM builds. Keep a narrow compatibility candidate
        // lane for devices that are still visibly USB Audio class / AudioStreaming. Native init
        // must later prove an actual playback stream before exclusive mode can start.
        devices.firstOrNull(::isUsbAudioCandidateDevice)?.let { device ->
            AppLogger.w(
                TAG,
                "UAC_COMPAT_CANDIDATE selected without Java-visible ISO OUT: " +
                    "device=${device.productName} class=${device.deviceClass}",
            )
            return device
        }

        AppLogger.w(TAG, "No USB audio candidate found")
        return null
    }

    fun isUsbAudioOutputDevice(device: UsbDevice): Boolean {
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            if (intf.interfaceClass != USB_CLASS_AUDIO ||
                intf.interfaceSubclass != USB_SUBCLASS_AUDIOSTREAMING
            ) continue
            for (endpointIndex in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(endpointIndex)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
                ) return true
            }
        }
        return false
    }


    /**
     * Permission-stage USB-audio candidate. Native raw-descriptor parsing remains authoritative.
     *
     * Do not broaden this to every per-interface/vendor device: the fallback exists specifically
     * for UAC1/composite DACs whose Android UsbDevice view may omit the non-zero AS alternate
     * setting, not for arbitrary USB peripherals.
     */
    fun isUsbAudioCandidateDevice(device: UsbDevice): Boolean {
        if (isUsbAudioOutputDevice(device)) return true
        if (device.deviceClass == USB_CLASS_AUDIO) return true
        return (0 until device.interfaceCount).any { index ->
            val intf = device.getInterface(index)
            intf.interfaceClass == USB_CLASS_AUDIO &&
                intf.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING
        }
    }

    fun dumpInterfaces(device: UsbDevice) {
        AppLogger.i(TAG, "========== USB Interfaces (all) ==========")
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            AppLogger.i(
                TAG,
                "  Interface[$index]: id=${intf.id} alt=${intf.alternateSetting} " +
                    "class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} " +
                    "protocol=${intf.interfaceProtocol} eps=${intf.endpointCount}"
            )
            for (endpointIndex in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(endpointIndex)
                val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                AppLogger.i(
                    TAG,
                    "    Endpoint[$endpointIndex]: addr=0x${endpoint.address.toString(16)} " +
                        "dir=$direction type=${endpoint.type} attr=0x${endpoint.attributes.toString(16)} " +
                        "maxPacket=${endpoint.maxPacketSize} interval=${endpoint.interval}"
                )
            }
        }
        AppLogger.i(TAG, "==========================================")
    }
}
