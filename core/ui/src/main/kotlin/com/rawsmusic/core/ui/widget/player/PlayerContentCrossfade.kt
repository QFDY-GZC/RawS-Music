package com.rawsmusic.core.ui.widget.player

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.core.ui.widget.bitmaps.AUTO_CROSSFADE_ARTWORK_FADE_IN_MS
import com.rawsmusic.core.ui.widget.bitmaps.automaticCrossfadeArtworkInAlpha

private class PlayerContentFrame<T>(val key: Any?, initial: T) {
    var value by mutableStateOf(initial)
}

/** Keep the outgoing song's last content frozen; timeline ticks do not restart the fade. */
@Composable
internal fun <T> PlayerContentCrossfade(
    key: Any?,
    value: T,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    val frame = remember(key) { PlayerContentFrame(key, value) }
    SideEffect { frame.value = value }
    val duration = if (enabled && LocalUiFrameAnimationActive.current &&
        (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) AUTO_CROSSFADE_ARTWORK_FADE_IN_MS else 0
    Crossfade(targetState = frame, modifier = modifier,
        animationSpec = tween(duration, easing = Easing { automaticCrossfadeArtworkInAlpha(it) }),
        label = "player-song-content") { shown ->
        val retired = shown !== frame
        Box(propagateMinConstraints = true, modifier = if (!retired) Modifier else Modifier
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) { content(shown.value) }
    }
}

internal data class PlayerLyricFrame(
    val song: io.github.proify.lyricon.lyric.model.Song?,
    val positionMs: Long,
)

internal fun playerLyricIdentity(song: io.github.proify.lyricon.lyric.model.Song?): Any? =
    song?.id?.takeIf { it.isNotBlank() } ?: song?.let { "${it.name}|${it.artist}|${it.duration}" }
