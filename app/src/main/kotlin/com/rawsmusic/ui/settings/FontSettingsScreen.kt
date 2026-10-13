package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class FontSettingsSlot {
    GLOBAL,
    LYRICS,
}

@Composable
internal fun FontSettingsScreen(
    onBack: () -> Unit,
    onApplyGlobal: () -> Unit,
    onImportGlobalFont: () -> Unit,
    onImportLyricFont: () -> Unit,
    initialSlot: FontSettingsSlot = FontSettingsSlot.GLOBAL,
) {
    var selectedSlotName by rememberSaveable(initialSlot) { mutableStateOf(initialSlot.name) }
    val selectedSlot = runCatching { FontSettingsSlot.valueOf(selectedSlotName) }
        .getOrDefault(initialSlot)

    SettingsPage(
        title = stringResource(R.string.settings_font_settings_title),
        onBack = onBack,
    ) {
        FontSettingsScopeTabs(
            selected = selectedSlot,
            onSelected = { selectedSlotName = it.name },
        )
        Spacer(Modifier.height(14.dp))

        when (selectedSlot) {
            FontSettingsSlot.GLOBAL -> GlobalFontSettingsContent(
                onApply = onApplyGlobal,
                onImportFont = onImportGlobalFont,
            )

            FontSettingsSlot.LYRICS -> LyricFontSettingsContent(
                onImportFont = onImportLyricFont,
            )
        }
    }
}

@Composable
private fun FontSettingsScopeTabs(
    selected: FontSettingsSlot,
    onSelected: (FontSettingsSlot) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(24.dp),
            )
            .padding(4.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FontSettingsSlot.entries.forEach { slot ->
            val isSelected = slot == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        color = if (isSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                        shape = RoundedCornerShape(20.dp),
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelected(slot) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (slot) {
                            FontSettingsSlot.GLOBAL -> R.string.settings_font_scope_global
                            FontSettingsSlot.LYRICS -> R.string.settings_font_scope_lyrics
                        }
                    ),
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
    }
}
