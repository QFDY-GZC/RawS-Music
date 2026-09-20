package com.rawsmusic.helper

import android.content.Context
import android.widget.Toast
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.LifecycleCoroutineScope
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.ui.widget.ActivityOverlayBackOwner
import com.rawsmusic.core.ui.widget.predictiveBottomSheetMotion
import com.rawsmusic.core.ui.widget.predictiveBottomSheetScrim
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding

/**
 * 歌曲动作表控制器。
 *
 * View/XML 版已经迁移为 Compose overlay；本类只保留显示状态和业务动作。
 */
class SongActionSheetHelper(
    private val context: Context,
    private val getPlayerController: () -> PlayerController?,
    private val setGestureInterceptDisabled: (Boolean) -> Unit,
    private val closePlayPage: () -> Unit,
    private val resolveCoverUri: (AudioFile) -> String,
    private val getCoroutineScope: () -> LifecycleCoroutineScope,
    private val openAlbumDetail: (albumName: String, albumArtist: String, coverPath: String) -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    var isSongActionSheetShowing by mutableStateOf(false)
        private set

    var isPlaylistPickerShowing by mutableStateOf(false)
        private set

    var playlistChoices by mutableStateOf<List<UserPlaylist>>(emptyList())
        private set

    var selectedPlaylistIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var isAddingToPlaylists by mutableStateOf(false)
        private set

    var isCreatingPlaylist by mutableStateOf(false)
        private set

    var hasCustomCover by mutableStateOf(false)
    private var pendingPlaylistSong: AudioFile? = null
    private var pendingPlaylistSongs: List<AudioFile> = emptyList()
    private var playlistChoicesObserverStarted = false

    var onEditMetadata: (() -> Unit)? = null
    var onOpenMetadataDetail: (() -> Unit)? = null
    var onDeleteCurrentSong: (() -> Unit)? = null
    var onPickCoverImage: (() -> Unit)? = null
    var onRestoreCover: (() -> Unit)? = null

    fun setup() = Unit

    fun show() {
        isSongActionSheetShowing = true
        setGestureInterceptDisabled(true)
        onVisibilityChanged(true)
    }

    fun hide() {
        isSongActionSheetShowing = false
        setGestureInterceptDisabled(false)
        onVisibilityChanged(false)
    }

    fun hidePlaylistPicker() {
        if (!isPlaylistPickerShowing) return
        isPlaylistPickerShowing = false
        pendingPlaylistSong = null
        pendingPlaylistSongs = emptyList()
        isAddingToPlaylists = false
        setGestureInterceptDisabled(false)
        onVisibilityChanged(false)
    }

    fun updateCoverRestoreButton() = Unit

    fun addToPlaylist() {
        val song = getPlayerController()?.currentSong?.value ?: return
        val playlistStore = PlaylistStore.getInstance(context)
        pendingPlaylistSong = song
        pendingPlaylistSongs = emptyList()
        playlistChoices = playlistStore.playlists.value
        selectedPlaylistIds = emptySet()
        isAddingToPlaylists = false
        isCreatingPlaylist = false
        isPlaylistPickerShowing = true
        setGestureInterceptDisabled(true)
        onVisibilityChanged(true)
        observePlaylistChoicesWhilePickerVisible(playlistStore)
    }

    fun showPlaylistPickerForSongs(songs: List<AudioFile>) {
        val playlistStore = PlaylistStore.getInstance(context)
        pendingPlaylistSongs = songs
        pendingPlaylistSong = songs.firstOrNull()
        playlistChoices = playlistStore.playlists.value
        selectedPlaylistIds = emptySet()
        isAddingToPlaylists = false
        isCreatingPlaylist = false
        isPlaylistPickerShowing = true
        setGestureInterceptDisabled(true)
        onVisibilityChanged(true)
        observePlaylistChoicesWhilePickerVisible(playlistStore)
    }

    private fun observePlaylistChoicesWhilePickerVisible(playlistStore: PlaylistStore) {
        if (playlistChoicesObserverStarted) return
        playlistChoicesObserverStarted = true
        getCoroutineScope().launch {
            playlistStore.playlists.collect { playlists ->
                if (!isPlaylistPickerShowing) return@collect
                playlistChoices = playlists
                val liveIds = playlists.asSequence().map { it.id }.toSet()
                selectedPlaylistIds = selectedPlaylistIds.intersect(liveIds)
            }
        }
    }

    fun startCreatePlaylist() {
        isCreatingPlaylist = true
    }

    fun cancelCreatePlaylist() {
        isCreatingPlaylist = false
    }

    fun createPlaylistAndAdd(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val songs = pendingPlaylistSongs.ifEmpty {
            listOfNotNull(pendingPlaylistSong ?: getPlayerController()?.currentSong?.value)
        }
        if (songs.isEmpty()) return
        val playlistStore = PlaylistStore.getInstance(context)
        getCoroutineScope().launch {
            val playlist = playlistStore.createPlaylist(trimmed)
            if (playlist != null) {
                playlistStore.addSongsToPlaylist(playlist.id, songs)
            }
            withContext(Dispatchers.Main) {
                hidePlaylistPicker()
                if (playlist != null) {
                    AppNoticeBus.post(
                        message = context.getString(R.string.ui_playlist_added, playlist.name),
                        icon = AppNoticeIcon.PLAYLIST,
                    )
                } else {
                    AppNoticeBus.error(context.getString(R.string.ui_playlist_create_failed))
                }
            }
        }
    }

    fun selectPlaylist(playlist: UserPlaylist) {
        selectedPlaylistIds = if (playlist.id in selectedPlaylistIds) {
            selectedPlaylistIds - playlist.id
        } else {
            selectedPlaylistIds + playlist.id
        }
    }

    fun confirmSelectedPlaylists() {
        if (isAddingToPlaylists) return
        val selected = playlistChoices.filter { it.id in selectedPlaylistIds }
        if (selected.isEmpty()) return
        val songs = pendingPlaylistSongs.ifEmpty {
            listOfNotNull(pendingPlaylistSong ?: getPlayerController()?.currentSong?.value)
        }
        if (songs.isEmpty()) return
        val playlistStore = PlaylistStore.getInstance(context)
        isAddingToPlaylists = true
        getCoroutineScope().launch {
            var succeeded = 0
            var failed = 0
            selected.forEach { playlist ->
                runCatching { playlistStore.addSongsToPlaylist(playlist.id, songs) }
                    .onSuccess { succeeded++ }
                    .onFailure { failed++ }
            }
            withContext(Dispatchers.Main) {
                isAddingToPlaylists = false
                if (succeeded > 0) {
                    hidePlaylistPicker()
                }
                val message = when {
                    succeeded > 0 && failed == 0 -> context.resources.getQuantityString(
                        R.plurals.ui_playlist_added_multiple,
                        succeeded,
                        succeeded,
                    )
                    succeeded > 0 -> context.getString(R.string.ui_playlist_added_partial, succeeded, failed)
                    else -> context.getString(R.string.ui_playlist_add_failed)
                }
                if (failed > 0) {
                    AppNoticeBus.error(message)
                } else {
                    AppNoticeBus.post(
                        message = message,
                        icon = AppNoticeIcon.PLAYLIST,
                    )
                }
            }
        }
    }

    fun addToQueue() {
        val song = getPlayerController()?.currentSong?.value ?: return
        getPlayerController()?.addToQueue(song)
        AppNoticeBus.post(
            message = context.getString(R.string.ui_queue_added),
            icon = AppNoticeIcon.QUEUE,
        )
    }

    fun showAlbumList() {
        try {
            val song = getPlayerController()?.currentSong?.value ?: return
            val album = song.album.trim()
            if (album.isBlank()) {
                AppNoticeBus.error(context.getString(R.string.ui_unknown_album))
                return
            }
            closePlayPage()
            openAlbumDetail(song.album, song.artist, resolveCoverUri(song))
        } catch (_: Exception) {
        }
    }
}

@Composable
fun SongActionSheetOverlay(
    helper: SongActionSheetHelper,
    isImmersiveEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    // The old player/lyric action sheet has been retired.  This helper remains responsible only
    // for the multi-song playlist picker used by selection mode; lyric actions now live in the
    // isolated MIUIX LyricMoreSheet and player settings live in ImmersiveMoreSheet.
    PlaylistPickerOverlay(
        helper = helper,
        modifier = modifier.zIndex(1_000f),
    )
}

@Composable
private fun PlaylistPickerOverlay(
    helper: SongActionSheetHelper,
    modifier: Modifier = Modifier,
) {
    val visible = helper.isPlaylistPickerShowing
    val scheme = MiuixTheme.colorScheme
    var animatedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            // Force one prepared frame with the sheet fully off-screen.  When the helper flips
            // visible in the same snapshot that replaces the long-press sheet, composing directly
            // at visible=true can initialise AnimatedVisibility at its end state and skip enter.
            animatedVisible = false
            withFrameNanos { }
            animatedVisible = true
        } else {
            animatedVisible = false
        }
    }
    val predictiveProgress = rememberPredictiveDialogProgress(
        enabled = visible,
        onDismissRequest = helper::hidePlaylistPicker,
    )
    ActivityOverlayBackOwner(active = visible)
    var newPlaylistName by remember(visible) { mutableStateOf("") }

    AnimatedVisibility(
        visible = animatedVisible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .predictiveBottomSheetScrim(predictiveProgress)
                    .background(Color.Black.copy(alpha = 0.36f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = helper::hidePlaylistPicker,
                    )
            )

            AnimatedVisibility(
                visible = animatedVisible,
                enter = slideInVertically(
                    animationSpec = tween(200),
                    initialOffsetY = { it }
                ),
                exit = slideOutVertically(
                    animationSpec = tween(200),
                    targetOffsetY = { it }
                ),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .predictiveBottomSheetMotion(predictiveProgress)
                        .clip(RoundedCornerShape(30.dp))
                        .background(scheme.surfaceContainerHigh)
                        .border(
                            width = 1.dp,
                            color = scheme.onSurface.copy(alpha = 0.07f),
                            shape = RoundedCornerShape(30.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 38.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.onSurfaceVariantSummary.copy(alpha = 0.28f))
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    AnimatedContent(
                        targetState = helper.isCreatingPlaylist,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                        },
                        label = "playlist-picker-mode",
                    ) { creatingPlaylist ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistPickerLeadingIcon(
                            iconRes = if (creatingPlaylist) R.drawable.ic_add_circle
                            else R.drawable.ic_play_list_add_fill,
                            accent = scheme.primary,
                            selected = true,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (creatingPlaylist) {
                                    stringResource(R.string.ui_create_playlist_title)
                                } else {
                                    stringResource(R.string.ui_playlist_picker_title)
                                },
                                color = scheme.onSurface,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            if (!creatingPlaylist) {
                                Text(
                                    text = stringResource(
                                        R.string.ui_playlist_add_selected,
                                        helper.selectedPlaylistIds.size,
                                    ),
                                    color = scheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    if (creatingPlaylist) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(scheme.background.copy(alpha = 0.62f))
                                .border(
                                    1.dp,
                                    scheme.onSurface.copy(alpha = 0.08f),
                                    RoundedCornerShape(18.dp),
                                )
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = newPlaylistName,
                                onValueChange = { newPlaylistName = it },
                                singleLine = true,
                                cursorBrush = SolidColor(scheme.primary),
                                textStyle = TextStyle(color = scheme.onSurface, fontSize = 16.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (newPlaylistName.isBlank()) {
                                Text(
                                    text = stringResource(R.string.ui_playlist_name),
                                    color = scheme.onSurfaceVariantSummary,
                                    fontSize = 16.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PlaylistPickerActionButton(
                                text = stringResource(R.string.ui_back),
                                icon = {
                                    Icon(
                                        MiuixIcons.Regular.Back,
                                        contentDescription = null,
                                        tint = scheme.onSurface,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                onClick = { helper.cancelCreatePlaylist() }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            PlaylistPickerActionButton(
                                text = stringResource(R.string.ui_playlist_create_and_add),
                                icon = {
                                    Icon(
                                        MiuixIcons.Regular.Ok,
                                        contentDescription = null,
                                        tint = if (newPlaylistName.isBlank()) {
                                            scheme.onSurfaceVariantSummary
                                        } else {
                                            scheme.onPrimary
                                        },
                                    )
                                },
                                emphasized = true,
                                enabled = newPlaylistName.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                onClick = { helper.createPlaylistAndAdd(newPlaylistName) }
                            )
                        }
                    } else {
                        PlaylistPickerRow(
                            title = stringResource(R.string.ui_create_playlist_title),
                            subtitle = stringResource(R.string.ui_playlist_created_summary),
                            iconRes = R.drawable.ic_add_circle,
                            accent = scheme.primary,
                            createAction = true,
                            onClick = {
                                newPlaylistName = ""
                                helper.startCreatePlaylist()
                            }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 336.dp)
                        ) {
                            items(helper.playlistChoices, key = { it.id }) { playlist ->
                                PlaylistPickerRow(
                                    title = playlist.name,
                                    subtitle = stringResource(R.string.ui_playlist_song_count, playlist.songs.size),
                                    iconRes = if (playlist.isFavorites) R.drawable.ic_heart_fill
                                    else R.drawable.ic_music_2_fill,
                                    accent = playlistPickerAccent(playlist),
                                    selected = playlist.id in helper.selectedPlaylistIds,
                                    onClick = { helper.selectPlaylist(playlist) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PlaylistPickerActionButton(
                                text = stringResource(R.string.ui_cancel),
                                icon = null,
                                modifier = Modifier.weight(0.78f),
                                onClick = helper::hidePlaylistPicker,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            val addEnabled = helper.selectedPlaylistIds.isNotEmpty() && !helper.isAddingToPlaylists
                            PlaylistPickerActionButton(
                                text = stringResource(
                                    R.string.ui_playlist_add_selected,
                                    helper.selectedPlaylistIds.size,
                                ),
                                icon = {
                                    Image(
                                        painter = painterResource(R.drawable.ic_check_line),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(
                                            if (addEnabled) scheme.onPrimary else scheme.onSurfaceVariantSummary
                                        ),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                emphasized = true,
                                enabled = addEnabled,
                                modifier = Modifier.weight(1.32f),
                                onClick = helper::confirmSelectedPlaylists,
                            )
                        }
                    }
                    }
                    }
                    Spacer(modifier = Modifier.height(rawStableNavigationBottomPadding() + 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerRow(
    title: String,
    subtitle: String,
    iconRes: Int,
    accent: Color,
    selected: Boolean = false,
    createAction: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(shape)
            .background(
                when {
                    selected -> scheme.primary.copy(alpha = 0.10f)
                    createAction -> scheme.primary.copy(alpha = 0.055f)
                    else -> Color.Transparent
                }
            )
            .then(
                if (createAction || selected) {
                    Modifier.border(
                        1.dp,
                        if (selected) scheme.primary.copy(alpha = 0.16f)
                        else scheme.primary.copy(alpha = 0.10f),
                        shape,
                    )
                } else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistPickerLeadingIcon(
            iconRes = iconRes,
            accent = accent,
            selected = selected || createAction,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = scheme.onSurface,
                fontSize = 15.sp,
                fontWeight = if (selected || createAction) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = scheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!createAction) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) scheme.primary else Color.Transparent)
                    .border(
                        1.dp,
                        if (selected) scheme.primary else scheme.onSurfaceVariantSummary.copy(alpha = 0.32f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Image(
                        painter = painterResource(R.drawable.ic_check_line),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(scheme.onPrimary),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerLeadingIcon(
    iconRes: Int,
    accent: Color,
    selected: Boolean,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = if (selected) 0.18f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(accent),
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun PlaylistPickerActionButton(
    text: String,
    icon: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val background = when {
        emphasized && enabled -> scheme.primary
        emphasized -> scheme.primary.copy(alpha = 0.08f)
        else -> scheme.background.copy(alpha = 0.64f)
    }
    val foreground = when {
        emphasized && enabled -> scheme.onPrimary
        enabled -> scheme.onSurface
        else -> scheme.onSurfaceVariantSummary
    }
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(
                1.dp,
                if (emphasized) Color.Transparent else scheme.onSurface.copy(alpha = 0.07f),
                RoundedCornerShape(18.dp),
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (icon != null) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                icon()
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = foreground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun playlistPickerAccent(playlist: UserPlaylist): Color {
    if (playlist.isFavorites) return Color(0xFFD58A9D)
    val palette = longArrayOf(
        0xFF8997C6,
        0xFF91A889,
        0xFFC39575,
        0xFF7899AF,
        0xFFA38CB4,
        0xFFB59D72,
    )
    val index = (playlist.id.hashCode() and Int.MAX_VALUE) % palette.size
    return Color(palette[index])
}
