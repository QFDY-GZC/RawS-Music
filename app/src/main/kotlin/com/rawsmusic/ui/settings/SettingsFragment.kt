package com.rawsmusic.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment

class SettingsFragment : Fragment() {

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
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_lyric_management)
                        } catch (_: Exception) {}
                    },
                    onNavigateToStatusBarLyric = {
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_status_bar_lyric)
                        } catch (_: Exception) {}
                    },
                    onNavigateToAppearance = {
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_appearance)
                        } catch (_: Exception) {}
                    },
                    onNavigateToAudioSettings = {
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_audio_settings)
                        } catch (_: Exception) {}
                    },
                    onNavigateToAudioEffects = {
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_audio_effects)
                        } catch (_: Exception) {}
                    },
                    onNavigateToPlayerInterface = {
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_player_interface)
                        } catch (_: Exception) {}
                    },
                    onNavigateToUsbDac = {
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_usb_dac_settings)
                        } catch (_: Exception) {}
                    },
                    onNavigateToGlobalFont = {
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_global_font_settings)
                        } catch (_: Exception) {}
                    },
                    onWebDavBackup = {
                        try {
                            NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_webdav_backup)
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}
