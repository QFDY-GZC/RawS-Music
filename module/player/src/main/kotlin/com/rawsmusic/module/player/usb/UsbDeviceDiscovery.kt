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
        val deviceList = usbManager.deviceList
        AppLogger.d(TAG, "Scanning ${deviceList.size} USB devices...")
        for (device in deviceList.values) {
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
            if (isUsbAudioOutputDevice(device)) {
                AppLogger.i(TAG, "Found USB audio device: ${device.productName}")
                return device
            }
        }
        AppLogger.w(TAG, "No USB audio device with ISO OUT endpoint found")
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
