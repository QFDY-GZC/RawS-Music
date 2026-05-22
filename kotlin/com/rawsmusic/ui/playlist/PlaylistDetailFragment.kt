package com.rawsmusic.ui.playlist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.R
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.ui.songs.PlayerHolder
import com.rawsmusic.ui.settings.SettingsPage
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.launch

class PlaylistDetailFragment : Fragment() {

    private val playlistStore by lazy { PlaylistStore.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val playlistId = arguments?.getString("playlistId") ?: ""
        val playlistName = arguments?.getString("playlistName") ?: "歌单"

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val playlists by playlistStore.playlists.collectAsState()
                val playlist = playlists.find { it.id == playlistId }
                val scope = rememberCoroutineScope()

                PlaylistDetailScreen(
                    playlistName = playlist?.name ?: playlistName,
                    songs = playlist?.songs ?: emptyList(),
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@PlaylistDetailFragment).navigateUp()
                        } catch (_: Exception) {}
                    },
                    onPlaySong = { song ->
                        playSong(song)
                    },
                    onRemoveSong = { song ->
                        scope.launch {
                            playlistStore.removeSongFromPlaylist(playlistId, song.key)
                        }
                    }
                )
            }
        }
    }

    private fun playSong(song: UserPlaylistSong) {
        val controller = PlayerHolder.controller ?: return
        val audioFile = song.toAudioFile()
        if (audioFile.path.isBlank()) {
            Toast.makeText(requireContext(), "无法播放: ${song.title}", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            controller.play(audioFile)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法播放: ${song.title}", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun PlaylistDetailScreen(
    playlistName: String,
    songs: List<UserPlaylistSong>,
    onBack: () -> Unit,
    onPlaySong: (UserPlaylistSong) -> Unit,
    onRemoveSong: (UserPlaylistSong) -> Unit
) {
    val colors = themeColors()

    SettingsPage(title = playlistName, onBack = onBack) {
        if (songs.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "歌单暂无歌曲",
                    fontSize = 14.sp,
                    color = colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(24.dp),
                    fontFamily = appFontFamily()
                )
            }
        }

        songs.forEachIndexed { index, song ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPlaySong(song) },
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        fontSize = 13.sp,
                        color = colors.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.width(28.dp),
                        fontWeight = FontWeight.Medium,
                        fontFamily = appFontFamily()
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            song.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = appFontFamily()
                        )
                        Text(
                            song.artist,
                            fontSize = 12.sp,
                            color = colors.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = appFontFamily()
                        )
                    }
                    IconButton(
                        onClick = { onRemoveSong(song) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_bin_6_fill),
                            contentDescription = "移除",
                            tint = colors.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}
