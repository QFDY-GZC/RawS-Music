package com.rawsmusic.ui.analytics

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.module.data.prefs.PlaybackStatsStore
import com.rawsmusic.ui.settings.SettingsPage
import com.rawsmusic.ui.settings.ThemeColors
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AnalyticsFragment : Fragment() {

    private val statsStore by lazy { PlaybackStatsStore.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AnalyticsScreen(
                    statsStore = statsStore,
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@AnalyticsFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

@Composable
fun AnalyticsScreen(
    statsStore: PlaybackStatsStore,
    onBack: () -> Unit
) {
    val stats by statsStore.stats.collectAsState()
    val history by statsStore.history.collectAsState()
    val dailyMs by statsStore.dailyListenMs.collectAsState()
    val colors = themeColors()

    val totalPlays = stats.sumOf { it.playCount }
    val totalListenMs = stats.sumOf { it.listenedMs }
    val totalListenMin = totalListenMs / 60_000L

    SettingsPage(title = "听歌统计", onBack = onBack) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("播放次数", "$totalPlays", colors)
                StatItem("听歌时长", "${totalListenMin}分钟", colors)
                StatItem("歌曲数", "${stats.size}", colors)
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle("听歌热力图（近8周）", colors)
        HeatmapCard(dailyMs, colors)

        Spacer(Modifier.height(8.dp))
        SectionTitle("播放次数排行", colors)
        stats.sortedByDescending { it.playCount }.take(10).forEachIndexed { index, item ->
            RankingRow(
                rank = index + 1,
                title = item.title,
                subtitle = item.artist,
                value = "${item.playCount}次",
                colors = colors
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle("听歌时长排行", colors)
        stats.sortedByDescending { it.listenedMs }.take(10).forEachIndexed { index, item ->
            RankingRow(
                rank = index + 1,
                title = item.title,
                subtitle = item.artist,
                value = "${item.listenedMs / 60_000L}分钟",
                colors = colors
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle("最近播放", colors)
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        history.take(20).forEach { entry ->
            RankingRow(
                rank = 0,
                title = entry.title,
                subtitle = entry.artist,
                value = sdf.format(entry.playedAt),
                colors = colors
            )
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun StatItem(label: String, value: String, colors: ThemeColors) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onSurface, fontFamily = appFontFamily())
        Text(label, fontSize = 12.sp, color = colors.onSurface.copy(alpha = 0.5f), fontFamily = appFontFamily())
    }
}

@Composable
private fun SectionTitle(title: String, colors: ThemeColors) {
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
        modifier = Modifier.padding(vertical = 8.dp),
        fontFamily = appFontFamily()
    )
}

@Composable
private fun RankingRow(
    rank: Int,
    title: String,
    subtitle: String,
    value: String,
    colors: ThemeColors
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rank > 0) {
                Text(
                    "$rank",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (rank <= 3) colors.primary else colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.width(28.dp),
                    fontFamily = appFontFamily()
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = appFontFamily())
                Text(subtitle, fontSize = 12.sp, color = colors.onSurface.copy(alpha = 0.5f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = appFontFamily())
            }
            Text(value, fontSize = 13.sp, color = colors.onSurface.copy(alpha = 0.6f), fontFamily = appFontFamily())
        }
    }
}

@Composable
private fun HeatmapCard(
    dailyMs: Map<String, Long>,
    colors: ThemeColors
) {
    val cal = Calendar.getInstance()
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val days = mutableListOf<Pair<String, Long>>()
    for (i in 55 downTo 0) {
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, -i)
        val key = sdf.format(cal.time)
        days.add(key to (dailyMs[key] ?: 0L))
    }

    val maxMs = days.maxOf { it.second }.coerceAtLeast(1L)

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val weeks = days.chunked(7)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                weeks.forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        week.forEach { (_, ms) ->
                            val ratio = (ms.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)
                            val cellColor = when {
                                ratio == 0f -> colors.onSurface.copy(alpha = 0.06f)
                                ratio < 0.25f -> colors.primary.copy(alpha = 0.2f)
                                ratio < 0.5f -> colors.primary.copy(alpha = 0.4f)
                                ratio < 0.75f -> colors.primary.copy(alpha = 0.65f)
                                else -> colors.primary.copy(alpha = 0.9f)
                            }
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(cellColor)
                            )
                        }
                    }
                }
            }
        }
    }
}
