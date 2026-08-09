package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.ui.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PlaylistsPage(onBack: () -> Unit) {
    SimplePageScaffold(title = stringResource(R.string.playlist_title), onBack = onBack) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.playlist_empty),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 16.sp
            )
        }
    }
}
