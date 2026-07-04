package com.rawsmusic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.Folder
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.ui.settings.SettingsPage
import com.rawsmusic.ui.settings.ThemeColors
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.delay

@Composable
fun GlobalSearchScreen(
    playlistStore: PlaylistStore,
    onBack: () -> Unit,
    onSongClick: (AudioFile) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlaylistClick: (UserPlaylist) -> Unit,
    onFolderClick: (Folder) -> Unit
) {
    val colors = themeColors()
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
    }

    val songs by MusicRepository.songs.collectAsState()
    val artists by MusicRepository.artists.collectAsState()
    val albums by MusicRepository.albums.collectAsState()
    val folders by MusicRepository.folders.collectAsState()
    val playlists by playlistStore.playlists.collectAsState()

    val filteredSongs = remember(debouncedQuery, songs) {
        if (debouncedQuery.isBlank()) emptyList()
        else {
            val q = debouncedQuery.lowercase()
            songs.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
    }

    val filteredAlbums = remember(debouncedQuery, albums) {
        if (debouncedQuery.isBlank()) emptyList()
        else {
            val q = debouncedQuery.lowercase()
            albums.filter {
                it.name.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q)
            }
        }
    }

    val filteredArtists = remember(debouncedQuery, artists) {
        if (debouncedQuery.isBlank()) emptyList()
        else {
            val q = debouncedQuery.lowercase()
            artists.filter { it.name.lowercase().contains(q) }
        }
    }

    val filteredPlaylists = remember(debouncedQuery, playlists) {
        if (debouncedQuery.isBlank()) emptyList()
        else {
            val q = debouncedQuery.lowercase()
            playlists.filter { it.name.lowercase().contains(q) }
        }
    }

    val filteredFolders = remember(debouncedQuery, folders) {
        if (debouncedQuery.isBlank()) emptyList()
        else {
            val q = debouncedQuery.lowercase()
            folders.filter {
                it.name.lowercase().contains(q) ||
                    it.path.lowercase().contains(q)
            }
        }
    }

    val hasResults = filteredSongs.isNotEmpty() || filteredAlbums.isNotEmpty() ||
        filteredArtists.isNotEmpty() || filteredPlaylists.isNotEmpty() ||
        filteredFolders.isNotEmpty()

    SettingsPage(title = "全局搜索", onBack = onBack) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("搜索歌曲、专辑、歌手、歌单、文件夹", color = colors.onSurfaceVariant, fontFamily = appFontFamily())
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.outline,
                focusedTextColor = colors.onSurface,
                unfocusedTextColor = colors.onSurface,
                cursorColor = colors.primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                fontFamily = appFontFamily()
            )
        )

        Spacer(Modifier.height(16.dp))

        if (debouncedQuery.isBlank()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("输入关键词开始搜索", fontSize = 16.sp, color = colors.onSurfaceVariant, fontFamily = appFontFamily())
                }
            }
        } else if (!hasResults) {
            Box(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("未找到相关结果", fontSize = 16.sp, color = colors.onSurfaceVariant, fontFamily = appFontFamily())
                }
            }
        } else {
            if (filteredSongs.isNotEmpty()) {
                SearchSection(
                    title = "歌曲",
                    count = filteredSongs.size,
                    colors = colors
                ) {
                    filteredSongs.take(3).forEach { song ->
                        SearchResultRow(
                            title = song.title,
                            subtitle = song.artist,
                            extra = song.album,
                            colors = colors,
                            onClick = { onSongClick(song) }
                        )
                    }
                    if (filteredSongs.size > 3) {
                        ViewMoreRow(
                            label = "查看更多歌曲 (${filteredSongs.size})",
                            colors = colors
                        )
                    }
                }
            }

            if (filteredAlbums.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SearchSection(
                    title = "专辑",
                    count = filteredAlbums.size,
                    colors = colors
                ) {
                    filteredAlbums.take(3).forEach { album ->
                        SearchResultRow(
                            title = album.name,
                            subtitle = album.artist,
                            extra = "${album.songCount}首",
                            colors = colors,
                            onClick = { onAlbumClick(album) }
                        )
                    }
                    if (filteredAlbums.size > 3) {
                        ViewMoreRow(
                            label = "查看更多专辑 (${filteredAlbums.size})",
                            colors = colors
                        )
                    }
                }
            }

            if (filteredArtists.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SearchSection(
                    title = "歌手",
                    count = filteredArtists.size,
                    colors = colors
                ) {
                    filteredArtists.take(3).forEach { artist ->
                        SearchResultRow(
                            title = artist.name,
                            subtitle = "${artist.songCount}首歌曲 · ${artist.albumCount}张专辑",
                            extra = "",
                            colors = colors,
                            onClick = { onArtistClick(artist) }
                        )
                    }
                    if (filteredArtists.size > 3) {
                        ViewMoreRow(
                            label = "查看更多歌手 (${filteredArtists.size})",
                            colors = colors
                        )
                    }
                }
            }

            if (filteredPlaylists.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SearchSection(
                    title = "歌单",
                    count = filteredPlaylists.size,
                    colors = colors
                ) {
                    filteredPlaylists.take(3).forEach { playlist ->
                        SearchResultRow(
                            title = playlist.name,
                            subtitle = "${playlist.songs.size}首歌曲",
                            extra = "",
                            colors = colors,
                            onClick = { onPlaylistClick(playlist) }
                        )
                    }
                    if (filteredPlaylists.size > 3) {
                        ViewMoreRow(
                            label = "查看更多歌单 (${filteredPlaylists.size})",
                            colors = colors
                        )
                    }
                }
            }

            if (filteredFolders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SearchSection(
                    title = "文件夹",
                    count = filteredFolders.size,
                    colors = colors
                ) {
                    filteredFolders.take(3).forEach { folder ->
                        SearchResultRow(
                            title = folder.name,
                            subtitle = folder.path,
                            extra = "${folder.songCount}首",
                            colors = colors,
                            onClick = { onFolderClick(folder) }
                        )
                    }
                    if (filteredFolders.size > 3) {
                        ViewMoreRow(
                            label = "查看更多文件夹 (${filteredFolders.size})",
                            colors = colors
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSection(
    title: String,
    count: Int,
    colors: ThemeColors,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    fontFamily = appFontFamily()
                )
                Text(
                    "$count",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                    fontFamily = appFontFamily()
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String,
    extra: String,
    colors: ThemeColors,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily()
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily()
                )
            }
        }
        if (extra.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                extra,
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                fontFamily = appFontFamily()
            )
        }
    }
}

@Composable
private fun ViewMoreRow(
    label: String,
    colors: ThemeColors
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = colors.primary,
            fontWeight = FontWeight.Medium,
            fontFamily = appFontFamily()
        )
    }
}
