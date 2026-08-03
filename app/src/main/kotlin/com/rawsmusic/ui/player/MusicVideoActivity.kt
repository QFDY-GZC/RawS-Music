package com.rawsmusic.ui.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Independent audible MV surface. Video-cover assignment remains the single source of truth;
 * this activity only owns the temporary decoder and never mutates playback queue state.
 */
class MusicVideoActivity : ComponentActivity() {
    private var videoView: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra("video_uri")?.takeIf(String::isNotBlank) ?: run {
            finish()
            return
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersiveMode()

        val controls = MediaController(this)
        val view = VideoView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            setMediaController(controls)
            controls.setAnchorView(this)
            setVideoURI(Uri.parse(source))
            setOnPreparedListener { player ->
                player.isLooping = false
                start()
            }
            setOnCompletionListener { finish() }
            setOnErrorListener { _, _, _ ->
                finish()
                true
            }
        }
        videoView = view
        setContentView(view)
        onBackPressedDispatcher.addCallback(this) { finish() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    override fun onStop() {
        videoView?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        videoView?.stopPlayback()
        videoView = null
        super.onDestroy()
    }
}
