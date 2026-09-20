package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.rawsmusic.core.common.model.AudioFile

/**
 * Optional provider-specific holder renderer used by the single persistent VirtualList body.
 *
 * Reference's adapter can bind heterogeneous ViewHolder types inside one VirtualList.  Raw previously
 * represented HOME with an entirely different Lazy/scroll container.  This adapter hook keeps the
 * physical holder/layout engine common while allowing HOME header/carousel/card holders to retain
 * their native visual content.
 */
@Immutable
data class VirtualListCustomProvider(
    val itemHeight: (AudioFile, Int) -> Dp?,
    val content: @Composable (
        song: AudioFile,
        index: Int,
        modifier: Modifier,
    ) -> Unit,
    val handles: (AudioFile, Int) -> Boolean,
)

val LocalVirtualListCustomProvider =
    staticCompositionLocalOf<VirtualListCustomProvider?> { null }
