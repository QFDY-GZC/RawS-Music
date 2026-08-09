package com.rawsmusic

import com.rawsmusic.core.ui.scene.NavigationState

/**
 * Owns the small runtime lifecycle bridge that keeps scene back handling,
 * foreground power state, and the realtime visualizer in sync with the Activity.
 * The Activity remains responsible for Android's super-call ordering.
 */
internal class MainActivityRuntimeLifecycleCoordinator(
    private val mainNavState: NavigationState,
    private val isSceneControllerInitialized: () -> Boolean,
    private val resetPredictiveBackGestureOwnership: (String) -> Unit,
    private val updatePredictiveBackRegistration: () -> Unit,
    private val disablePredictiveBack: () -> Unit,
    private val removePredictiveBackHandoff: () -> Unit,
    private val postToWindow: ((() -> Unit) -> Unit),
    private val postToWindowDelayed: ((Long, () -> Unit) -> Unit),
    private val detachUsbStatusNotice: () -> Unit,
    private val stopRotation: () -> Unit,
    private val setActivityForeground: (Boolean) -> Unit,
    private val stopRealtimeSpectrum: () -> Unit,
    private val resetVisualizerSpectrum: () -> Unit,
) {
    fun onPostResume() {
        resetPredictiveBackGestureOwnership("activity_post_resume")
        if (!isSceneControllerInitialized()) return

        mainNavState.resetTransientBackState()
        updatePredictiveBackRegistration()
        postToWindow { updatePredictiveBackRegistration() }
        postToWindowDelayed(32L) { updatePredictiveBackRegistration() }
    }

    fun onStop() {
        detachUsbStatusNotice()
        resetPredictiveBackGestureOwnership("activity_on_stop")
        mainNavState.resetTransientBackState()
        disablePredictiveBack()
        removePredictiveBackHandoff()
        stopRotation()
        setActivityForeground(false)
        stopRealtimeSpectrum()
        resetVisualizerSpectrum()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) {
            resetPredictiveBackGestureOwnership("window_focus_lost")
            disablePredictiveBack()
            return
        }
        if (!isSceneControllerInitialized()) return

        updatePredictiveBackRegistration()
        postToWindow { updatePredictiveBackRegistration() }
        postToWindowDelayed(32L) { updatePredictiveBackRegistration() }
    }
}
