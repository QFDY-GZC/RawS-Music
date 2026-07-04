package com.rawsmusic.ui.albums

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.pages.themeColors
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.ui.songs.PlayerHolder

/**
 * 专辑详情页面（纯 Compose）。
 * 从 AlbumDetailFragment 迁移。
 */
@Composable
fun AlbumDetailPageCompose(
    albumName: String,
    albumArtist: String,
    coverPath: String,
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val colors = themeColors()
    val songs by viewModel.songs.observeAsState(emptyList())
    val hasHiRes = songs.any { it.isHiRes }

    LaunchedEffect(albumName, albumArtist) {
        viewModel.loadSongs(albumName, albumArtist)
    }

    fun playSongSafe(song: AudioFile) {
        if (song.path.isBlank()) return
        if (PlayerHolder.controller == null) {
            val activity = context as? com.rawsmusic.MainActivity
            if (activity != null) {
                activity.playerController ?: PlayerController.getInstance(context).also {
                    activity.playerController = it
                    PlayerHolder.controller = it
                }
            }
        }
        try { viewModel.playSong(song) } catch (_: Exception) {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Spacer(Modifier.height(44.dp))

        // 标题栏
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = colors.primary, fontSize = 14.sp)
            }
            Text(
                albumName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(44.dp))
        }

        // 专辑信息
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 专辑封面
            Box(
                Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surface)
            ) {
                if (coverPath.isNotBlank()) {
                    BitmapImage(
                        key = coverPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetWidth = 256,
                        targetHeight = 256
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
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasHiRes) {
                        Spacer(Modifier.width(6.dp))
                        Text("Hi-Res", fontSize = 10.sp, color = colors.primary, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    albumArtist,
                    fontSize = 14.sp,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 歌曲列表（Compose）
        ComposePowerListFull(
            songs = songs,
            onSongClick = { song, _ -> playSongSafe(song) },
            onSongLongClick = { _, _ -> }
        )
    }
}
