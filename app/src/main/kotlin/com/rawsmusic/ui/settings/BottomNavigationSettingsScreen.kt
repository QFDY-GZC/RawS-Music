package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import com.rawsmusic.core.ui.widget.MiniPlayerArtworkMode
import com.rawsmusic.core.ui.widget.MiniPlayerKaraokeEffectState
import com.rawsmusic.core.ui.widget.effectiveMiniPlayerArtworkSizeDp
import com.rawsmusic.core.ui.widget.miniPlayerArtworkSizeBounds
import com.rawsmusic.core.ui.widget.originalMiniPlayerArtworkVisualSizeDp
import com.rawsmusic.core.ui.widget.rememberMiniPlayerArtworkMode
import com.rawsmusic.core.ui.widget.writeStoredMiniPlayerArtworkMode
import com.rawsmusic.core.ui.widget.bottombar.miniPlayerSurfaceShape
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.BottomChromeScrollBehavior
import com.rawsmusic.module.data.prefs.BottomNavigationStyleSettings
import com.rawsmusic.module.data.prefs.MiniPlayerBubbleMotion
import com.rawsmusic.module.data.prefs.MiniPlayerBubbleOrigin
import com.rawsmusic.module.data.prefs.MiniPlayerProgressDirection
import com.rawsmusic.module.data.prefs.MiniPlayerPlayPausePosition
import com.rawsmusic.module.data.prefs.MiniPlayerSecondaryAction
import com.rawsmusic.module.data.prefs.MiniPlayerStyleSettings
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BottomNavigationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val bottomNavigationEnabled by PersonalizationPreferences.bottomNavigationEnabled.collectAsState()
    val bottomChromeScrollBehavior by PersonalizationPreferences.bottomChromeScrollBehavior.collectAsState()
    val bottomNavigationStyle by PersonalizationPreferences.bottomNavigationStyle.collectAsState()
    val progressEffects by PersonalizationPreferences.miniPlayerProgressEffects.collectAsState()
    val miniPlayerStyle by PersonalizationPreferences.miniPlayerStyle.collectAsState()
    val miniPlayerControls by PersonalizationPreferences.miniPlayerControls.collectAsState()
    val miniPlayerKaraokeEffectEnabled = MiniPlayerKaraokeEffectState.enabled
    val miniPlayerArtworkModeState = rememberMiniPlayerArtworkMode()
    val miniPlayerArtworkMode = miniPlayerArtworkModeState.value
    val artworkSizeBounds = miniPlayerArtworkSizeBounds(
        expandedHeightDp = miniPlayerStyle.expandedHeightDp,
        compactHeightDp = miniPlayerStyle.compactHeightDp,
        mode = miniPlayerArtworkMode,
    )
    val effectiveArtworkSizeDp = artworkSizeBounds.constrain(miniPlayerStyle.artworkSizeDp)
    val savedTags by PersonalizationPreferences.bottomNavigationSceneTags.collectAsState()

    LaunchedEffect(
        miniPlayerArtworkMode,
        miniPlayerStyle.expandedHeightDp,
        miniPlayerStyle.compactHeightDp,
    ) {
        val originalRadiusMax = originalMiniPlayerArtworkVisualSizeDp(effectiveArtworkSizeDp) / 2f
        val effectiveOriginalRadius = miniPlayerStyle.originalArtworkCornerRadiusDp
            .coerceIn(0f, originalRadiusMax)
        if (
            miniPlayerStyle.artworkSizeDp != effectiveArtworkSizeDp ||
            miniPlayerStyle.originalArtworkCornerRadiusDp != effectiveOriginalRadius
        ) {
            PersonalizationPreferences.updateMiniPlayerStyle { current ->
                current.copy(
                    artworkSizeDp = effectiveArtworkSizeDp,
                    originalArtworkCornerRadiusDp = effectiveOriginalRadius,
                )
            }
        }
    }
    val selectedScenes = resolveBottomNavigationScenes(savedTags)

    fun persist(scenes: List<NavScene>) {
        PersonalizationPreferences.bottomNavigationScenes = scenes.map { it.tag }
    }

    fun updateMiniPlayerStyleConstrained(
        mode: MiniPlayerArtworkMode = miniPlayerArtworkMode,
        transform: (MiniPlayerStyleSettings) -> MiniPlayerStyleSettings,
    ) {
        PersonalizationPreferences.updateMiniPlayerStyle { current ->
            val candidate = transform(current).normalized()
            val bounds = miniPlayerArtworkSizeBounds(
                expandedHeightDp = candidate.expandedHeightDp,
                compactHeightDp = candidate.compactHeightDp,
                mode = mode,
            )
            val constrainedArtworkSize = bounds.constrain(candidate.artworkSizeDp)
            val originalVisualSize = originalMiniPlayerArtworkVisualSizeDp(constrainedArtworkSize)
            candidate.copy(
                artworkSizeDp = constrainedArtworkSize,
                originalArtworkCornerRadiusDp = candidate.originalArtworkCornerRadiusDp
                    .coerceIn(0f, originalVisualSize / 2f),
            )
        }
    }

    fun selectMiniPlayerArtworkMode(mode: MiniPlayerArtworkMode) {
        if (miniPlayerArtworkModeState.value == mode) return
        miniPlayerArtworkModeState.value = mode
        writeStoredMiniPlayerArtworkMode(context, mode)
        updateMiniPlayerStyleConstrained(mode = mode) { it }
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

        SmallTitle(text = stringResource(R.string.settings_bottom_navigation_style_section))
        SettingsCard {
            BottomNavigationPreview(selectedScenes, bottomNavigationStyle, miniPlayerStyle)
            PreferenceValueSlider(
                title = stringResource(R.string.settings_bottom_navigation_height_title),
                value = bottomNavigationStyle.heightDp,
                valueRange = 44f..96f,
                valueText = "${bottomNavigationStyle.heightDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateBottomNavigationStyle { it.copy(heightDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_bottom_navigation_bottom_lift_title),
                value = bottomNavigationStyle.bottomLiftDp,
                valueRange = 0f..64f,
                valueText = "${bottomNavigationStyle.bottomLiftDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateBottomNavigationStyle { it.copy(bottomLiftDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_bottom_navigation_radius_title),
                value = bottomNavigationStyle.cornerRadiusDp,
                valueRange = 0f..(bottomNavigationStyle.heightDp / 2f),
                valueText = "${bottomNavigationStyle.cornerRadiusDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateBottomNavigationStyle { it.copy(cornerRadiusDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_bottom_navigation_leading_margin_title),
                value = bottomNavigationStyle.leadingMarginDp,
                valueRange = 0f..64f,
                valueText = "${bottomNavigationStyle.leadingMarginDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateBottomNavigationStyle { it.copy(leadingMarginDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_bottom_navigation_trailing_margin_title),
                value = bottomNavigationStyle.trailingMarginDp,
                valueRange = 0f..64f,
                valueText = "${bottomNavigationStyle.trailingMarginDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateBottomNavigationStyle { it.copy(trailingMarginDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_bottom_navigation_curve_title),
                value = bottomNavigationStyle.cornerCurve,
                valueRange = 1.2f..8f,
                valueText = String.format("%.1f", bottomNavigationStyle.cornerCurve),
                onValueChange = { value ->
                    PersonalizationPreferences.updateBottomNavigationStyle { it.copy(cornerCurve = value) }
                },
            )
            Text(
                text = stringResource(R.string.settings_bottom_navigation_curve_summary),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
            Text(
                text = stringResource(R.string.settings_bottom_navigation_inline_height_summary),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { PersonalizationPreferences.resetBottomNavigationStyle() }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_bottom_navigation_style_reset),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        SmallTitle(text = stringResource(R.string.settings_mini_player_style_section))
        SettingsCard {
            MiniPlayerStylePreview(miniPlayerStyle, miniPlayerArtworkMode)
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_height_title),
                value = miniPlayerStyle.expandedHeightDp,
                valueRange = maxOf(36f, miniPlayerStyle.compactHeightDp)..104f,
                valueText = "${miniPlayerStyle.expandedHeightDp.toInt()}dp",
                onValueChange = { value ->
                    updateMiniPlayerStyleConstrained { it.copy(expandedHeightDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_compact_height_title),
                value = miniPlayerStyle.compactHeightDp,
                valueRange = 34f..minOf(88f, miniPlayerStyle.expandedHeightDp),
                valueText = "${miniPlayerStyle.compactHeightDp.toInt()}dp",
                onValueChange = { value ->
                    updateMiniPlayerStyleConstrained { it.copy(compactHeightDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_radius_title),
                value = miniPlayerStyle.cornerRadiusDp,
                valueRange = 0f..52f,
                valueText = "${miniPlayerStyle.cornerRadiusDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateMiniPlayerStyle { it.copy(cornerRadiusDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_leading_margin_title),
                value = miniPlayerStyle.leadingMarginDp,
                valueRange = 0f..64f,
                valueText = "${miniPlayerStyle.leadingMarginDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateMiniPlayerStyle { it.copy(leadingMarginDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_trailing_margin_title),
                value = miniPlayerStyle.trailingMarginDp,
                valueRange = 0f..64f,
                valueText = "${miniPlayerStyle.trailingMarginDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateMiniPlayerStyle { it.copy(trailingMarginDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_curve_title),
                value = miniPlayerStyle.cornerCurve,
                valueRange = 1.2f..8f,
                valueText = String.format("%.1f", miniPlayerStyle.cornerCurve),
                onValueChange = { value ->
                    PersonalizationPreferences.updateMiniPlayerStyle { it.copy(cornerCurve = value) }
                },
            )
            Text(
                text = stringResource(R.string.settings_mini_player_curve_summary),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_artwork_gap_title),
                value = miniPlayerStyle.artworkTextGapDp,
                valueRange = 0f..24f,
                valueText = "${miniPlayerStyle.artworkTextGapDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateMiniPlayerStyle { it.copy(artworkTextGapDp = value) }
                },
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_control_gap_title),
                value = miniPlayerStyle.controlGapDp,
                valueRange = 0f..20f,
                valueText = "${miniPlayerStyle.controlGapDp.toInt()}dp",
                onValueChange = { value ->
                    PersonalizationPreferences.updateMiniPlayerStyle { it.copy(controlGapDp = value) }
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        miniPlayerArtworkModeState.value = MiniPlayerArtworkMode.Vinyl
                        writeStoredMiniPlayerArtworkMode(context, MiniPlayerArtworkMode.Vinyl)
                        PersonalizationPreferences.resetMiniPlayerStyle()
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_mini_player_style_reset),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SettingsCard {
            val normalLabel = stringResource(R.string.settings_mini_player_artwork_mode_circle)
            val vinylLabel = stringResource(R.string.settings_mini_player_artwork_mode_vinyl)
            val originalLabel = stringResource(R.string.settings_mini_player_artwork_mode_original)
            val currentModeLabel = when (miniPlayerArtworkMode) {
                MiniPlayerArtworkMode.Normal -> normalLabel
                MiniPlayerArtworkMode.Vinyl -> vinylLabel
                MiniPlayerArtworkMode.Original -> originalLabel
            }
            RawWindowDropdownPreference(
                entry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = normalLabel,
                            selected = miniPlayerArtworkMode == MiniPlayerArtworkMode.Normal,
                            onClick = { selectMiniPlayerArtworkMode(MiniPlayerArtworkMode.Normal) },
                        ),
                        DropdownItem(
                            text = vinylLabel,
                            selected = miniPlayerArtworkMode == MiniPlayerArtworkMode.Vinyl,
                            onClick = { selectMiniPlayerArtworkMode(MiniPlayerArtworkMode.Vinyl) },
                        ),
                        DropdownItem(
                            text = originalLabel,
                            selected = miniPlayerArtworkMode == MiniPlayerArtworkMode.Original,
                            onClick = { selectMiniPlayerArtworkMode(MiniPlayerArtworkMode.Original) },
                        ),
                    ),
                ),
                title = stringResource(R.string.settings_mini_player_artwork_display_title),
                summary = currentModeLabel,
                showValue = true,
                maxHeight = 300.dp,
                collapseOnSelection = true,
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_player_artwork_size_title),
                value = effectiveArtworkSizeDp,
                valueRange = artworkSizeBounds.minDp..artworkSizeBounds.maxDp,
                valueText = "${effectiveArtworkSizeDp.toInt()}dp",
                onValueChange = { value ->
                    updateMiniPlayerStyleConstrained { it.copy(artworkSizeDp = value) }
                },
            )
            Text(
                text = stringResource(R.string.settings_mini_player_artwork_size_dynamic_summary),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
            if (miniPlayerArtworkMode == MiniPlayerArtworkMode.Original) {
                val originalVisualSizeDp = originalMiniPlayerArtworkVisualSizeDp(effectiveArtworkSizeDp)
                val originalRadiusMax = originalVisualSizeDp / 2f
                val originalRadius = miniPlayerStyle.originalArtworkCornerRadiusDp.coerceIn(0f, originalRadiusMax)
                PreferenceValueSlider(
                    title = stringResource(R.string.settings_mini_player_original_artwork_radius_title),
                    value = originalRadius,
                    valueRange = 0f..originalRadiusMax,
                    valueText = "${originalRadius.toInt()}dp",
                    onValueChange = { value ->
                        PersonalizationPreferences.updateMiniPlayerStyle {
                            it.copy(originalArtworkCornerRadiusDp = value)
                        }
                    },
                )
            }
        }

        SmallTitle(text = stringResource(R.string.settings_mini_controls_section))
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_mini_player_karaoke_effect),
                summary = stringResource(R.string.settings_mini_player_karaoke_effect_desc),
                checked = miniPlayerKaraokeEffectEnabled,
                onCheckedChange = MiniPlayerKaraokeEffectState::updateEnabled,
            )
            SwitchPreference(
                title = stringResource(R.string.settings_mini_previous_title),
                summary = stringResource(R.string.settings_mini_previous_summary),
                checked = miniPlayerControls.showPrevious,
                onCheckedChange = { checked ->
                    PersonalizationPreferences.updateMiniPlayerControls {
                        it.copy(showPrevious = checked)
                    }
                },
            )

            val trailingLabel = stringResource(R.string.settings_mini_play_position_trailing)
            val artworkLeftLabel = stringResource(R.string.settings_mini_play_position_artwork_left)
            val artworkRightLabel = stringResource(R.string.settings_mini_play_position_artwork_right)
            val playPositionLabel = when (miniPlayerControls.playPausePosition) {
                MiniPlayerPlayPausePosition.TRAILING -> trailingLabel
                MiniPlayerPlayPausePosition.ARTWORK_LEFT -> artworkLeftLabel
                MiniPlayerPlayPausePosition.ARTWORK_RIGHT -> artworkRightLabel
            }
            RawWindowDropdownPreference(
                entry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = trailingLabel,
                            selected = miniPlayerControls.playPausePosition == MiniPlayerPlayPausePosition.TRAILING,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerControls {
                                    it.copy(playPausePosition = MiniPlayerPlayPausePosition.TRAILING)
                                }
                            },
                        ),
                        DropdownItem(
                            text = artworkLeftLabel,
                            selected = miniPlayerControls.playPausePosition == MiniPlayerPlayPausePosition.ARTWORK_LEFT,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerControls {
                                    it.copy(playPausePosition = MiniPlayerPlayPausePosition.ARTWORK_LEFT)
                                }
                            },
                        ),
                        DropdownItem(
                            text = artworkRightLabel,
                            selected = miniPlayerControls.playPausePosition == MiniPlayerPlayPausePosition.ARTWORK_RIGHT,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerControls {
                                    it.copy(playPausePosition = MiniPlayerPlayPausePosition.ARTWORK_RIGHT)
                                }
                            },
                        ),
                    ),
                ),
                title = stringResource(R.string.settings_mini_play_position_title),
                summary = playPositionLabel,
                showValue = true,
                maxHeight = 300.dp,
                collapseOnSelection = true,
            )

            val secondaryDefaultLabel = stringResource(R.string.settings_mini_secondary_default)
            val secondaryQueueLabel = stringResource(R.string.settings_mini_secondary_queue)
            val secondaryHiddenLabel = stringResource(R.string.settings_mini_secondary_hidden)
            val secondaryLabel = when (miniPlayerControls.secondaryAction) {
                MiniPlayerSecondaryAction.DEFAULT -> secondaryDefaultLabel
                MiniPlayerSecondaryAction.QUEUE -> secondaryQueueLabel
                MiniPlayerSecondaryAction.HIDDEN -> secondaryHiddenLabel
            }
            RawWindowDropdownPreference(
                entry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = secondaryDefaultLabel,
                            selected = miniPlayerControls.secondaryAction == MiniPlayerSecondaryAction.DEFAULT,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerControls {
                                    it.copy(secondaryAction = MiniPlayerSecondaryAction.DEFAULT)
                                }
                            },
                        ),
                        DropdownItem(
                            text = secondaryQueueLabel,
                            selected = miniPlayerControls.secondaryAction == MiniPlayerSecondaryAction.QUEUE,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerControls {
                                    it.copy(secondaryAction = MiniPlayerSecondaryAction.QUEUE)
                                }
                            },
                        ),
                        DropdownItem(
                            text = secondaryHiddenLabel,
                            selected = miniPlayerControls.secondaryAction == MiniPlayerSecondaryAction.HIDDEN,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerControls {
                                    it.copy(secondaryAction = MiniPlayerSecondaryAction.HIDDEN)
                                }
                            },
                        ),
                    ),
                ),
                title = stringResource(R.string.settings_mini_secondary_action_title),
                summary = secondaryLabel,
                showValue = true,
                maxHeight = 300.dp,
                collapseOnSelection = true,
            )
        }

        SmallTitle(text = stringResource(R.string.settings_bottom_navigation_scroll_behavior_section))
        SettingsCard {
            BottomChromeScrollBehaviorChoice(
                behavior = BottomChromeScrollBehavior.DEFAULT,
                selected = bottomChromeScrollBehavior == BottomChromeScrollBehavior.DEFAULT,
                onClick = {
                    PersonalizationPreferences.bottomChromeScrollBehaviorValue =
                        BottomChromeScrollBehavior.DEFAULT
                },
            )
            Spacer(Modifier.height(4.dp))
            BottomChromeScrollBehaviorChoice(
                behavior = BottomChromeScrollBehavior.STATIC,
                selected = bottomChromeScrollBehavior == BottomChromeScrollBehavior.STATIC,
                onClick = {
                    PersonalizationPreferences.bottomChromeScrollBehaviorValue =
                        BottomChromeScrollBehavior.STATIC
                },
            )
        }

        SmallTitle(text = stringResource(R.string.settings_mini_progress_effects_section))
        SettingsCard {
            RawWindowDropdownPreference(
                entry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = stringResource(R.string.settings_mini_progress_direction_ltr),
                            selected = progressEffects.direction == MiniPlayerProgressDirection.LEFT_TO_RIGHT,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerProgressEffects {
                                    it.copy(direction = MiniPlayerProgressDirection.LEFT_TO_RIGHT)
                                }
                            },
                        ),
                        DropdownItem(
                            text = stringResource(R.string.settings_mini_progress_direction_rtl),
                            selected = progressEffects.direction == MiniPlayerProgressDirection.RIGHT_TO_LEFT,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerProgressEffects {
                                    it.copy(direction = MiniPlayerProgressDirection.RIGHT_TO_LEFT)
                                }
                            },
                        ),
                    )
                ),
                title = stringResource(R.string.settings_mini_progress_direction_title),
                summary = stringResource(
                    if (progressEffects.direction == MiniPlayerProgressDirection.LEFT_TO_RIGHT) {
                        R.string.settings_mini_progress_direction_ltr
                    } else {
                        R.string.settings_mini_progress_direction_rtl
                    }
                ),
                maxHeight = 220.dp,
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_bubble_count_title),
                value = progressEffects.bubbleCount.toFloat(),
                valueRange = 0f..32f,
                valueText = progressEffects.bubbleCount.toString(),
                onValueChange = { raw ->
                    PersonalizationPreferences.updateMiniPlayerProgressEffects {
                        it.copy(bubbleCount = raw.toInt().coerceIn(0, 32))
                    }
                },
            )
            val motionFloatLabel = stringResource(R.string.settings_mini_bubble_motion_float)
            val motionWaveLabel = stringResource(R.string.settings_mini_bubble_motion_wave)
            val motionOrbitLabel = stringResource(R.string.settings_mini_bubble_motion_orbit)
            RawWindowDropdownPreference(
                entry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = motionFloatLabel,
                            selected = progressEffects.bubbleMotion == MiniPlayerBubbleMotion.FLOAT,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerProgressEffects {
                                    it.copy(bubbleMotion = MiniPlayerBubbleMotion.FLOAT)
                                }
                            },
                        ),
                        DropdownItem(
                            text = motionWaveLabel,
                            selected = progressEffects.bubbleMotion == MiniPlayerBubbleMotion.WAVE,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerProgressEffects {
                                    it.copy(bubbleMotion = MiniPlayerBubbleMotion.WAVE)
                                }
                            },
                        ),
                        DropdownItem(
                            text = motionOrbitLabel,
                            selected = progressEffects.bubbleMotion == MiniPlayerBubbleMotion.ORBIT,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerProgressEffects {
                                    it.copy(bubbleMotion = MiniPlayerBubbleMotion.ORBIT)
                                }
                            },
                        ),
                    )
                ),
                title = stringResource(R.string.settings_mini_bubble_motion_title),
                summary = when (progressEffects.bubbleMotion) {
                    MiniPlayerBubbleMotion.FLOAT -> motionFloatLabel
                    MiniPlayerBubbleMotion.WAVE -> motionWaveLabel
                    MiniPlayerBubbleMotion.ORBIT -> motionOrbitLabel
                },
                maxHeight = 260.dp,
            )
            PreferenceValueSlider(
                title = stringResource(R.string.settings_mini_bubble_speed_title),
                value = progressEffects.bubbleSpeed,
                valueRange = 0.25f..2.5f,
                valueText = String.format("%.2f×", progressEffects.bubbleSpeed),
                onValueChange = { speed ->
                    PersonalizationPreferences.updateMiniPlayerProgressEffects {
                        it.copy(bubbleSpeed = speed)
                    }
                },
            )
            val originStartLabel = stringResource(
                if (progressEffects.direction == MiniPlayerProgressDirection.LEFT_TO_RIGHT) {
                    R.string.settings_mini_bubble_origin_left
                } else {
                    R.string.settings_mini_bubble_origin_right
                }
            )
            val originTopLabel = stringResource(R.string.settings_mini_bubble_origin_top)
            val originBottomLabel = stringResource(R.string.settings_mini_bubble_origin_bottom)
            RawWindowDropdownPreference(
                entry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = originStartLabel,
                            selected = progressEffects.bubbleOrigin == MiniPlayerBubbleOrigin.START_EDGE,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerProgressEffects {
                                    it.copy(bubbleOrigin = MiniPlayerBubbleOrigin.START_EDGE)
                                }
                            },
                        ),
                        DropdownItem(
                            text = originTopLabel,
                            selected = progressEffects.bubbleOrigin == MiniPlayerBubbleOrigin.TOP,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerProgressEffects {
                                    it.copy(bubbleOrigin = MiniPlayerBubbleOrigin.TOP)
                                }
                            },
                        ),
                        DropdownItem(
                            text = originBottomLabel,
                            selected = progressEffects.bubbleOrigin == MiniPlayerBubbleOrigin.BOTTOM,
                            onClick = {
                                PersonalizationPreferences.updateMiniPlayerProgressEffects {
                                    it.copy(bubbleOrigin = MiniPlayerBubbleOrigin.BOTTOM)
                                }
                            },
                        ),
                    )
                ),
                title = stringResource(R.string.settings_mini_bubble_origin_title),
                summary = when (progressEffects.bubbleOrigin) {
                    MiniPlayerBubbleOrigin.START_EDGE -> originStartLabel
                    MiniPlayerBubbleOrigin.TOP -> originTopLabel
                    MiniPlayerBubbleOrigin.BOTTOM -> originBottomLabel
                },
                maxHeight = 260.dp,
            )
            SwitchPreference(
                title = stringResource(R.string.settings_mini_high_energy_title),
                summary = stringResource(R.string.settings_mini_high_energy_summary),
                checked = progressEffects.highEnergyHighlight,
                onCheckedChange = { enabled ->
                    PersonalizationPreferences.updateMiniPlayerProgressEffects {
                        it.copy(highEnergyHighlight = enabled)
                    }
                },
            )
        }

        SmallTitle(text = stringResource(R.string.settings_bottom_navigation_preview_section))
        SettingsCard {
            BottomNavigationPreview(
                scenes = selectedScenes,
                style = bottomNavigationStyle,
                miniPlayerStyle = miniPlayerStyle,
                showMinimized = false,
            )
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
private fun MiniPlayerStylePreview(
    style: MiniPlayerStyleSettings,
    artworkMode: MiniPlayerArtworkMode,
) {
    val previewShape = miniPlayerSurfaceShape(style.cornerRadiusDp.dp, style.cornerCurve)
    val artworkSizeDp = effectiveMiniPlayerArtworkSizeDp(
        requestedArtworkSizeDp = style.artworkSizeDp,
        expandedHeightDp = style.expandedHeightDp,
        compactHeightDp = style.compactHeightDp,
        mode = artworkMode,
    )
    val previewContentEnvelopeDp = maxOf(
        44f,
        when (artworkMode) {
            MiniPlayerArtworkMode.Normal,
            MiniPlayerArtworkMode.Original -> artworkSizeDp
            MiniPlayerArtworkMode.Vinyl -> artworkSizeDp * (52f / 44f)
        },
    )
    val previewVerticalPadding = minOf(
        7.dp,
        ((style.expandedHeightDp - previewContentEnvelopeDp) / 2f).coerceAtLeast(0f).dp,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = style.leadingMarginDp.dp.coerceAtMost(32.dp),
                    end = style.trailingMarginDp.dp.coerceAtMost(32.dp),
                )
                .height(style.expandedHeightDp.dp)
                .clip(previewShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = previewVerticalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (artworkMode) {
                    MiniPlayerArtworkMode.Normal -> {
                        Box(
                            modifier = Modifier
                                .width((artworkSizeDp + 8f).dp)
                                .height(artworkSizeDp.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(artworkSizeDp.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)),
                            )
                        }
                    }
                    MiniPlayerArtworkMode.Vinyl -> {
                        val scale = artworkSizeDp / 44f
                        Box(
                            modifier = Modifier
                                .width((70f * scale).dp)
                                .height((52f * scale).dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size((39f * scale).dp)
                                    .align(Alignment.CenterEnd)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                            )
                            Box(
                                modifier = Modifier
                                    .size((40f * scale).dp)
                                    .align(Alignment.CenterStart)
                                    .clip(RoundedCornerShape((10f * scale).dp))
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)),
                            )
                        }
                    }
                    MiniPlayerArtworkMode.Original -> {
                        val visualSize = originalMiniPlayerArtworkVisualSizeDp(artworkSizeDp)
                        val radius = style.originalArtworkCornerRadiusDp
                            .coerceIn(0f, visualSize / 2f)
                        Box(
                            modifier = Modifier
                                .width((artworkSizeDp + 8f).dp)
                                .height(artworkSizeDp.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(visualSize.dp)
                                    .clip(RoundedCornerShape(radius.dp))
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(style.artworkTextGapDp.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.68f)
                            .height(7.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.48f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.34f))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.16f))
                )
                if (style.controlGapDp > 0f) Spacer(Modifier.width(style.controlGapDp.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
            }
        }
    }
}

@Composable
private fun PreferenceValueSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        val rangeStart = valueRange.start
        val rangeEnd = valueRange.endInclusive
        val hasAdjustableRange =
            rangeStart.isFinite() && rangeEnd.isFinite() && rangeEnd > rangeStart
        val safeRangeStart = if (rangeStart.isFinite()) rangeStart else 0f
        val safeRangeEnd = if (hasAdjustableRange) rangeEnd else safeRangeStart + 1f
        Slider(
            value = if (hasAdjustableRange) {
                value.coerceIn(safeRangeStart, safeRangeEnd)
            } else {
                safeRangeStart
            },
            onValueChange = { newValue ->
                if (hasAdjustableRange) onValueChange(newValue)
            },
            valueRange = safeRangeStart..safeRangeEnd,
            enabled = hasAdjustableRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BottomChromeScrollBehaviorChoice(
    behavior: BottomChromeScrollBehavior,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val titleRes = when (behavior) {
        BottomChromeScrollBehavior.DEFAULT -> R.string.settings_bottom_navigation_scroll_behavior_default
        BottomChromeScrollBehavior.STATIC -> R.string.settings_bottom_navigation_scroll_behavior_static
    }
    val summaryRes = when (behavior) {
        BottomChromeScrollBehavior.DEFAULT -> R.string.settings_bottom_navigation_scroll_behavior_default_summary
        BottomChromeScrollBehavior.STATIC -> R.string.settings_bottom_navigation_scroll_behavior_static_summary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(titleRes),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(summaryRes),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun BottomNavigationPreview(
    scenes: List<NavScene>,
    style: BottomNavigationStyleSettings,
    miniPlayerStyle: MiniPlayerStyleSettings,
    showMinimized: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    val contentColor = scheme.onSurface
    val navigationHeight = style.heightDp.dp
    val navigationCorner = style.cornerRadiusDp.dp.coerceAtMost(navigationHeight / 2f)
    val navigationShape = miniPlayerSurfaceShape(navigationCorner, style.cornerCurve)
    val compactSize = miniPlayerStyle.compactHeightDp.dp
    val compactNavigationCorner = style.cornerRadiusDp.dp.coerceAtMost(compactSize / 2f)
    val compactNavigationShape = miniPlayerSurfaceShape(
        compactNavigationCorner,
        style.cornerCurve,
    )
    val compactMiniCorner = (miniPlayerStyle.cornerRadiusDp + 5f).dp
        .coerceAtMost(compactSize / 2f)
    val compactMiniShape = miniPlayerSurfaceShape(
        compactMiniCorner,
        miniPlayerStyle.cornerCurve,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_bottom_navigation_preview_expanded),
            color = scheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.onSurface.copy(alpha = 0.045f))
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = style.leadingMarginDp.dp,
                        end = style.trailingMarginDp.dp,
                    )
                    .height(navigationHeight)
                    .clip(navigationShape)
                    .background(scheme.surfaceContainer)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                scenes.forEachIndexed { index, scene ->
                    val selected = index == 0
                    val tint = if (selected) scheme.primary else contentColor.copy(alpha = 0.66f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 3.dp)
                                    .height((style.heightDp - 12f).coerceAtLeast(28f).dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(scheme.primary.copy(alpha = 0.13f)),
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            BottomNavigationEntryIcon(
                                scene = scene,
                                tint = tint,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = scene.bottomNavigationLabel(),
                                color = tint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        if (showMinimized) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_bottom_navigation_preview_minimized),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.onSurface.copy(alpha = 0.045f))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(compactSize),
                ) {
                    val inlineGap = 8.dp
                    val navigationLeadingMargin = style.leadingMarginDp.dp
                    val navigationTrailingMargin = style.trailingMarginDp.dp
                    val inlineLeadingReserve = navigationLeadingMargin + compactSize + inlineGap
                    val inlineTrailingReserve = navigationTrailingMargin + compactSize + inlineGap
                    val miniWidth = (maxWidth - inlineLeadingReserve - inlineTrailingReserve)
                        .coerceAtLeast(1.dp)
                    val miniCenterShift = (inlineLeadingReserve - inlineTrailingReserve) / 2f
                    val selectedScene = scenes.firstOrNull() ?: NavScene.HOME

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = navigationLeadingMargin)
                            .size(compactSize)
                            .clip(compactNavigationShape)
                            .background(scheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomNavigationEntryIcon(
                            scene = selectedScene,
                            tint = scheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = miniCenterShift)
                            .width(miniWidth)
                            .height(compactSize)
                            .clip(compactMiniShape)
                            .background(scheme.surfaceContainer),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(
                                        miniPlayerStyle.artworkSizeDp
                                            .coerceAtMost(miniPlayerStyle.compactHeightDp - 8f)
                                            .dp,
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(scheme.primary.copy(alpha = 0.22f)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.72f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(contentColor.copy(alpha = 0.58f)),
                                )
                                Spacer(Modifier.height(5.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.46f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(contentColor.copy(alpha = 0.25f)),
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = navigationTrailingMargin)
                            .size(compactSize)
                            .clip(compactNavigationShape)
                            .background(scheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomNavigationEntryIcon(
                            scene = NavScene.SEARCH,
                            tint = contentColor.copy(alpha = 0.72f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
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
