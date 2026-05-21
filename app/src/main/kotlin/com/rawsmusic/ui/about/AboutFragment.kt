package com.rawsmusic.ui.about

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.ui.settings.Divider
import com.rawsmusic.ui.settings.SectionHeader
import com.rawsmusic.ui.settings.themeColors

class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AboutScreen(
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@AboutFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val colors = themeColors()
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("\u2190 \u8fd4\u56de", color = colors.primary, fontSize = 16.sp)
            }
            Text(
                "\u5173\u4e8e",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "RawS Music",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "\u7248\u672c 1.1.0",
            fontSize = 14.sp,
            color = colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Divider()

        // 作者
        SectionHeader("\u4f5c\u8005")
        Text("QFDY", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Text("\u4e2a\u4eba\u5f00\u53d1\u8005", fontSize = 14.sp, color = colors.secondaryText)
        Spacer(Modifier.height(8.dp))
        Text(
            "\u6253\u9020\u7eaf\u7cb9\u7684\u672c\u5730\u97f3\u4e50\u64ad\u653e\u5668\u3002",
            fontSize = 14.sp, color = colors.onSurfaceVariant, lineHeight = 20.sp
        )

        Divider()

        // 开源库
        SectionHeader("\u5f00\u6e90\u5e93")
        Spacer(Modifier.height(8.dp))

        OpenSourceItem("FFmpeg + AudioTrack", "Google", "\u5f3a\u5927\u7684\u5a92\u4f53\u64ad\u653e\u6846\u67b6\uff0c\u652f\u6301\u591a\u79cd\u97f3\u9891\u683c\u5f0f\u548c\u9ad8\u7ea7\u64ad\u653e\u529f\u80fd\u3002", "Apache License 2.0", colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("Coil", "Coil Contributors", "\u9ad8\u6548\u52a0\u8f7d\u548c\u7f13\u5b58\u56fe\u7247\uff0c\u7528\u4e8e\u4e13\u8f91\u5c01\u9762\u663e\u793a\u3002", "BSD, MIT, Apache License 2.0", colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("Material Components", "Google", "Material Design 3 \u7ec4\u4ef6\u5e93\uff0c\u63d0\u4f9b\u73b0\u4ee3\u5316\u7684 UI \u7ec4\u4ef6\u3002", "Apache License 2.0", colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("Kotlin Coroutines", "JetBrains", "Kotlin \u534f\u7a0b\u5e93\uff0c\u7528\u4e8e\u5f02\u6b65\u7f16\u7a0b\u548c\u5e76\u53d1\u5904\u7406\u3002", "Apache License 2.0", colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("AndroidX Navigation", "Google", "Android Jetpack \u5bfc\u822a\u7ec4\u4ef6\uff0c\u7ba1\u7406\u5e94\u7528\u5185\u9875\u9762\u8df3\u8f6c\u3002", "Apache License 2.0", colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("JAudioTagger", "JAudioTagger Team", "\u97f3\u9891\u5143\u6570\u636e\u8bfb\u53d6\u5e93\uff0c\u7528\u4e8e\u89e3\u6790\u6b4c\u66f2\u6807\u7b7e\u4fe1\u606f\u3002", "LGPL v2.1", colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("Backdrop-Compose", "Kyant", "Compose \u6db2\u6001\u73bb\u7483\uff0c\u7528\u4e8e\u5b9e\u73b0\u7cbe\u7f8e\u7684 UI\u3002", "Apache License 2.0", colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("libusb", "libusb Contributors", "USB \u8bbe\u5907\u8bbf\u95ee\u5e93\uff0c\u7528\u4e8e USB DAC \u97f3\u9891\u8f93\u51fa\u3002", "LGPL v2.1", colors)

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun OpenSourceItem(name: String, author: String, desc: String, license: String, colors: com.rawsmusic.ui.settings.ThemeColors) {
    Column {
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Text(author, fontSize = 13.sp, color = colors.secondaryText)
        Text(desc, fontSize = 14.sp, color = colors.onSurfaceVariant, lineHeight = 20.sp)
        Text(license, fontSize = 12.sp, color = colors.secondaryText)
    }
}
