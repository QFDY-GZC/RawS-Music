package com.rawsmusic.module.player.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Build

/** Android permission boundary; native only receives a device after this succeeds. */
internal object UsbPermissionIntentFactory {
    const val ACTION_USB_PERMISSION = "com.rawsmusic.USB_PERMISSION"

    fun create(context: Context, device: UsbDevice): PendingIntent {
        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(context, device.deviceId, permissionIntent, flags)
    }
}
