package com.rawsmusic.ui.analysis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.SideEffect
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.theme.RawSMusicTheme

class AudioSpectrumAnalysisActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val song = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SONG, AudioFile::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_SONG)
        }
        if (song == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        setContent {
            RawSMusicTheme {
                val colors = spectrumUiColors()
                SideEffect {
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.background.toArgb()
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = colors.background.luminance() >= 0.5f
                        isAppearanceLightNavigationBars = colors.background.luminance() >= 0.5f
                    }
                }
                AudioSpectrumAnalysisScreen(song = song, onBack = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_SONG = "audio_spectrum_song"

        fun createIntent(context: Context, song: AudioFile): Intent =
            Intent(context, AudioSpectrumAnalysisActivity::class.java)
                .putExtra(EXTRA_SONG, song)
    }
}
