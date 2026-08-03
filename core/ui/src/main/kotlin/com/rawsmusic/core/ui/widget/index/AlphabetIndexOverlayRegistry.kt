package com.rawsmusic.core.ui.widget.index

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

internal data class AlphabetIndexOverlayEntry(
    val owner: Any,
    val data: RawAlphabetIndexData,
    val modifier: Modifier,
    val enabled: Boolean,
    val minCellHeightDp: Float,
    val onTopSelect: (() -> Unit)?,
    val onSelect: (String, Int) -> Unit,
)

@Stable
internal class AlphabetIndexOverlayRegistry {
    var entry by mutableStateOf<AlphabetIndexOverlayEntry?>(null)
        private set

    fun publish(value: AlphabetIndexOverlayEntry) {
        entry = value
    }

    fun remove(owner: Any) {
        if (entry?.owner === owner) entry = null
    }
}

internal val LocalAlphabetIndexOverlayRegistry =
    staticCompositionLocalOf<AlphabetIndexOverlayRegistry?> { null }
