package com.rawsmusic.ui.artists

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.NavHostFragment
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.ui.settings.LiquidGlassCard
import com.rawsmusic.ui.songs.PlayerHolder
import com.rawsmusic.module.player.PlayerController

class ArtistDetailFragment : Fragment() {

    private val viewModel: ArtistDetailViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val artistName = arguments?.getString(ARG_ARTIST_NAME) ?: ""

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ArtistDetailScreen(
                    artistName = artistName,
                    viewModel = viewModel,
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@ArtistDetailFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }

    companion object {
        const val ARG_ARTIST_NAME = "arg_artist_name"

        fun newInstance(artistName: String): ArtistDetailFragment {
            return ArtistDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ARTIST_NAME, artistName)
                }
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    artistName: String,
    viewModel: ArtistDetailViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()
    val songs by viewModel.songs.observeAsState(emptyList())
    val albums by viewModel.albums.observeAsState(emptyList())

    LaunchedEffect(artistName) {
        viewModel.loadArtistData(artistName)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF2F1F0),
                            Color(0xFFE8E7E5),
                            Color(0xFFD1D0CD).copy(alpha = 0.3f),
                            Color(0xFFB0AFA8).copy(alpha = 0.1f)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← 返回", color = Color(0xFF6750A4), fontSize = 16.sp)
                }
                Text(
                    artistName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            LiquidGlassCard(backdrop) {
                Text(artistName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1B1F))
                Spacer(Modifier.height(4.dp))
                Text(
                    "${songs.size} 首歌曲 · ${albums.size} 张专辑",
                    fontSize = 14.sp, color = Color(0xFF79747E)
                )
            }

            if (albums.isNotEmpty()) {
                LiquidGlassCard(backdrop) {
                    Text("专辑", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                    Spacer(Modifier.height(12.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(albums) { album ->
                            AlbumItem(album)
                        }
                    }
                }
            }

            if (songs.isNotEmpty()) {
                LiquidGlassCard(backdrop) {
                    Text("歌曲", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B1F))
                    Spacer(Modifier.height(8.dp))

                    for (song in songs.take(20)) {
                        TextButton(
                            onClick = {
                                if (song.path.isBlank()) return@TextButton
                                try {
                                    viewModel.playSong(song)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    song.displayName,
                                    fontSize = 14.sp,
                                    color = Color(0xFF1C1B1F),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    song.album.ifBlank { "未知专辑" },
                                    fontSize = 12.sp,
                                    color = Color(0xFF79747E),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (songs.size > 20) {
                        Spacer(Modifier.height(4.dp))
                        Text("还有 ${songs.size - 20} 首…", fontSize = 12.sp, color = Color(0xFF79747E))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AlbumItem(album: Album) {
    val coverSize = 120.dp
    Column(
        modifier = Modifier.width(coverSize),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val coverUri = album.coverPath.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(coverUri)
                .crossfade(true)
                .build(),
            contentDescription = album.name,
            modifier = Modifier
                .size(coverSize)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(4.dp))
        Text(
            album.name.ifBlank { "未知专辑" },
            fontSize = 12.sp,
            color = Color(0xFF1C1B1F),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (album.year > 0) {
            Text(
                "${album.year}",
                fontSize = 11.sp,
                color = Color(0xFF79747E)
            )
        }
    }
}
