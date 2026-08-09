package com.rawsmusic.module.player.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Keeps Android USB broadcast decoding separate from transport/lifecycle work.
 * Every callback is posted to the serialized USB owner before it reaches the manager.
 */
internal class UsbBroadcastReceiver(
    private val transportOwner: UsbTransportCommandQueue,
    private val onPermissionResult: (UsbDevice?, Boolean) -> Unit,
    private val onDeviceDetached: (UsbDevice?) -> Unit,
    private val onDeviceAttached: (UsbDevice?) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val device = intent.usbDevice()
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                transportOwner.post("broadcast-permission-result") {
                    onPermissionResult(device, granted)
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = intent.usbDevice()
                transportOwner.post("broadcast-detach") {
                    onDeviceDetached(device)
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = intent.usbDevice()
                if (device != null) {
                    transportOwner.post("broadcast-attach") {
                        onDeviceAttached(device)
                    }
                }
            }
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private companion object {
        const val ACTION_USB_PERMISSION = UsbPermissionIntentFactory.ACTION_USB_PERMISSION
    }
}
