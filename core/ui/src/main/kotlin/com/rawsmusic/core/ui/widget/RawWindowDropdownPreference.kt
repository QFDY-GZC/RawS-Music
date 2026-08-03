package com.rawsmusic.core.ui.widget

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.popup.WindowDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowCascadingListPopup

/**
 * MIUIX window dropdown row that preserves the library's native back semantics.
 *
 * Flat entries use [WindowDropdownPopup]. Entries containing children use
 * [WindowCascadingListPopup], whose own state machine collapses secondary to primary before
 * dismissing the popup. The local visibility token only suspends the Activity scene callback; it
 * never proxies or replaces MIUIX's dismiss action.
 */
@Composable
fun RawWindowDropdownPreference(
    entry: DropdownEntry,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    collapseOnSelection: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var expanded by remember { mutableStateOf(false) }
    var popupMounted by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val latestExpandedChange by rememberUpdatedState(onExpandedChange)

    val setExpanded: (Boolean) -> Unit = { value ->
        if (expanded != value) {
            expanded = value
            if (value) popupMounted = true
            latestExpandedChange?.invoke(value)
        }
    }
    // MIUIX owns the actual back action and submenu state. While its window is shown, only suspend
    // the Activity scene callback; never proxy dismissal through a second global stack.
    val runtimeToken = remember { Any() }
    DisposableEffect(expanded, runtimeToken) {
        if (expanded) MiuixOverlayBackRuntime.attach(runtimeToken)
        onDispose { MiuixOverlayBackRuntime.detach(runtimeToken) }
    }

    val itemsNotEmpty = entry.items.isNotEmpty()
    val actualEnabled = enabled && entry.enabled && itemsNotEmpty
    val actionColor = if (actualEnabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }
    val hasCascadingItems = remember(entry) {
        entry.items.any { !it.children.isNullOrEmpty() }
    }

    BasicComponent(
        modifier = modifier,
        interactionSource = interactionSource,
        insideMargin = insideMargin,
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = {
            if (showValue && itemsNotEmpty) {
                entry.items.firstOrNull { it.selected }?.text?.takeIf { it.isNotBlank() }?.let { value ->
                    Text(
                        text = value,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .align(Alignment.CenterVertically)
                            .weight(1f, fill = false),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = actionColor,
                        textAlign = TextAlign.End,
                    )
                }
            }
            DropdownArrowEndAction(actionColor = actionColor)

            if (itemsNotEmpty) {
                if (hasCascadingItems) {
                    WindowCascadingListPopup(
                        show = expanded,
                        entries = listOf(entry),
                        onDismissRequest = { setExpanded(false) },
                        onDismissFinished = { popupMounted = false },
                        maxHeight = maxHeight,
                        dropdownColors = dropdownColors,
                        collapseOnSelection = collapseOnSelection,
                    )
                } else {
                    WindowDropdownPopup(
                        entry = entry,
                        show = expanded,
                        onDismiss = { setExpanded(false) },
                        onDismissFinished = { popupMounted = false },
                        maxHeight = maxHeight,
                        dropdownColors = dropdownColors,
                        collapseOnSelection = collapseOnSelection,
                    )
                }
            }
        },
        bottomAction = bottomAction,
        onClick = {
            if (actualEnabled) {
                setExpanded(!expanded)
                if (expanded) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
            }
        },
        role = Role.DropdownList,
        holdDownState = popupMounted,
        enabled = actualEnabled,
    )
}
