package com.rawsmusic.module.player.usb

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences

class UsbVolumeController(
    private val context: Context,
    private val nativeHandle: Long = 0L
) {
    companion object {
        private const val TAG = "UsbVolumeCtrl"
        const val DEFAULT_STEP = 0.04f
        /** 硬件音量最小 dB 值（系统音量 0 时映射到此值） */
        private const val MIN_HARDWARE_DB = -60.0f
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    var curLinear: Float = AppPreferences.Player.usbHardwareVolume
        private set

    private val canControlHardwareVolume: Boolean
        get() = AppPreferences.Player.hardwareFeatureUnitEnabled &&
                try {
                    val h = if (nativeHandle != 0L) nativeHandle else UsbAudioEngine.currentHandle
                    UsbAudioEngine.nativeCanControlVolume(h)
                } catch (_: Throwable) { false }

    private val shouldSyncSystemVolume: Boolean
        get() = !AppPreferences.Player.bitPerfectEnabled

    private fun nativeSet(linear: Float) {
        val h = if (nativeHandle != 0L) nativeHandle else UsbAudioEngine.currentHandle
        val rc = UsbAudioEngine.nativeSetVolume(h, linear)
        if (rc < 0) {
            AppLogger.w(TAG, "nativeSetVolume failed, rc=$rc, linear=$linear")
        } else {
            curLinear = linear
            AppPreferences.Player.usbHardwareVolume = linear
            AppLogger.d(TAG, "hardware volume set → $linear")
        }
    }

    fun stepVolume(delta: Float = DEFAULT_STEP) {
        if (!canControlHardwareVolume) {
            AppLogger.w(TAG, "stepVolume ignored – Feature Unit disabled or not present")
            return
        }
        nativeSet((curLinear + delta).coerceIn(0f, 1f))
    }

    fun setVolumeLinear(linear: Float, persist: Boolean = true) {
        if (!canControlHardwareVolume) {
            AppLogger.w(TAG, "setVolumeLinear ignored – Feature Unit disabled or not present")
            return
        }
        val target = linear.coerceIn(0f, 1f)
        nativeSet(target)
        if (!persist) {
            AppPreferences.Player.usbHardwareVolume = target
        }
    }

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            syncFromSystemVolume()
        }
    }

    fun register() {
        if (shouldSyncSystemVolume) {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
        }
        curLinear = AppPreferences.Player.usbHardwareVolume
        if (canControlHardwareVolume) nativeSet(curLinear)
        else syncFromSystemVolume() // 非硬件音量模式：立即同步系统音量到软件音量
        AppLogger.d(TAG, "UsbVolumeController registered, curLinear=$curLinear, bitPerfect=${AppPreferences.Player.bitPerfectEnabled}, canHw=$canControlHardwareVolume")
    }

    fun unregister() {
        if (shouldSyncSystemVolume) {
            context.contentResolver.unregisterContentObserver(volumeObserver)
        }
        AppPreferences.Player.usbHardwareVolume = curLinear
        AppLogger.d(TAG, "UsbVolumeController unregistered, saved curLinear=$curLinear")
    }

    private fun syncFromSystemVolume() {
        // bit-perfect 模式下不同步系统音量到硬件音量
        // 用户通过 DAC 硬件按钮控制音量，系统音量可能很低，不应覆盖
        if (AppPreferences.Player.bitPerfectEnabled) return

        val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxSys == 0) return

        if (canControlHardwareVolume) {
            // dB 映射：系统音量 0 → -60dB，系统音量 max → 0dB
            // 避免线性映射在低音量时产生巨大跳变（如 0→-60dB vs 1→-28dB）
            val ratio = curSys.toFloat() / maxSys  // 0.0 ~ 1.0
            val db = (1.0f - ratio) * MIN_HARDWARE_DB  // -60dB ~ 0dB
            val linear = Math.pow(10.0, db / 20.0).toFloat()
            nativeSet(linear)
            AppLogger.d(TAG, "bit-perfect hw volume sync → ${"%.3f".format(linear)} (${db.toInt()}dB, sys=$curSys/$maxSys)")
            return
        }

        val linear = curSys.toFloat() / maxSys
        val gain = linear * linear
        nativeSet(gain)
        AppLogger.d(TAG, "system volume sync → $gain (sys=$curSys/$maxSys)")
    }
}
