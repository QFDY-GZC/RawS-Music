package com.rawsmusic.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.ui.settings.Divider
import com.rawsmusic.ui.settings.SectionHeader
import com.rawsmusic.ui.settings.themeColors

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val colors = themeColors()
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
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
                Text("\u2190 ${stringResource(R.string.ui_back)}", color = colors.primary, fontSize = 16.sp)
            }
            Text(
                stringResource(R.string.ui_about_title),
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
            stringResource(R.string.ui_about_version),
            fontSize = 14.sp,
            color = colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Divider()

        // 作者
        SectionHeader(stringResource(R.string.ui_about_author))
        Text("QFDY", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Text(stringResource(R.string.ui_about_developer), fontSize = 14.sp, color = colors.secondaryText)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ui_about_tagline),
            fontSize = 14.sp, color = colors.onSurfaceVariant, lineHeight = 20.sp
        )

        Divider()

        // 开源库
        SectionHeader(stringResource(R.string.ui_about_open_source))
        Spacer(Modifier.height(8.dp))

        OpenSourceItem("FFmpeg + AudioTrack", "Google", stringResource(R.string.ui_about_ffmpeg_desc), stringResource(R.string.ui_about_apache_license), colors)
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("Material Components", "Google", stringResource(R.string.ui_about_material_desc), stringResource(R.string.ui_about_apache_license), colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("Kotlin Coroutines", "JetBrains", stringResource(R.string.ui_about_coroutine_desc), stringResource(R.string.ui_about_apache_license), colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("AndroidX Navigation", "Google", stringResource(R.string.ui_about_navigation_desc), stringResource(R.string.ui_about_apache_license), colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("JAudioTagger", "JAudioTagger Team", stringResource(R.string.ui_about_jaudiotagger_desc), stringResource(R.string.ui_about_lgpl_license), colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("Backdrop-Compose", "Kyant", stringResource(R.string.ui_about_backdrop_desc), stringResource(R.string.ui_about_apache_license), colors)
        Spacer(Modifier.height(16.dp))
        OpenSourceItem("libusb", "libusb Contributors", stringResource(R.string.ui_about_libusb_desc), stringResource(R.string.ui_about_lgpl_license), colors)

        Spacer(Modifier.height(160.dp))
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
