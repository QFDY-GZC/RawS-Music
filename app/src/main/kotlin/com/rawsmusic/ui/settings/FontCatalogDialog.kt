package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.LyricFontManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FontCatalogDialog(
    title: String,
    selectedPath: String,
    systemFonts: List<LyricFontManager.FontInfo>,
    importedFonts: List<LyricFontManager.FontInfo>,
    onDismiss: () -> Unit,
    onSelect: (LyricFontManager.FontInfo?) -> Unit,
    onImport: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val allFonts = remember(systemFonts, importedFonts, normalized) {
        (importedFonts + systemFonts)
            .distinctBy { it.path }
            .filter { normalized.isBlank() || it.name.lowercase().contains(normalized) }
    }
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    val cardShape = RoundedCornerShape(28.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = 28.dp, vertical = 52.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 650.dp)
                    .shadow(10.dp, cardShape)
                    .clip(cardShape)
                    .background(MiuixTheme.colorScheme.surface)
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    label = stringResource(R.string.settings_font_search_hint),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 450.dp)
                        .padding(top = 10.dp),
                ) {
                    item(key = "__default__") {
                        FontCatalogRow(
                            name = stringResource(R.string.settings_default_system_font),
                            selected = selectedPath.isBlank(),
                            onClick = {
                                onSelect(null)
                                onDismiss()
                            },
                        )
                    }
                    items(allFonts, key = { it.path }) { font ->
                        FontCatalogRow(
                            name = font.name,
                            selected = selectedPath == font.path,
                            onClick = {
                                onSelect(font)
                                onDismiss()
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onImport != null) {
                        TextButton(
                            text = stringResource(R.string.settings_lyric_import_font_file),
                            onClick = {
                                onDismiss()
                                onImport()
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    TextButton(
                        text = stringResource(R.string.settings_cancel),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun FontCatalogRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
        )
        if (selected) {
            Text(
                text = "✓",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
