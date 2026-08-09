package com.rawsmusic.ui.settings

import android.net.Uri
import android.os.Bundle
import java.io.File

class AiSeparationActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
        val sourceUri = sourcePath.takeIf { it.isNotBlank() }?.let { path ->
            if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
        }
        val sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME).orEmpty()
        setContent {
            AiSeparationSettingsScreen(
                onBack = { finish() },
                initialAudioUri = sourceUri,
                initialAudioName = sourceName,
            )
        }
    }

    companion object {
        const val EXTRA_SOURCE_PATH = "ai_separation_source_path"
        const val EXTRA_SOURCE_NAME = "ai_separation_source_name"
    }
}
