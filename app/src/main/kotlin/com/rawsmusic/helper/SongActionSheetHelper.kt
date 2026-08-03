package com.rawsmusic.helper

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleCoroutineScope
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Ok

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

    var isCreatingPlaylist by mutableStateOf(false)
        private set

    var hasCustomCover by mutableStateOf(false)
    private var pendingPlaylistSong: AudioFile? = null
    private var pendingPlaylistSongs: List<AudioFile> = emptyList()

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
        isCreatingPlaylist = false
        pendingPlaylistSong = null
        pendingPlaylistSongs = emptyList()
        setGestureInterceptDisabled(false)
        onVisibilityChanged(false)
    }

    fun updateCoverRestoreButton() = Unit

    fun addToPlaylist() {
        val song = getPlayerController()?.currentSong?.value ?: return
        val playlistStore = PlaylistStore.getInstance(context)
        val playlists = playlistStore.playlists.value
        pendingPlaylistSong = song
        pendingPlaylistSongs = emptyList()
        playlistChoices = playlists
        isCreatingPlaylist = false
        isPlaylistPickerShowing = true
        setGestureInterceptDisabled(true)
        onVisibilityChanged(true)
    }

    fun showPlaylistPickerForSongs(songs: List<AudioFile>) {
        val playlistStore = PlaylistStore.getInstance(context)
        val playlists = playlistStore.playlists.value
        pendingPlaylistSongs = songs
        pendingPlaylistSong = songs.firstOrNull()
        playlistChoices = playlists
        isCreatingPlaylist = false
        isPlaylistPickerShowing = true
        setGestureInterceptDisabled(true)
        onVisibilityChanged(true)
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
                val message = playlist?.let { context.getString(R.string.ui_playlist_added, it.name) }
                    ?: context.getString(R.string.ui_playlist_create_failed)
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun selectPlaylist(playlist: UserPlaylist) {
        val songs = pendingPlaylistSongs.ifEmpty {
            listOfNotNull(pendingPlaylistSong ?: getPlayerController()?.currentSong?.value)
        }
        if (songs.isEmpty()) return
        hidePlaylistPicker()
        val playlistStore = PlaylistStore.getInstance(context)
        getCoroutineScope().launch {
            playlistStore.addSongsToPlaylist(playlist.id, songs)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.ui_playlist_added, playlist.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun addToQueue() {
        val song = getPlayerController()?.currentSong?.value ?: return
        getPlayerController()?.addToQueue(song)
        Toast.makeText(context, context.getString(R.string.ui_queue_added), Toast.LENGTH_SHORT).show()
    }

    fun showAlbumList() {
        try {
            val song = getPlayerController()?.currentSong?.value ?: return
            val album = song.album.trim()
            if (album.isBlank()) {
                Toast.makeText(context, context.getString(R.string.ui_unknown_album), Toast.LENGTH_SHORT).show()
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
    PlaylistPickerOverlay(helper = helper)
}

@Composable
private fun PlaylistPickerOverlay(helper: SongActionSheetHelper) {
    val visible = helper.isPlaylistPickerShowing
    var newPlaylistName by remember(visible) { mutableStateOf("") }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { helper.hidePlaylistPicker() }
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    initialOffsetY = { it }
                ) + fadeIn(tween(120)),
                exit = slideOutVertically(
                    animationSpec = tween(180),
                    targetOffsetY = { it }
                ) + fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(Color(0xF21B1816))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (helper.isCreatingPlaylist) stringResource(R.string.ui_create_playlist_title)
                        else stringResource(R.string.ui_playlist_picker_title),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (helper.isCreatingPlaylist) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = newPlaylistName,
                                onValueChange = { newPlaylistName = it },
                                singleLine = true,
                                cursorBrush = SolidColor(Color(0xFF8DA8FF)),
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (newPlaylistName.isBlank()) {
                                Text(
                                    text = stringResource(R.string.ui_playlist_name),
                                    color = Color.White.copy(alpha = 0.38f),
                                    fontSize = 15.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PlaylistPickerActionButton(
                                text = stringResource(R.string.ui_back),
                                icon = { Icon(MiuixIcons.Regular.Back, contentDescription = null, tint = Color.White.copy(alpha = 0.76f)) },
                                modifier = Modifier.weight(1f),
                                onClick = { helper.cancelCreatePlaylist() }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            PlaylistPickerActionButton(
                                text = stringResource(R.string.ui_playlist_create_and_add),
                                icon = { Icon(MiuixIcons.Regular.Ok, contentDescription = null, tint = Color.White) },
                                tint = Color(0xFF8DA8FF),
                                modifier = Modifier.weight(1f),
                                onClick = { helper.createPlaylistAndAdd(newPlaylistName) }
                            )
                        }
                    } else {
                        PlaylistPickerRow(
                            title = stringResource(R.string.ui_create_playlist_title),
                            subtitle = stringResource(R.string.ui_playlist_created_summary),
                            icon = { Icon(MiuixIcons.Regular.Folder, contentDescription = null, tint = Color.White.copy(alpha = 0.82f)) },
                            onClick = {
                                newPlaylistName = ""
                                helper.startCreatePlaylist()
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(288.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            helper.playlistChoices.forEach { playlist ->
                                PlaylistPickerRow(
                                    title = playlist.name,
                                    subtitle = stringResource(R.string.ui_playlist_song_count, playlist.songs.size),
                                    icon = { Icon(MiuixIcons.Regular.Music, contentDescription = null, tint = Color.White.copy(alpha = 0.72f)) },
                                    onClick = { helper.selectPlaylist(playlist) }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { helper.hidePlaylistPicker() }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = stringResource(R.string.ui_cancel), color = Color(0xFF8DA8FF), fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp, maxLines = 1)
            Text(text = subtitle, color = Color.White.copy(alpha = 0.42f), fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun PlaylistPickerActionButton(
    text: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.16f),
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(tint.copy(alpha = if (tint.alpha < 1f) tint.alpha else 0.18f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}
