package com.rawsmusic.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment

class AudioSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassAudioSettingsScreen(
                    onNavigateToSpatialSound = {
                        try {
                            NavHostFragment.findNavController(this@AudioSettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_spatial_sound)
                        } catch (_: Exception) {}
                    },
                    onNavigateToUsbDac = {
                        try {
                            NavHostFragment.findNavController(this@AudioSettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_usb_dac_settings)
                        } catch (_: Exception) {}
                    },
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@AudioSettingsFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}
