package com.rawsmusic

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.PlayerSceneController

/** Owns the portrait Activity's rotation proposal and landscape-player handoff. */
internal class MainActivityRotationCoordinator(
    private val activity: ComponentActivity,
    private val currentScene: () -> PlayerSceneController.Scene,
    private val homeFullCoverActive: () -> Boolean,
    private val playerModalVisible: () -> Boolean,
    private val hasCurrentSong: () -> Boolean,
    private val onPortraitDetected: () -> Unit,
    private val onLaunchLandscapePlayer: () -> Unit,
) {
    companion object {
        private const val TAG = "LandscapeEntry"
        private const val LAUNCH_DEBOUNCE_MS = 1_200L
    }

    var launchArmed: Boolean = true
        private set

    var activityLaunchPending: Boolean = false
        private set

    private var lastLaunchMs = 0L
    private var orientationListener: OrientationEventListener? = null

    fun setup() {
        orientationListener = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || activity.isFinishing || activity.isDestroyed) return
                if (!orientation.isPortraitOrientation()) return
                if (!launchArmed) {
                    launchArmed = true
                    onPortraitDetected()
                }
            }
        }
    }

    fun onResume() {
        activityLaunchPending = false
        orientationListener?.takeIf { it.canDetectOrientation() }?.enable()
    }

    fun onStop() {
        orientationListener?.disable()
    }

    fun onDestroy() {
        orientationListener?.disable()
        orientationListener = null
    }

    fun setHomeFullCoverPolicy(launchArmed: Boolean, clearPendingLaunch: Boolean) {
        this.launchArmed = launchArmed
        if (clearPendingLaunch) activityLaunchPending = false
    }

    fun syncPolicy(scene: PlayerSceneController.Scene) {
        if (scene != PlayerSceneController.Scene.PLAYER) launchArmed = true
        val allowSystemRotationProposal =
            !homeFullCoverActive() &&
                scene == PlayerSceneController.Scene.PLAYER &&
                launchArmed &&
                hasCurrentSong()
        val target = if (allowSystemRotationProposal) {
            ActivityInfo.SCREEN_ORIENTATION_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (activity.requestedOrientation != target) {
            AppLogger.i(
                TAG,
                "rotationPolicy scene=$scene armed=$launchArmed target=$target " +
                    "config=${activity.resources.configuration.orientation}"
            )
            activity.requestedOrientation = target
        }
    }

    fun launchFromSystemRotation() {
        if (
            homeFullCoverActive() ||
            currentScene() != PlayerSceneController.Scene.PLAYER ||
            playerModalVisible() ||
            !hasCurrentSong() ||
            activityLaunchPending
        ) return

        val now = SystemClock.uptimeMillis()
        if (now - lastLaunchMs < LAUNCH_DEBOUNCE_MS) return
        launchArmed = false
        activityLaunchPending = true
        lastLaunchMs = now
        AppLogger.i(TAG, "launch from accepted system rotation")
        onLaunchLandscapePlayer()
    }

    private fun Int.isPortraitOrientation(): Boolean = this in 0..28 || this in 332..359
}
