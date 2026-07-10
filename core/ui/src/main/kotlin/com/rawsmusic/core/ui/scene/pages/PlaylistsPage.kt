package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PlaylistsPage(onBack: () -> Unit) {
    SimplePageScaffold(title = "歌单", onBack = onBack) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "歌单列表\n(待集成)",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 16.sp
            )
        }
    }
}
