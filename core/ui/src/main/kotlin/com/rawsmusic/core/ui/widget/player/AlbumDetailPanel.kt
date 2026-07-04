package com.rawsmusic.core.ui.widget.player

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.scene.pages.themeColors
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage

@Composable
fun AlbumDetailPanel(
    currentSong: AudioFile?,
    songs: List<AudioFile>,
    coverPath: String?,
    onSongClick: (AudioFile, Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = themeColors()
    val albumName = currentSong?.album?.ifBlank { "未知专辑" } ?: "未知专辑"
    val artist = currentSong?.albumArtist?.ifBlank { currentSong.artist }?.ifBlank { "未知艺术家" } ?: "未知艺术家"
    val hasHiRes = songs.any { it.isHiRes }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = colors.primary, fontSize = 14.sp)
            }
            Text(
                "专辑详情",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )
            Spacer(Modifier.width(56.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(86.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
            ) {
                if (!coverPath.isNullOrBlank()) {
                    BitmapImage(
                        key = coverPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetWidth = 320,
                        targetHeight = 320
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        albumName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (hasHiRes) {
                        Spacer(Modifier.width(6.dp))
                        Text("Hi-Res", fontSize = 10.sp, color = colors.primary, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    artist,
                    fontSize = 14.sp,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${songs.size} 首",
                    fontSize = 12.sp,
                    color = colors.secondaryText
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(songs) { index, song ->
                AlbumSongRow(
                    song = song,
                    index = index,
                    onClick = { onSongClick(song, index) }
                )
            }
        }
    }
}

@Composable
private fun AlbumSongRow(
    song: AudioFile,
    index: Int,
    onClick: () -> Unit
) {
    val colors = themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${index + 1}",
            fontSize = 13.sp,
            color = colors.secondaryText,
            modifier = Modifier.width(32.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                song.displayName,
                fontSize = 14.sp,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artist.ifBlank { "未知艺术家" },
                fontSize = 12.sp,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            AudioUtils.formatDuration(song.duration),
            fontSize = 12.sp,
            color = colors.secondaryText
        )
    }
}
