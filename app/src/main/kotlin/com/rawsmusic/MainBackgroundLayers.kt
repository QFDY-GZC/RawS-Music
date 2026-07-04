package com.rawsmusic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainBackgroundLayers() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    )
}
