package com.rawsmusic.core.ui.widget.bitmaps

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rawsmusic.core.common.utils.AppLogger
import java.util.WeakHashMap

internal fun Bitmap.hasNativeArtworkHdr(): Boolean =
    Build.VERSION.SDK_INT >= 34 && !isRecycled && hasGainmap()

// Visible scene hosts may share an Activity window during handoff.
// Releasing one owner must not switch another visible HDR owner back to SDR.
internal object PlaybackHdrWindows {
    private class Owners(val originalMode: Int, val originalHeadroom: Float) {
        val active = mutableMapOf<Any, Float>()
    }
    private val windows = WeakHashMap<Window, Owners>()

    fun update(window: Window, owner: Any, enabled: Boolean, headroom: Float = 0f) {
        if (Build.VERSION.SDK_INT < 34) return
        val owners = windows[window] ?: if (enabled) {
            Owners(window.colorMode, if (Build.VERSION.SDK_INT >= 35) window.attributes.desiredHdrHeadroom else 0f)
                .also { windows[window] = it }
        } else return
        if (enabled) owners.active[owner] = headroom else owners.active.remove(owner)
        val mode = if (owners.active.isNotEmpty()) ActivityInfo.COLOR_MODE_HDR else owners.originalMode
        if (window.colorMode != mode) window.colorMode = mode
        if (Build.VERSION.SDK_INT >= 35) {
            val desired = when {
                owners.active.isEmpty() -> owners.originalHeadroom
                owners.active.values.any { it == 0f } -> 0f
                else -> owners.active.values.max()
            }
            if (window.attributes.desiredHdrHeadroom != desired) window.setDesiredHdrHeadroom(desired)
        }
        if (owners.active.isEmpty()) windows.remove(window)
    }
}

internal fun Context.playerActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}

/** Reports native artwork eligibility. Only the scene host controls window color mode. */
@Composable
internal fun PlaybackArtworkHdr(state: PlaybackArtworkTransitionState, visible: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, view, lifecycle, visible) {
        val window = view.context.playerActivity()?.window
        var lastReport: String? = null
        val display = view.display
        var capable = Build.VERSION.SDK_INT >= 26 && display?.isHdr == true
        var lastInput: Pair<Boolean, Boolean>? = null
        fun update(force: Boolean = false) {
            if (window == null) return
            val current = state.visual(state.foregroundCurrentToken())
            val target = if (state.ratio > 0f) state.visual(state.foregroundTargetToken()) else null
            val native = current?.hasNativeArtworkHdr() == true || target?.hasNativeArtworkHdr() == true
            val active = visible && view.isAttachedToWindow && view.windowVisibility == android.view.View.VISIBLE &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            // Ratio callbacks are animation-frame hot paths. Capability/window/headroom queries
            // run only when admission changes, or when the system explicitly reports a change.
            val input = native to active
            if (!force && lastInput == input) return
            lastInput = input
            val request = shouldRequestPlaybackArtworkHdr(
                Build.VERSION.SDK_INT, capable, view.isHardwareAccelerated, active, native,
            )
            try {
                val ratioAvailable = Build.VERSION.SDK_INT >= 34 && display?.isHdrSdrRatioAvailable == true
                val report = "sdk=${Build.VERSION.SDK_INT} displayHdr=$capable nativeGainmap=$native " +
                    "visible=$active contentEligible=$request windowMode=${if (Build.VERSION.SDK_INT >= 26) window.colorMode else 0} " +
                    "headroom=${if (ratioAvailable) display?.hdrSdrRatio else "unknown"}"
                if (report != lastReport) {
                    lastReport = report
                    AppLogger.i("ArtworkHdr", report)
                }
            } catch (error: RuntimeException) {
                val report = "fallback=${error.javaClass.simpleName}"
                if (lastReport != report) { lastReport = report; AppLogger.i("ArtworkHdr", report) }
            }
        }
        val observer = PlaybackArtworkPresentationObserver { _, _ -> update() }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> update(true) }
        val manager = view.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) { if (id == display?.displayId) { capable = false; update(true) } }
            override fun onDisplayChanged(id: Int) {
                if (id == display?.displayId) {
                    capable = Build.VERSION.SDK_INT >= 26 && display.isHdr
                    update(true)
                }
            }
        }
        state.addPresentationObserver(observer)
        lifecycle.addObserver(lifecycleObserver)
        manager?.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        val ratioListener = java.util.function.Consumer<android.view.Display> { update(true) }
        val ratioListening = Build.VERSION.SDK_INT >= 34 && display?.isHdrSdrRatioAvailable == true
        if (ratioListening) display?.registerHdrSdrRatioChangedListener(view.context.mainExecutor, ratioListener)
        update()
        onDispose {
            state.removePresentationObserver(observer)
            lifecycle.removeObserver(lifecycleObserver)
            manager?.unregisterDisplayListener(listener)
            if (ratioListening) display?.unregisterHdrSdrRatioChangedListener(ratioListener)
        }
    }
}
