package com.rawsmusic

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.scene.HomeFullCoverBackRuntime
import com.rawsmusic.core.ui.scene.NavigationState
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.ui.settings.SettingsBackHandoffRuntime
import android.os.Handler

/** Owns Android predictive-back arbitration for the Activity scene stack. */
internal class MainActivityPredictiveBackCoordinator(
    private val activity: ComponentActivity,
    private val mainHandler: Handler,
    private val mainNavigation: NavigationState,
    private val playerSceneController: () -> PlayerSceneController?,
    private val homeFullCoverOverlayActive: () -> Boolean,
    private val audioInfoSharedWindowActive: () -> Boolean,
    private val audioInfoPopupShowing: () -> Boolean,
    private val metadataEditorShowing: () -> Boolean,
    private val metadataDeleteConfirmShowing: () -> Boolean,
    private val metadataDetailVisible: () -> Boolean,
    private val songActionSheetShowing: () -> Boolean,
    private val playlistPickerShowing: () -> Boolean,
    private val playModePopupShowing: () -> Boolean,
    private val metadataCardPopupShowing: () -> Boolean,
    private val composePlayerModalVisible: () -> Boolean,
    private val composePlayerModalDismissAction: () -> (() -> Unit)?,
    private val dismissAudioInfoPopup: () -> Unit,
    private val closeMetadataDetail: () -> Unit,
    private val hidePlayModePopup: () -> Unit,
    private val onActivityBackFallback: () -> Unit,
) {
    private enum class BackDragType { NONE, COVER, CONTAINER, HOME_FULL_COVER }

    private var callback: OnBackPressedCallback? = null
    private var dragType = BackDragType.NONE
    private var redispatching = false
    private val handoffRelease = Runnable { updateRegistration() }

    fun setup() {
        callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                dragType = BackDragType.NONE
                if (!PersonalizationPreferences.predictiveBackAnimationEnabled) return
                if (com.rawsmusic.core.ui.scene.pages.SourcePortalBackRuntime.shouldSuppressSceneBack()) return
                if (
                    com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime.activeCount > 0 ||
                    com.rawsmusic.core.ui.scene.pages.SourcePortalBackRuntime.activeCount > 0
                ) return
                if (
                    audioInfoPopupShowing() ||
                    metadataEditorShowing() ||
                    metadataDeleteConfirmShowing() ||
                    metadataDetailVisible() ||
                    songActionSheetShowing() ||
                    playlistPickerShowing() ||
                    playModePopupShowing() ||
                    metadataCardPopupShowing()
                ) return
                if (composePlayerModalVisible() || composePlayerModalDismissAction() != null) return

                if (homeFullCoverOverlayActive()) {
                    dragType = if (HomeFullCoverBackRuntime.start()) {
                        BackDragType.HOME_FULL_COVER
                    } else {
                        BackDragType.NONE
                    }
                    return
                }

                val sceneController = playerSceneController() ?: return
                val swipeRight = backEvent.swipeEdge == BackEventCompat.EDGE_LEFT
                when {
                    audioInfoSharedWindowActive() && mainNavigation.canNavigateBack() -> {
                        val direction = if (swipeRight) 1f else -1f
                        dragType = if (mainNavigation.startBackDrag(direction)) {
                            BackDragType.CONTAINER
                        } else {
                            BackDragType.NONE
                        }
                    }
                    sceneController.currentScene == PlayerSceneController.Scene.PLAYER -> {
                        sceneController.startCoverDrag(swipeRight, PlayerSceneController.Scene.MAIN)
                        dragType = BackDragType.COVER
                    }
                    sceneController.currentScene == PlayerSceneController.Scene.LYRIC -> {
                        sceneController.startLyricToPlayerDrag()
                        dragType = BackDragType.COVER
                    }
                    sceneController.currentScene == PlayerSceneController.Scene.QUEUE -> {
                        sceneController.startCoverDrag(swipeRight, PlayerSceneController.Scene.MAIN)
                        dragType = BackDragType.COVER
                    }
                    sceneController.currentScene == PlayerSceneController.Scene.ALBUM_DETAIL -> {
                        sceneController.startCoverDrag(swipeRight, PlayerSceneController.Scene.PLAYER)
                        dragType = BackDragType.COVER
                    }
                    sceneController.currentScene == PlayerSceneController.Scene.MAIN &&
                        !mainNavigation.isAtHome() -> {
                        val direction = if (swipeRight) 1f else -1f
                        dragType = if (mainNavigation.startBackDrag(direction)) {
                            BackDragType.CONTAINER
                        } else {
                            BackDragType.NONE
                        }
                    }
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (!PersonalizationPreferences.predictiveBackAnimationEnabled) return
                when (dragType) {
                    BackDragType.COVER -> playerSceneController()?.updateCoverDragProgress(backEvent.progress)
                    BackDragType.CONTAINER -> mainNavigation.updateBackDrag(backEvent.progress)
                    BackDragType.HOME_FULL_COVER -> HomeFullCoverBackRuntime.progress(backEvent.progress)
                    BackDragType.NONE -> Unit
                }
            }

            override fun handleOnBackPressed() {
                when (dragType) {
                    BackDragType.COVER -> {
                        playerSceneController()?.releaseCoverDrag(true, 0f)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.CONTAINER -> {
                        mainNavigation.releaseBackDrag(commit = true)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.HOME_FULL_COVER -> {
                        HomeFullCoverBackRuntime.complete()
                        dragType = BackDragType.NONE
                    }
                    BackDragType.NONE -> {
                        if (homeFullCoverOverlayActive() && HomeFullCoverBackRuntime.complete()) {
                            dragType = BackDragType.NONE
                            return
                        }
                        if (com.rawsmusic.core.ui.scene.pages.SourcePortalBackRuntime.consumeSuppressedSceneBack()) {
                            dragType = BackDragType.NONE
                            return
                        }
                        if (
                            com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime.activeCount > 0 ||
                            com.rawsmusic.core.ui.scene.pages.SourcePortalBackRuntime.activeCount > 0
                        ) {
                            redispatchBackBelowSceneCallback()
                        } else if (audioInfoPopupShowing()) {
                            dismissAudioInfoPopup()
                        } else if (metadataDetailVisible()) {
                            closeMetadataDetail()
                        } else if (playModePopupShowing()) {
                            hidePlayModePopup()
                        } else if (composePlayerModalVisible() || composePlayerModalDismissAction() != null) {
                            composePlayerModalDismissAction()?.invoke()
                        } else {
                            onActivityBackFallback()
                        }
                        dragType = BackDragType.NONE
                    }
                }
            }

            override fun handleOnBackCancelled() {
                when (dragType) {
                    BackDragType.COVER -> playerSceneController()?.releaseCoverDrag(false, 0f)
                    BackDragType.CONTAINER -> mainNavigation.releaseBackDrag(commit = false)
                    BackDragType.HOME_FULL_COVER -> HomeFullCoverBackRuntime.cancel()
                    BackDragType.NONE -> Unit
                }
                dragType = BackDragType.NONE
            }
        }
        callback?.let { activity.onBackPressedDispatcher.addCallback(activity, it) }
        updateRegistration()
    }

    fun disable() {
        callback?.isEnabled = false
    }

    fun removeHandoffRelease() {
        mainHandler.removeCallbacks(handoffRelease)
    }

    fun resetGestureOwnership(reason: String) {
        when (dragType) {
            BackDragType.COVER -> playerSceneController()?.releaseCoverDrag(false, 0f)
            BackDragType.CONTAINER -> mainNavigation.releaseBackDrag(commit = false)
            BackDragType.HOME_FULL_COVER -> HomeFullCoverBackRuntime.cancel()
            BackDragType.NONE -> Unit
        }
        if (dragType != BackDragType.NONE) {
            AppLogger.i("PredictiveBack", "reset gesture owner: reason=$reason old=$dragType")
        }
        dragType = BackDragType.NONE
    }

    fun redispatchBackBelowSceneCallback() {
        val currentCallback = callback ?: return
        if (redispatching) return
        redispatching = true
        currentCallback.isEnabled = false
        try {
            activity.onBackPressedDispatcher.onBackPressed()
        } finally {
            redispatching = false
            updateRegistration()
        }
    }

    fun updateRegistration(
        activeMiuixOverlayCount: Int = com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime.activeCount,
        activeSourcePortalBackCount: Int = com.rawsmusic.core.ui.scene.pages.SourcePortalBackRuntime.activeCount,
    ) {
        val currentCallback = callback ?: return
        val sceneController = playerSceneController()
        if (sceneController == null) {
            currentCallback.isEnabled = false
            return
        }
        val windowReadyForSceneBack = activity.window.decorView.hasWindowFocus() &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (!windowReadyForSceneBack) {
            currentCallback.isEnabled = false
            return
        }
        val handoffRemainingMs = SettingsBackHandoffRuntime.remainingBlockMs()
        if (handoffRemainingMs > 0L) {
            currentCallback.isEnabled = false
            mainHandler.removeCallbacks(handoffRelease)
            mainHandler.postDelayed(handoffRelease, handoffRemainingMs + 16L)
            return
        }
        mainHandler.removeCallbacks(handoffRelease)
        val isAtAppRoot = sceneController.currentScene == PlayerSceneController.Scene.MAIN &&
            mainNavigation.isAtHome()
        currentCallback.isEnabled = activeMiuixOverlayCount == 0 &&
            activeSourcePortalBackCount == 0 &&
            (homeFullCoverOverlayActive() || !isAtAppRoot)
    }
}
