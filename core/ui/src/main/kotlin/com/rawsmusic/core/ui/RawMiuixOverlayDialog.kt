package com.rawsmusic.core.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Keeps the Activity-level scene callback below every visible Miuix overlay. */
object MiuixOverlayBackRuntime {
    var activeCount by mutableIntStateOf(0)
        private set

    internal fun attach() {
        activeCount++
    }

    internal fun detach() {
        activeCount = (activeCount - 1).coerceAtLeast(0)
    }
}

@Composable
fun RawMiuixOverlayDialog(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    backgroundColor: Color = DialogDefaults.backgroundColor(),
    onDismissRequest: (() -> Unit)? = null,
    renderInRootScaffold: Boolean = true,
    content: @Composable () -> Unit
) {
    DisposableEffect(show) {
        if (show) MiuixOverlayBackRuntime.attach()
        onDispose {
            if (show) MiuixOverlayBackRuntime.detach()
        }
    }

    OverlayDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = summary,
        backgroundColor = backgroundColor,
        onDismissRequest = onDismissRequest,
        renderInRootScaffold = renderInRootScaffold,
        content = content
    )
}
