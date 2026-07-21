package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.ui.scene.BottomNavigationEntryIcon
import com.rawsmusic.core.ui.scene.MAX_BOTTOM_NAVIGATION_ITEMS
import com.rawsmusic.core.ui.scene.MIN_BOTTOM_NAVIGATION_ITEMS
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.bottomNavigationLabel
import com.rawsmusic.core.ui.scene.customizableBottomNavigationScenes
import com.rawsmusic.core.ui.scene.resolveBottomNavigationScenes
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BottomNavigationSettingsScreen(onBack: () -> Unit) {
    val bottomNavigationEnabled by PersonalizationPreferences.bottomNavigationEnabled.collectAsState()
    val savedTags by PersonalizationPreferences.bottomNavigationSceneTags.collectAsState()
    val selectedScenes = resolveBottomNavigationScenes(savedTags)

    fun persist(scenes: List<NavScene>) {
        PersonalizationPreferences.bottomNavigationScenes = scenes.map { it.tag }
    }

    SettingsPage(
        title = stringResource(R.string.settings_bottom_navigation_title),
        onBack = onBack,
    ) {
        SmallTitle(text = stringResource(R.string.settings_bottom_navigation_visibility_section))
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_bottom_navigation_enabled_title),
                summary = stringResource(R.string.settings_bottom_navigation_enabled_summary),
                checked = bottomNavigationEnabled,
                onCheckedChange = { enabled ->
                    PersonalizationPreferences.isBottomNavigationEnabled = enabled
                },
            )
        }

        SmallTitle(text = stringResource(R.string.settings_bottom_navigation_preview_section))
        SettingsCard {
            BottomNavigationPreview(selectedScenes)
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.settings_bottom_navigation_count,
                    selectedScenes.size,
                    MAX_BOTTOM_NAVIGATION_ITEMS,
                ),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        SmallTitle(text = stringResource(R.string.settings_bottom_navigation_selected_section))
        SettingsCard {
            selectedScenes.forEachIndexed { index, scene ->
                SelectedNavigationEntryRow(
                    scene = scene,
                    index = index,
                    total = selectedScenes.size,
                    canRemove = scene != NavScene.HOME && selectedScenes.size > MIN_BOTTOM_NAVIGATION_ITEMS,
                    onMoveUp = {
                        if (index > 0) {
                            val updated = selectedScenes.toMutableList()
                            val item = updated.removeAt(index)
                            updated.add(index - 1, item)
                            persist(updated)
                        }
                    },
                    onMoveDown = {
                        if (index < selectedScenes.lastIndex) {
                            val updated = selectedScenes.toMutableList()
                            val item = updated.removeAt(index)
                            updated.add(index + 1, item)
                            persist(updated)
                        }
                    },
                    onRemove = {
                        if (scene != NavScene.HOME && selectedScenes.size > MIN_BOTTOM_NAVIGATION_ITEMS) {
                            persist(selectedScenes.filterNot { it == scene })
                        }
                    },
                )
            }
        }

        SmallTitle(text = stringResource(R.string.settings_bottom_navigation_entries_section))
        SettingsCard {
            Text(
                text = stringResource(R.string.settings_bottom_navigation_entries_summary),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            customizableBottomNavigationScenes.chunked(2).forEach { rowScenes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowScenes.forEach { scene ->
                        val selected = scene in selectedScenes
                        val canToggle = when {
                            scene == NavScene.HOME -> false
                            selected -> selectedScenes.size > MIN_BOTTOM_NAVIGATION_ITEMS
                            else -> selectedScenes.size < MAX_BOTTOM_NAVIGATION_ITEMS
                        }
                        NavigationChoiceTile(
                            scene = scene,
                            selected = selected,
                            enabled = canToggle,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (selected) {
                                    persist(selectedScenes.filterNot { it == scene })
                                } else {
                                    persist(selectedScenes + scene)
                                }
                            },
                        )
                    }
                    if (rowScenes.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (!bottomNavigationEnabled || NavScene.SETTINGS !in selectedScenes) {
            SettingsCard {
                Text(
                    text = stringResource(
                        if (bottomNavigationEnabled) {
                            R.string.settings_bottom_navigation_settings_fallback_title
                        } else {
                            R.string.settings_bottom_navigation_disabled_fallback_title
                        }
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(
                        if (bottomNavigationEnabled) {
                            R.string.settings_bottom_navigation_settings_fallback_summary
                        } else {
                            R.string.settings_bottom_navigation_disabled_fallback_summary
                        }
                    ),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .clickable {
                    PersonalizationPreferences.isBottomNavigationEnabled = true
                    PersonalizationPreferences.resetBottomNavigationScenes()
                }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.settings_bottom_navigation_reset),
                color = MiuixTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BottomNavigationPreview(scenes: List<NavScene>) {
    val contentColor = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scenes.forEachIndexed { index, scene ->
            val selected = index == 0
            val tint = if (selected) MiuixTheme.colorScheme.primary else contentColor
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.13f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BottomNavigationEntryIcon(
                        scene = scene,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = scene.bottomNavigationLabel(),
                    color = tint,
                    fontSize = 9.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SelectedNavigationEntryRow(
    scene: NavScene,
    index: Int,
    total: Int,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomNavigationEntryIcon(
            scene = scene,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = scene.bottomNavigationLabel(),
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = if (scene == NavScene.HOME) {
                    stringResource(R.string.settings_bottom_navigation_home_required)
                } else {
                    stringResource(R.string.settings_bottom_navigation_position, index + 1)
                },
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        CompactActionButton(
            text = "↑",
            enabled = index > 0,
            onClick = onMoveUp,
        )
        CompactActionButton(
            text = "↓",
            enabled = index < total - 1,
            onClick = onMoveDown,
        )
        CompactActionButton(
            text = stringResource(R.string.settings_bottom_navigation_remove),
            enabled = canRemove,
            onClick = onRemove,
        )
    }
}

@Composable
private fun NavigationChoiceTile(
    scene: NavScene,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = when {
        selected -> MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val contentColor = when {
        !enabled && !selected -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        selected -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomNavigationEntryIcon(
            scene = scene,
            tint = contentColor,
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = scene.bottomNavigationLabel(),
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
