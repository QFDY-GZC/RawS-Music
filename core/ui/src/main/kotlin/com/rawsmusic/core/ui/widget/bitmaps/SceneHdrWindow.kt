package com.rawsmusic.core.ui.widget.bitmaps

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.HdrPreferences
import com.rawsmusic.module.data.prefs.HdrScene
import com.rawsmusic.module.data.prefs.canKeepHdrWindow
import com.rawsmusic.module.data.prefs.observedHdrRatio

/** One scene-level owner per window, independent of cover loading and animation frames. */
@Composable
fun SceneHdrWindow(scene: HdrScene, transitionScene: HdrScene? = null) {
    val settings by HdrPreferences.settings.collectAsState()
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sceneName by rememberUpdatedState("$scene->$transitionScene")
    val headroom by rememberUpdatedState(settings.requestedHeadroom(Build.VERSION.SDK_INT))
    val refresh = remember(view, lifecycle) { arrayOfNulls<() -> Unit>(1) }
    val requested = settings.requests(scene, transitionScene)
    DisposableEffect(view, lifecycle, requested) {
        val window = view.context.playerActivity()?.window
        val owner = Any()
        val manager = view.context.getSystemService(DisplayManager::class.java)
        var lastReport: String? = null
        fun update() {
            if (window == null) return
            val supported = Build.VERSION.SDK_INT >= 26 && view.display?.isHdr == true
            val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                view.isAttachedToWindow && view.windowVisibility == View.VISIBLE
            val admitted = canKeepHdrWindow(Build.VERSION.SDK_INT, supported, view.isHardwareAccelerated, resumed, requested)
            try {
                PlaybackHdrWindows.update(window, owner, admitted, headroom)
                val report = "scene=$sceneName enabled=$requested displayHdr=$supported resumed=$resumed " +
                    "active=$admitted desiredHeadroom=$headroom windowMode=${if (Build.VERSION.SDK_INT >= 26) window.colorMode else 0}"
                if (lastReport != report) { lastReport = report; AppLogger.i("WindowHdr", report) }
            } catch (error: RuntimeException) {
                runCatching { PlaybackHdrWindows.update(window, owner, false) }
                AppLogger.w("WindowHdr", "fallback=${error.javaClass.simpleName}")
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = update()
            override fun onViewDetachedFromWindow(v: View) = update()
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = update()
            override fun onDisplayRemoved(id: Int) = update()
            override fun onDisplayChanged(id: Int) { if (id == view.display?.displayId) update() }
        }
        lifecycle.addObserver(observer)
        refresh[0] = { update() }
        view.addOnAttachStateChangeListener(attachListener)
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        update()
        onDispose {
            refresh[0] = null
            lifecycle.removeObserver(observer)
            view.removeOnAttachStateChangeListener(attachListener)
            manager?.unregisterDisplayListener(listener)
            if (window != null) runCatching { PlaybackHdrWindows.update(window, owner, false) }
        }
    }
    // Preference changes update the existing lease, without a transient SDR reset.
    SideEffect { refresh[0]?.invoke() }
}

/** Live capability state; no polling, decoder or additional worker thread. */
@Composable
fun rememberHdrWindowSupported(): Boolean = rememberHdrDisplayStatus().supported

data class HdrDisplayStatus(
    val supported: Boolean = false,
    val actualRatio: Float? = null,
    val windowHdr: Boolean = false,
)

@Composable
fun rememberHdrDisplayStatus(): HdrDisplayStatus {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember(view) { mutableStateOf(HdrDisplayStatus()) }
    val refresh = remember(view, lifecycle) { arrayOfNulls<() -> Unit>(1) }
    DisposableEffect(view, lifecycle) {
        var ratioDisplay: android.view.Display? = null
        lateinit var ratioListener: java.util.function.Consumer<android.view.Display>
        fun bindRatioListener() {
            if (Build.VERSION.SDK_INT < 34) return
            val current = view.display?.takeIf { it.isHdrSdrRatioAvailable }
            if (current == ratioDisplay) return
            ratioDisplay?.unregisterHdrSdrRatioChangedListener(ratioListener)
            ratioDisplay = current
            current?.registerHdrSdrRatioChangedListener(view.context.mainExecutor, ratioListener)
        }
        fun update() {
            status = runCatching {
                bindRatioListener()
                val display = view.display
                val supported = Build.VERSION.SDK_INT >= 34 && display?.isHdr == true && view.isHardwareAccelerated
                val available = Build.VERSION.SDK_INT >= 34 && display?.isHdrSdrRatioAvailable == true
                val ratio = if (available) display!!.hdrSdrRatio else 0f
                HdrDisplayStatus(
                    supported = supported,
                    actualRatio = observedHdrRatio(Build.VERSION.SDK_INT, available, ratio),
                    windowHdr = Build.VERSION.SDK_INT >= 26 && view.context.playerActivity()?.window?.colorMode ==
                        android.content.pm.ActivityInfo.COLOR_MODE_HDR,
                )
            }.getOrDefault(HdrDisplayStatus())
        }
        ratioListener = java.util.function.Consumer { update() }
        val manager = view.context.getSystemService(DisplayManager::class.java)
        val observer = LifecycleEventObserver { _, _ -> update() }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = update()
            override fun onViewDetachedFromWindow(v: View) { status = HdrDisplayStatus() }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = update()
            override fun onDisplayRemoved(id: Int) = update()
            override fun onDisplayChanged(id: Int) = update()
        }
        view.addOnAttachStateChangeListener(attachListener)
        lifecycle.addObserver(observer)
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refresh[0] = { update() }
        update()
        onDispose {
            refresh[0] = null
            view.removeOnAttachStateChangeListener(attachListener)
            lifecycle.removeObserver(observer)
            manager?.unregisterDisplayListener(listener)
            if (Build.VERSION.SDK_INT >= 34) runCatching { ratioDisplay?.unregisterHdrSdrRatioChangedListener(ratioListener) }
        }
    }
    SideEffect { refresh[0]?.invoke() }
    return status
}
