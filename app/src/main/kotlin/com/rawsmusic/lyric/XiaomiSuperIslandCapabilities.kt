package com.rawsmusic.lyric

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/** Official HyperOS capability probes used by Super Island diagnostics. */
internal object XiaomiSuperIslandCapabilities {
    private const val TAG = "RawSuperIsland"
    private const val FOCUS_PROTOCOL_KEY = "notification_focus_protocol"
    private const val ISLAND_PROPERTY_KEY = "persist.sys.feature.island"
    private val logged = AtomicBoolean(false)

    fun logOnce(context: Context) {
        if (!logged.compareAndSet(false, true)) return
        val protocol = runCatching {
            Settings.System.getInt(context.contentResolver, FOCUS_PROTOCOL_KEY, 0)
        }.getOrDefault(0)
        val islandProperty = readSystemProperty(ISLAND_PROPERTY_KEY)
        val focusPermission = queryFocusPermission(context)
        AppLogger.d(
            TAG,
            "SUPER_ISLAND_CAPABILITY protocol=$protocol " +
                "islandProperty=${islandProperty ?: "unknown"} " +
                "focusPermission=${focusPermission ?: "unknown"} " +
                "supportsIsland=${protocol >= 3 || islandProperty == true}"
        )
    }

    private fun queryFocusPermission(context: Context): Boolean? = runCatching {
        val args = Bundle().apply { putString("package", context.packageName) }
        context.contentResolver.call(
            Uri.parse("content://miui.statusbar.notification.public"),
            "canShowFocus",
            null,
            args
        )?.getBoolean("canShowFocus", false)
    }.getOrNull()

    private fun readSystemProperty(key: String): Boolean? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val value = clazz.getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as? String
        when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }.getOrNull()
}
