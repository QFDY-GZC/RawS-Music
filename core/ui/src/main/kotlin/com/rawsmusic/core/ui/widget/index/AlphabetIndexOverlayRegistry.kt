package com.rawsmusic.core.ui.widget.index

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

internal data class AlphabetIndexOverlayEntry(
    val owner: Any,
    val sceneId: String,
    val data: RawAlphabetIndexData,
    val modifier: Modifier,
    val enabled: Boolean,
    val minCellHeightDp: Float,
    val scrollActiveProvider: (() -> Boolean)?,
    val onTopSelect: (() -> Unit)?,
    val onSelect: (String, Int) -> Unit,
)

@Stable
internal class AlphabetIndexOverlayRegistry {
    private var entries by mutableStateOf<List<AlphabetIndexOverlayEntry>>(emptyList())

    fun publish(value: AlphabetIndexOverlayEntry) {
        val current = entries
        val existingIndex = current.indexOfFirst { it.owner === value.owner }
        entries = if (existingIndex >= 0) {
            current.toMutableList().also { it[existingIndex] = value }
        } else {
            current + value
        }
    }

    fun remove(owner: Any) {
        entries = entries.filterNot { it.owner === owner }
    }

    fun entryFor(sceneId: String): AlphabetIndexOverlayEntry? =
        entries.lastOrNull { it.sceneId == sceneId }
}

internal val LocalAlphabetIndexOverlayRegistry =
    staticCompositionLocalOf<AlphabetIndexOverlayRegistry?> { null }
