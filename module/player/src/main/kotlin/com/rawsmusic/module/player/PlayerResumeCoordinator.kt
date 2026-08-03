package com.rawsmusic.module.player

import android.util.Log
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.usb.UsbAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns resume decisions for USB warm playback and ordinary Android output. */
internal class PlayerResumeCoordinator(
    private val scope: CoroutineScope,
    private val tag: String,
    private val isReleased: () -> Boolean,
    private val isUsbExclusiveActive: () -> Boolean,
    private val nativeStreamState: () -> UsbAudioEngine.NativeStreamState,
    private val nativeSessionBroken: () -> Boolean,
    private val hasCurrentSong: () -> Boolean,
    private val recoverUsbExclusiveAsync: () -> Unit,
    private val ffmpegResume: () -> Boolean,
    private val isUsbHardwareVolumeRouteActive: () -> Boolean,
    private val transitionPlayState: (PlayState, String) -> Unit,
    private val startUsbKeepAlive: (String) -> Unit,
    private val startProgressUpdate: () -> Unit,
    private val resetPreparedUsbSession: () -> Unit,
    private val isRenderSwitching: () -> Boolean,
    private val clearRenderSwitching: () -> Unit,
    private val resumeSystemAudio: () -> Unit,
    private val isAppReleased: () -> Boolean = isReleased,
) {
    suspend fun resume() {
        if (isUsbExclusiveActive()) {
            val nativeState = nativeStreamState()
            if (nativeSessionBroken()) {
                AppLogger.w(tag, "resume: USB session broken, recovering")
                if (hasCurrentSong()) recoverUsbExclusiveAsync()
                return
            }
            if (nativeState == UsbAudioEngine.NativeStreamState.STANDBY) {
                AppLogger.w(tag, "resume: legacy standby detected, recovering with full USB reopen")
                recoverUsbExclusiveAsync()
                return
            }
            if (nativeState == UsbAudioEngine.NativeStreamState.STREAMING) {
                val resumed = ffmpegResume()
                if (resumed && isUsbHardwareVolumeRouteActive()) {
                    AppLogger.i(tag, "USB warm resume keeps Feature Unit at the user value")
                }
                if (!resumed) {
                    AppLogger.w(tag, "resume: warm decoder resume failed, recovering")
                    recoverUsbExclusiveAsync()
                    return
                }
                transitionPlayState(PlayState.PLAYING, "resume_warm")
                startUsbKeepAlive("manual_resume_warm_streaming")
                startProgressUpdate()
                return
            }
            if (nativeState == UsbAudioEngine.NativeStreamState.PREPARED) {
                resetPreparedUsbSession()
                ffmpegResume()
                transitionPlayState(PlayState.PLAYING, "resume_prepared")
                startProgressUpdate()
                return
            }
            AppLogger.w(tag, "resume: unexpected nativeState=$nativeState, recovering")
            recoverUsbExclusiveAsync()
            return
        }

        if (isRenderSwitching()) {
            Log.i(tag, "Render switching in progress, delaying resume")
            scope.launch {
                var waitCount = 0
                while (isRenderSwitching() && waitCount < 50) {
                    delay(10)
                    waitCount++
                }
                clearRenderSwitching()
                withContext(Dispatchers.Main) {
                    if (!isAppReleased()) resumeSystemAudio()
                }
            }
            return
        }
        resumeSystemAudio()
    }
}
