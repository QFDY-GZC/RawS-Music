package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Non-scrolling settings frame used by the audio-effects workspace.
 *
 * Unlike [SettingsPage], this frame leaves scrolling to each pager page so
 * every dimension can retain an independent scroll position.
 */
@Composable
internal fun AudioEffectsWorkspacePage(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val pageBackground = if (isDark) Color(0xFF101014) else Color(0xFFF4F4F7)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        SmallTopAppBar(
            title = title,
            color = pageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Back,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            content()
        }
    }
}
