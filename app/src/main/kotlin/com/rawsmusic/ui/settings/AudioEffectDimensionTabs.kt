package com.rawsmusic.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class AudioEffectDimension(@StringRes val titleRes: Int) {
    PARAMETRIC_EQ(R.string.settings_effects_tab_parametric_eq),
    GRAPHIC_EQ(R.string.settings_effects_tab_graphic_eq),
    SPATIAL_REVERB(R.string.settings_effects_tab_spatial_reverb),
    ADVANCED(R.string.settings_effects_tab_advanced)
}

/**
 * Embedded capsule tabs inspired by the project's main navigation bar.
 *
 * Deliberately excludes backdrop blur, liquid-glass lensing, elevation and
 * floating offsets. Selection changes are immediate; the pager owns any
 * direct-manipulation motion from a user's horizontal swipe.
 */
@Composable
internal fun AudioEffectDimensionTabs(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AudioEffectDimension.entries.forEachIndexed { index, dimension ->
            val selected = selectedIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (selected) MiuixTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelected(index) }
                    )
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(dimension.titleRes),
                    color = if (selected) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily()
                )
            }
        }
    }
}
