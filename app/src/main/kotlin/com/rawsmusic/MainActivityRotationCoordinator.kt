package com.rawsmusic

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.PlayerSceneController

/** Rotation reflows the retained main window; the standalone player is a manual destination. */
internal class MainActivityRotationCoordinator(
    private val activity: ComponentActivity,
) {
    fun syncPolicy(scene: PlayerSceneController.Scene) {
        val target = ActivityInfo.SCREEN_ORIENTATION_USER
        if (activity.requestedOrientation == target) return
        AppLogger.i(
            "WindowRotation",
            "scene=$scene target=$target config=${activity.resources.configuration.orientation}",
        )
        activity.requestedOrientation = target
    }
}
