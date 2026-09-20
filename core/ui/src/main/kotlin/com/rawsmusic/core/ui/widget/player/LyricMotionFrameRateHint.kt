package com.rawsmusic.core.ui.widget.player

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * Temporary high-refresh lease for active lyric motion.
 *
 * Android 15+ can adapt render rate per View.  The full Compose host is the surface-producing
 * View, so request the platform HIGH category only while a karaoke lift or pinch transaction is
 * actively producing frames and release it when motion stops. A reference count prevents two
 * lyric surfaces (for example a transition overlap) from clearing each other's request.
 */
private object LyricFrameRateLease {
    private val counts = WeakHashMap<View, Int>()

    fun acquire(view: View) {
        if (Build.VERSION.SDK_INT < 35) return
        val count = counts[view] ?: 0
        counts[view] = count + 1
        if (count == 0) {
            view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH)
        }
    }

    fun release(view: View) {
        if (Build.VERSION.SDK_INT < 35) return
        val count = counts[view] ?: return
        if (count <= 1) {
            counts.remove(view)
            view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
        } else {
            counts[view] = count - 1
        }
    }
}

@Composable
internal fun LyricMotionFrameRateHint(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (enabled) LyricFrameRateLease.acquire(view)
        onDispose {
            if (enabled) LyricFrameRateLease.release(view)
        }
    }
}
