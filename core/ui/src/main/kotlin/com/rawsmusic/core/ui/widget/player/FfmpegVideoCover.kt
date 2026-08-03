package com.rawsmusic.core.ui.widget.player

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge

@Composable
internal fun FfmpegVideoCover(
    uri: String,
    active: Boolean,
    cornerRadiusDp: Float,
    modifier: Modifier = Modifier
) {
    if (uri.isBlank()) return
    val lifecycleOwner = LocalLifecycleOwner.current
    val radiusPx = with(LocalDensity.current) { cornerRadiusDp.dp.toPx() }
    var lifecycleActive by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleActive = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { context -> FfmpegVideoCoverTextureView(context) },
        update = { view ->
            view.setCornerRadius(radiusPx)
            view.setSource(uri)
            view.setPlaybackActive(active && lifecycleActive)
        },
        modifier = modifier.fillMaxSize()
    )
}

private class FfmpegVideoCoverTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var source = ""
    private var sessionHandle = 0L
    private var playbackActive = true
    private var cornerRadiusPx = Float.NaN

    init {
        surfaceTextureListener = this
        isOpaque = false
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setSource(value: String) {
        if (source == value) return
        source = value
        restartSession()
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        FFmpegBridge.setVideoCoverSessionActive(sessionHandle, active)
    }

    fun setCornerRadius(radiusPx: Float) {
        if (cornerRadiusPx == radiusPx) return
        cornerRadiusPx = radiusPx
        if (radiusPx <= 0f) {
            clipToOutline = false
            outlineProvider = null
            return
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        clipToOutline = true
    }

    private fun restartSession() {
        releaseSession()
        val texture = surfaceTexture ?: return
        if (source.isBlank() || !isAvailable) return
        val sourceUri = Uri.parse(source)
        if (sourceUri.scheme.equals("http", ignoreCase = true) ||
            sourceUri.scheme.equals("https", ignoreCase = true)
        ) {
            val surface = Surface(texture)
            try {
                sessionHandle = FFmpegBridge.createVideoCoverUrlSession(source, surface)
                FFmpegBridge.setVideoCoverSessionActive(sessionHandle, playbackActive)
            } finally {
                surface.release()
            }
            return
        }
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(sourceUri, "r")
        }.getOrNull() ?: return
        descriptor.use {
            val surface = Surface(texture)
            try {
                sessionHandle = FFmpegBridge.createVideoCoverSession(it.fd, surface)
                FFmpegBridge.setVideoCoverSessionActive(sessionHandle, playbackActive)
            } finally {
                surface.release()
            }
        }
    }

    private fun releaseSession() {
        FFmpegBridge.releaseVideoCoverSession(sessionHandle)
        sessionHandle = 0L
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        restartSession()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releaseSession()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        releaseSession()
        super.onDetachedFromWindow()
    }
}
