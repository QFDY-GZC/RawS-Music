package com.rawsmusic.ui.artists

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.ui.settings.themeColors

class ArtistsFragment : Fragment() {

    private val viewModel: ArtistsViewModel by viewModels()
    private lateinit var composeView: ComposeView
    
    // 用于从外部触发搜索的状态
    private var triggerSearch = mutableStateOf(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        return composeView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val navController = findNavController()
        composeView.setContent {
            ArtistsScreen(
                viewModel = viewModel,
                triggerSearch = triggerSearch.value,
                onSearchTriggered = { triggerSearch.value = false },
                onArtistClick = { artist ->
                    val bundle = Bundle().apply {
                        putString(ArtistDetailFragment.ARG_ARTIST_NAME, artist.name)
                    }
                    try {
                        navController.navigate(com.rawsmusic.R.id.action_global_artist_detail, bundle)
                    } catch (_: Exception) {}
                }
            )
        }
    }

    /** 从导航栏搜索按钮触发搜索 */
    fun enterSearch() {
        triggerSearch.value = true
    }
}

@Composable
fun ArtistsScreen(
    viewModel: ArtistsViewModel,
    triggerSearch: Boolean = false,
    onSearchTriggered: () -> Unit = {},
    onArtistClick: (Artist) -> Unit
) {
    val colors = themeColors()
    val artists by viewModel.artists.observeAsState(emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadArtists() }

    // 外部触发搜索
    LaunchedEffect(triggerSearch) {
        if (triggerSearch) {
            isSearchActive = true
            onSearchTriggered()
        }
    }

    val filteredArtists = if (searchQuery.isBlank()) {
        artists
    } else {
        val lowerQuery = searchQuery.lowercase()
        artists.filter { artist ->
            artist.name.lowercase().contains(lowerQuery)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // 标题栏
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearchActive) {
                // 搜索模式
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(colors.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(
                                fontSize = 15.sp,
                                color = colors.onSurface
                            ),
                            cursorBrush = SolidColor(colors.primary),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "搜索艺术家",
                                            fontSize = 15.sp,
                                            color = colors.outline
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "取消",
                            fontSize = 14.sp,
                            color = colors.primary,
                            modifier = Modifier.clickable {
                                isSearchActive = false
                                searchQuery = ""
                            }
                        )
                    }
                }
            } else {
                // 正常模式
                Text(
                    "艺术家",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    "搜索",
                    fontSize = 14.sp,
                    color = colors.primary,
                    modifier = Modifier.clickable { isSearchActive = true }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 艺术家列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (filteredArtists.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchQuery.isBlank()) "暂无艺术家" else "未找到匹配的艺术家",
                            fontSize = 14.sp,
                            color = colors.secondaryText
                        )
                    }
                }
            } else {
                items(
                    items = filteredArtists,
                    key = { it.name }
                ) { artist ->
                    ArtistRow(artist, colors, onArtistClick)
                }
            }

            item { Spacer(Modifier.height(160.dp)) }
        }
    }
}

@Composable
internal fun ArtistRow(
    artist: Artist,
    colors: com.rawsmusic.ui.settings.ThemeColors,
    onClick: (Artist) -> Unit
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick(artist) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverUri = artist.coverPath.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(coverUri)
                .crossfade(true)
                .build(),
            contentDescription = artist.name,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artist.name.ifBlank { "未知艺术家" },
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${artist.songCount} 首歌曲 · ${artist.albumCount} 张专辑",
                fontSize = 13.sp,
                color = colors.secondaryText
            )
        }
    }
}
