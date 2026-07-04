package com.rawsmusic.ui.settings

import android.os.Bundle

class AudioEffectsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassAudioEffectsScreen(
                onNavigateToPEQ = { navigateToSettings(PEQActivity::class.java) },
                onNavigateToGraphicEQ = { navigateToSettings(GraphicEQActivity::class.java) },
                onNavigateToSpatialSound = { navigateToSettings(SpatialSoundActivity::class.java) },
                onNavigateToCompressor = { navigateToSettings(CompressorActivity::class.java) },
                onNavigateToBassTreble = { navigateToSettings(BassTrebleBoostActivity::class.java) },
                onNavigateToSurround360 = { navigateToSettings(Surround360Activity::class.java) },
                onNavigateToPanoramic360 = { navigateToSettings(Panoramic360Activity::class.java) },
                onTogglePEQ = { enabled ->
                    try {
                        playerController?.ensurePEQConnected()
                        playerController?.peqController?.setEnabled(enabled)
                    } catch (_: Exception) {}
                },
                onToggleCompressor = { enabled ->
                    try {
                        playerController?.ensureCompressorConnected()
                        playerController?.compressorController?.setEnabled(enabled)
                    } catch (_: Exception) {}
                },
                onToggleSurround360 = { enabled ->
                    try {
                        playerController?.ensureSurround360Connected()
                        playerController?.surround360Controller?.setEnabled(enabled)
                    } catch (_: Exception) {}
                },
                onTogglePanoramic360 = { enabled ->
                    try {
                        playerController?.ensurePanoramic360Connected()
                        playerController?.panoramic360Controller?.setEnabled(enabled)
                    } catch (_: Exception) {}
                },
                onBack = { finish() }
            )
        }
    }
}
