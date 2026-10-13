package com.rawsmusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun PlayerKeepScreenOnEffect(enabled: Boolean, playerVisible: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, enabled, playerVisible) {
        val previousKeepScreenOn = view.keepScreenOn
        fun update() {
            view.keepScreenOn = previousKeepScreenOn ||
                (enabled && playerVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = previousKeepScreenOn
        }
    }
}
