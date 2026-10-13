package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.requiredSize
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistStore
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalPlayerPlaylistAction = staticCompositionLocalOf<((AudioFile) -> Unit)?> { null }

/** Hosts supply the existing playlist picker; the player UI never writes a second playlist store. */
@Composable
fun PlayerPlaylistActionProvider(onAddSong: (AudioFile) -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlayerPlaylistAction provides onAddSong, content = content)
}

@Composable
fun rememberPlayerPlaylistAction(): ((AudioFile) -> Unit)? = LocalPlayerPlaylistAction.current

@Composable
internal fun PlayerMoreFavoriteButton(song: AudioFile?, onAddSong: ((AudioFile) -> Unit)?) {
    if (onAddSong == null) return
    var visible by remember { mutableStateOf(AppPreferences.UI.playerMoreFavoriteVisible) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) visible = AppPreferences.UI.playerMoreFavoriteVisible
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!visible) return
    val context = LocalContext.current.applicationContext
    val store = remember(context) { PlaylistStore.getInstance(context) }
    val playlists by store.playlists.collectAsState()
    val songKey = song?.playlistIdentityKey()
    val savedPlaylistIds = remember(songKey, playlists) {
        playerSavedPlaylistIds(songKey, playlists.asSequence().map { playlist -> playlist.id to playlist.songs.map { it.key } })
    }
    val saved = savedPlaylistIds.isNotEmpty()
    val scale = remember(songKey) { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val event = PlayerFavoriteSuccessRuntime.latest
    val gate = remember(songKey) { PlayerFavoriteCelebrationGate(songKey, event?.revision ?: 0L) }
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.player_favorite_splash))
    val splashProgress = remember(songKey) { Animatable(0f) }
    var celebrating by remember(songKey) { mutableStateOf(false) }
    val playlistMounted = PlayerPlaylistBackRuntime.mounted
    LaunchedEffect(songKey, event?.revision, playlistMounted, composition) {
        if (composition != null && gate.update(event?.revision ?: 0L, event?.songKey, playlistMounted)) {
            celebrating = true
            try {
                splashProgress.snapTo(0f)
                splashProgress.animateTo(1f, tween(650, easing = LinearEasing))
            } finally {
                celebrating = false
            }
        }
    }
    val scheme = MiuixTheme.colorScheme
    val tint = if (saved) Color(0xFFEF5350) else scheme.onSurface.copy(alpha = if (song == null) 0.38f else 1f)
    Box(
        modifier = Modifier.padding(start = 8.dp).size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape)
                .clickable(enabled = song != null) {
                    song?.let { clickedSong ->
                        scope.launch {
                            scale.snapTo(1f)
                            scale.animateTo(0.82f, tween(90))
                            scale.animateTo(1.16f, spring(dampingRatio = 0.7f, stiffness = 700f))
                            scale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 700f))
                        }
                        onAddSong(clickedSong)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(if (saved) R.drawable.ic_playlist_favorite_filled else R.drawable.ic_playlist_favorite_outline),
                contentDescription = stringResource(if (saved) R.string.player_more_remove_from_playlists else R.string.player_more_add_to_playlist),
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(22.dp).graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
        }
        if (celebrating) {
            LottieAnimation(
                composition = composition,
                progress = { splashProgress.value },
                modifier = Modifier.requiredSize(64.dp),
            )
        }
    }
}
