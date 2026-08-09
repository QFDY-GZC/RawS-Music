package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Stable UI-only representation of the user-selected Lyrico source priority. */
data class MetadataMatchSourceUi(
    val id: String,
    val name: String,
)

@Composable
internal fun LibraryMoreDialog(
    visible: Boolean,
    sourceOrder: List<MetadataMatchSourceUi>,
    onDismiss: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenSort: () -> Unit,
    onMoveSource: (sourceId: String, direction: Int) -> Unit,
    onAutoMatchCurrent: () -> Unit,
    onRematchAll: () -> Unit,
) {
    var showSourceOrder by remember { mutableStateOf(false) }
    var showRematchConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) {
            showSourceOrder = false
            showRematchConfirm = false
        }
    }

    RawMiuixOverlayDialog(
        show = visible && !showSourceOrder && !showRematchConfirm,
        title = stringResource(R.string.library_more_title),
        summary = stringResource(R.string.library_more_summary),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            LibraryActionRow(
                title = stringResource(R.string.library_select_folder),
                summary = stringResource(R.string.library_select_folder_summary),
                imageVector = MiuixIcons.Regular.Folder,
                onClick = {
                    onDismiss()
                    onChooseFolder()
                },
            )
            LibraryActionRow(
                title = stringResource(R.string.library_sort_layout),
                summary = stringResource(R.string.library_sort_layout_summary),
                imageVector = MiuixIcons.Regular.Sort,
                onClick = {
                    onDismiss()
                    onOpenSort()
                },
            )
            LibraryActionRow(
                title = stringResource(R.string.library_source_order),
                summary = if (sourceOrder.isEmpty()) stringResource(R.string.library_sources_not_imported)
                else stringResource(R.string.library_sources_fallback_summary),
                imageVector = MiuixIcons.Regular.Music,
                enabled = sourceOrder.isNotEmpty(),
                onClick = { showSourceOrder = true },
            )
            LibraryResourceActionRow(
                title = stringResource(R.string.library_match_current),
                summary = stringResource(R.string.library_match_current_summary),
                iconRes = R.drawable.ic_music_note,
                onClick = {
                    onDismiss()
                    onAutoMatchCurrent()
                },
            )
            LibraryResourceActionRow(
                title = stringResource(R.string.library_rematch_all),
                summary = stringResource(R.string.library_rematch_all_summary),
                iconRes = R.drawable.ic_shuffle_custom,
                danger = true,
                onClick = { showRematchConfirm = true },
            )
        }
    }

    RawMiuixOverlayDialog(
        show = visible && showSourceOrder,
        title = stringResource(R.string.library_source_order),
        summary = stringResource(R.string.library_source_order_summary),
        onDismissRequest = { showSourceOrder = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (sourceOrder.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_no_enabled_sources),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(18.dp),
                )
            } else {
                sourceOrder.forEachIndexed { index, source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Music,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = "${index + 1}. ${source.name}",
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onMoveSource(source.id, -1) },
                            enabled = index > 0,
                        ) {
                            Icon(MiuixIcons.Regular.ExpandLess, stringResource(R.string.library_move_up))
                        }
                        IconButton(
                            onClick = { onMoveSource(source.id, 1) },
                            enabled = index < sourceOrder.lastIndex,
                        ) {
                            Icon(MiuixIcons.Regular.ExpandMore, stringResource(R.string.library_move_down))
                        }
                    }
                }
            }
        }
    }

    RawMiuixOverlayDialog(
        show = visible && showRematchConfirm,
        title = stringResource(R.string.library_rematch_all_confirm_title),
        summary = stringResource(R.string.library_rematch_all_confirm_summary),
        onDismissRequest = { showRematchConfirm = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showRematchConfirm = false },
            )
            DialogTextButton(
                text = stringResource(R.string.library_start_matching),
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    showRematchConfirm = false
                    onDismiss()
                    onRematchAll()
                },
            )
        }
    }
}

@Composable
private fun LibraryActionRow(
    title: String,
    summary: String,
    imageVector: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary.copy(alpha = alpha),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MiuixTheme.colorScheme.onSurface.copy(alpha = alpha), fontSize = 16.sp)
            Text(
                summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun LibraryResourceActionRow(
    title: String,
    summary: String,
    iconRes: Int,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (danger) androidx.compose.material3.MaterialTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp)
            Text(summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DialogTextButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
    Text(
        text = text,
        color = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
    )
}
