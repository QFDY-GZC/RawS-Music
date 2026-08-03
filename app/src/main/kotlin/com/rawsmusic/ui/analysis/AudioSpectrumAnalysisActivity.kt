package com.rawsmusic.ui.analysis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.ui.settings.BaseSettingsActivity

class AudioSpectrumAnalysisActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            AudioSpectrumAnalysisScreen(
                song = song,
                onBack = ::finish
            )
        }
    }

    companion object {
        private const val EXTRA_SONG = "audio_spectrum_song"

        fun createIntent(context: Context, song: AudioFile): Intent =
            Intent(context, AudioSpectrumAnalysisActivity::class.java)
                .putExtra(EXTRA_SONG, song)
    }
}
