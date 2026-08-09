package com.rawsmusic.core.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * Visibility gate for the Activity-level scene callback.
 *
 * MIUIX dialogs and dropdown windows already own their own NavigationBackHandler. This runtime does
 * not dispatch or reorder back actions; it only reports whether a MIUIX overlay is visible so the
 * Activity scene callback can stand down and let the library handler receive the gesture.
 */
object MiuixOverlayBackRuntime {
    private val lock = Any()
    private val visibleTokens = LinkedHashSet<Any>()

    var activeCount by mutableIntStateOf(0)
        private set

    internal fun attach(token: Any) {
        synchronized(lock) {
            visibleTokens += token
            activeCount = visibleTokens.size
        }
    }

    internal fun detach(token: Any) {
        synchronized(lock) {
            visibleTokens -= token
            activeCount = visibleTokens.size
        }
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
    val runtimeToken = remember { Any() }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestContent by rememberUpdatedState(content)
    val dispatchDismiss: () -> Unit = { latestDismiss?.invoke() }

    DisposableEffect(show, runtimeToken) {
        if (show) MiuixOverlayBackRuntime.attach(runtimeToken)
        onDispose { MiuixOverlayBackRuntime.detach(runtimeToken) }
    }

    val predictiveProgress = rememberPredictiveDialogProgress(
        enabled = show && onDismissRequest != null,
        onDismissRequest = dispatchDismiss
    )

    OverlayDialog(
        show = show,
        modifier = modifier.predictiveDialogMotion(predictiveProgress),
        title = title,
        summary = summary,
        backgroundColor = backgroundColor,
        onDismissRequest = if (onDismissRequest == null) null else dispatchDismiss,
        renderInRootScaffold = renderInRootScaffold,
        content = { latestContent() }
    )
}
