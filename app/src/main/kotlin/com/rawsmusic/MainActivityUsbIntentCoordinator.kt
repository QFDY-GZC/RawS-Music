package com.rawsmusic

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService

/** Handles USB attach intents before the Activity enters its normal scene lifecycle. */
internal class MainActivityUsbIntentCoordinator(
    private val packageName: String,
    private val packageManager: PackageManager,
    private val activity: MainActivity,
    private val controller: () -> PlayerController?,
) {
    fun handleAttachIntent(intent: Intent?, reason: String) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return

        AppLogger.i("MainActivity", "USB attach intent received: ${device.deviceName} reason=$reason")
        val handled = PlayerService.dispatchUsbAttachIntent(activity, device, reason)
        if (!handled) {
            controller()?.handleUsbDeviceAttachIntent(device, reason)
        }
    }

    fun setAttachAliasEnabled(enabled: Boolean, reason: String) {
        val component = ComponentName(packageName, "$packageName.UsbAttachActivityAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
            AppLogger.i("MainActivity", "USB attach alias enabled=$enabled reason=$reason")
        } catch (e: Exception) {
            AppLogger.w(
                "MainActivity",
                "USB attach alias toggle failed enabled=$enabled reason=$reason: ${e.message}",
            )
        }
    }
}
