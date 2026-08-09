package com.rawsmusic.ui.settings

import android.os.Bundle

class SettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LiquidGlassSettingsScreen(
                onNavigateToLyrics = { navigateToSettings(LyricSettingsActivity::class.java) },
                onNavigateToAppearance = { navigateToSettings(AppearanceActivity::class.java) },
                onNavigateToPersonalization = { navigateToSettings(PersonalizationSettingsActivity::class.java) },
                onNavigateToAudioSettings = { navigateToSettings(AudioSettingsActivity::class.java) },
                onNavigateToAudioEffects = { navigateToSettings(AudioEffectsActivity::class.java) },
                onNavigateToAiSeparation = { navigateToSettings(AiSeparationActivity::class.java) },
                onNavigateToTransitionSettings = { navigateToSettings(TransitionSettingsActivity::class.java) },
                onNavigateToPlayerInterface = { navigateToSettings(PlayerInterfaceActivity::class.java) },
                onNavigateToUsbDac = { navigateToSettings(UsbDacSettingsActivity::class.java) },
                onNavigateToGlobalFont = { navigateToSettings(GlobalFontSettingsActivity::class.java) },
                onNavigateToAlbumArt = { navigateToSettings(AlbumArtActivity::class.java) },
                onWebDavBackup = { navigateToSettings(WebDavBackupActivity::class.java) },
                onNavigateToLogViewer = { navigateToSettings(LogViewerActivity::class.java) },
                onNavigateToAbout = { navigateToSettings(AboutActivity::class.java) },
                onNavigateToScanSettings = { navigateToSettings(ScanSettingsActivity::class.java) }
            )
        }
    }
}
