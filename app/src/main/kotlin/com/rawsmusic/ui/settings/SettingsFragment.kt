package com.rawsmusic.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.ui.settings.compose.scene.ComposeMainActivity

class SettingsFragment : Fragment() {

    private fun navigateTo(destinationId: Int) {
        val activity = activity as? com.rawsmusic.MainActivity
        if (activity != null) {
            activity.navigateSettingsForward(destinationId)
            return
        }
        try {
            NavHostFragment.findNavController(this).navigate(destinationId)
        } catch (_: Exception) {}
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassSettingsScreen(
                    onNavigateToLyricManagement = {
                        navigateTo(com.rawsmusic.R.id.nav_lyric_management)
                    },
                    onNavigateToStatusBarLyric = {
                        navigateTo(com.rawsmusic.R.id.nav_status_bar_lyric)
                    },
                    onNavigateToAppearance = {
                        navigateTo(com.rawsmusic.R.id.nav_appearance)
                    },
                    onNavigateToAudioSettings = {
                        navigateTo(com.rawsmusic.R.id.nav_audio_settings)
                    },
                    onNavigateToAudioEffects = {
                        navigateTo(com.rawsmusic.R.id.nav_audio_effects)
                    },
                    onNavigateToPlayerInterface = {
                        navigateTo(com.rawsmusic.R.id.nav_player_interface)
                    },
                    onNavigateToUsbDac = {
                        navigateTo(com.rawsmusic.R.id.nav_usb_dac_settings)
                    },
                    onNavigateToGlobalFont = {
                        navigateTo(com.rawsmusic.R.id.nav_global_font_settings)
                    },
                    onNavigateToAlbumArt = {
                        navigateTo(com.rawsmusic.R.id.nav_album_art_settings)
                    },
                    onWebDavBackup = {
                        navigateTo(com.rawsmusic.R.id.nav_webdav_backup)
                    },
                    onNavigateToComposePlayerDemo = {
                        navigateTo(com.rawsmusic.R.id.nav_compose_player_demo)
                    },
                    onNavigateToComposeScene = {
                        startActivity(Intent(requireContext(), ComposeMainActivity::class.java))
                    }
                )
            }
        }
    }
}
