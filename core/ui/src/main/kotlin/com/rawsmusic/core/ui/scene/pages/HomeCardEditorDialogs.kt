package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomeCardAddDialog(
    show: Boolean,
    group: HomeCardGroup?,
    state: HomeCardLayoutState,
    contentCounts: Map<NavScene, Int>,
    onDismissRequest: () -> Unit,
) {
    if (!show) return
    var sort by remember(show, group) { mutableStateOf(HomeCardChooserSort.DEFAULT) }
    val hidden = remember(state.hiddenScenes, state.libraryOrder, state.toolOrder, group, sort, contentCounts) {
        val base = when (group) {
            HomeCardGroup.LIBRARY -> state.hidden(HomeCardGroup.LIBRARY)
            HomeCardGroup.TOOLS -> state.hidden(HomeCardGroup.TOOLS)
            null -> state.hidden(HomeCardGroup.LIBRARY) + state.hidden(HomeCardGroup.TOOLS)
        }
        when (sort) {
            HomeCardChooserSort.DEFAULT -> base
            HomeCardChooserSort.NAME -> base.sortedBy { it.label.lowercase() }
            HomeCardChooserSort.CONTENT_COUNT -> base.sortedWith(
                compareByDescending<NavScene> { contentCounts[it] ?: -1 }
                    .thenBy { it.label.lowercase() }
            )
        }
    }
    val sortEntry = DropdownEntry(
        items = HomeCardChooserSort.entries.map { option ->
            DropdownItem(
                text = stringResource(option.titleRes()),
                selected = sort == option,
                onClick = { sort = option },
            )
        },
    )
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.home_card_add_title),
        summary = stringResource(R.string.home_card_add_summary),
        onDismissRequest = onDismissRequest,
        renderInRootScaffold = true,
    ) {
        RawWindowDropdownPreference(
            entry = sortEntry,
            title = stringResource(R.string.home_card_sort_title),
            summary = null,
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            showValue = true,
            maxHeight = 220.dp,
            collapseOnSelection = true,
        )
        Spacer(Modifier.height(10.dp))
        if (hidden.isEmpty()) {
            Text(
                text = stringResource(R.string.home_card_add_empty),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 16.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                hidden.forEach { scene ->
                    HomeCardDialogRow(
                        scene = scene,
                        count = contentCounts[scene],
                        trailingText = stringResource(R.string.home_card_add_action),
                        onClick = {
                            state.show(scene)
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

/** Reused by the real Settings activity; changes propagate to HOME through SharedPreferences. */
@Composable
fun HomeCardSettingsDialog(
    show: Boolean,
    state: HomeCardLayoutState,
    onDismissRequest: () -> Unit,
    renderInRootScaffold: Boolean = true,
) {
    if (!show) return
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.home_card_settings_title),
        summary = stringResource(R.string.home_card_settings_summary),
        onDismissRequest = onDismissRequest,
        renderInRootScaffold = renderInRootScaffold,
    ) {
        HomeCardSettingsGroup(
            title = stringResource(R.string.home_library_section),
            group = HomeCardGroup.LIBRARY,
            state = state,
        )
        Spacer(Modifier.height(12.dp))
        HomeCardSettingsGroup(
            title = stringResource(R.string.home_tools_section),
            group = HomeCardGroup.TOOLS,
            state = state,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f))
                .clickable { state.reset() }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_card_reset),
                color = MiuixTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HomeCardSettingsGroup(
    title: String,
    group: HomeCardGroup,
    state: HomeCardLayoutState,
) {
    var scenes by remember(group) { mutableStateOf(state.scenes(group, visibleOnly = false)) }
    val externalOrder = when (group) {
        HomeCardGroup.LIBRARY -> state.libraryOrder
        HomeCardGroup.TOOLS -> state.toolOrder
    }
    LaunchedEffect(externalOrder, state.hiddenScenes, group) {
        scenes = state.scenes(group, visibleOnly = false)
    }

    fun moveVisible(scene: NavScene, delta: Int) {
        val visible = scenes.filter { state.isVisible(it) }.toMutableList()
        val from = visible.indexOf(scene)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, visible.lastIndex)
        if (to == from) return
        visible.removeAt(from)
        visible.add(to, scene)
        val hidden = scenes.filterNot { state.isVisible(it) }
        scenes = visible + hidden
        state.setVisibleOrder(group, visible)
    }
    Text(
        text = title,
        color = MiuixTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (group == HomeCardGroup.LIBRARY) 330.dp else 210.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        scenes.forEach { scene ->
            val visible = state.isVisible(scene)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = scene.label,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (visible) {
                        Text(
                            text = stringResource(R.string.home_card_drag_hint_short),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
                if (visible) {
                    Text(
                        text = "↑",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { moveVisible(scene, -1) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                    Text(
                        text = "↓",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { moveVisible(scene, 1) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
                Switch(
                    checked = visible,
                    onCheckedChange = { state.setVisible(scene, it) },
                )
            }
        }
    }
}

@Composable
private fun HomeCardDialogRow(
    scene: NavScene,
    count: Int?,
    trailingText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = scene.label,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(
                    text = stringResource(R.string.home_card_content_count, count),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = trailingText,
            color = MiuixTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun HomeCardChooserSort.titleRes(): Int = when (this) {
    HomeCardChooserSort.DEFAULT -> R.string.home_card_sort_default
    HomeCardChooserSort.NAME -> R.string.home_card_sort_name
    HomeCardChooserSort.CONTENT_COUNT -> R.string.home_card_sort_count
}
