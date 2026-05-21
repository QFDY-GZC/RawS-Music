package com.rawsmusic.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.module.data.prefs.AppPreferences

class AudioEffectsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassAudioEffectsScreen(
                    onNavigateToPEQ = {
                        try {
                            NavHostFragment.findNavController(this@AudioEffectsFragment)
                                .navigate(com.rawsmusic.R.id.nav_peq)
                        } catch (_: Exception) {}
                    },
                    onNavigateToSpatialSound = {
                        try {
                            NavHostFragment.findNavController(this@AudioEffectsFragment)
                                .navigate(com.rawsmusic.R.id.nav_spatial_sound)
                        } catch (_: Exception) {}
                    },
                    onTogglePEQ = { enabled ->
                        try {
                            val activity = requireActivity() as? com.rawsmusic.MainActivity
                            val playerController = activity?.playerController
                            playerController?.ensurePEQConnected()
                            playerController?.peqController?.setEnabled(enabled)
                        } catch (e: Exception) {
                            android.util.Log.e("AudioEffects", "Failed to toggle PEQ", e)
                        }
                    },
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@AudioEffectsFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

@Composable
fun LiquidGlassAudioEffectsScreen(
    onNavigateToPEQ: () -> Unit,
    onNavigateToSpatialSound: () -> Unit,
    onTogglePEQ: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val colors = themeColors()
    var peqEnabled by remember { mutableStateOf(AppPreferences.PEQ.isEnabled) }

    SettingsPage(title = "音效设置", onBack = onBack) {
        SectionHeader("参量均衡器")
        SwitchRow("启用参量均衡器", peqEnabled) { checked ->
            peqEnabled = checked
            onTogglePEQ(checked)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNavigateToPEQ) {
            Text("进入参量均衡器 →", color = colors.primary, fontSize = 14.sp)
        }

        Divider()

        SectionHeader("立体声扩展")
        Text(
            "虚拟器、互馈 (Crossfeed) 设置",
            fontSize = 13.sp,
            color = colors.secondaryText,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNavigateToSpatialSound) {
            Text("进入立体声扩展设置 →", color = colors.primary, fontSize = 14.sp)
        }
    }
}
