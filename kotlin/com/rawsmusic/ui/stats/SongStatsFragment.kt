package com.rawsmusic.ui.stats

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.ui.settings.Divider
import com.rawsmusic.ui.settings.SectionHeader
import com.rawsmusic.ui.settings.themeColors
import com.rawsmusic.core.ui.theme.ThemeManager

class SongStatsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SongStatsScreen(
                    onBack = {
                        try {
                            NavHostFragment.findNavController(this@SongStatsFragment).navigateUp()
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }
}

private data class StatsItem(
    val label: String,
    val count: Int,
    val percentage: Float,
    val color: Color,
    val description: String
)

private fun isLossless(song: AudioFile): Boolean {
    val fmt = song.format.ifBlank { song.encodingFormat.ifBlank { song.extension } }
    return fmt.equals("FLAC", true) || fmt.equals("WAV", true) ||
            fmt.equals("AIFF", true) || fmt.equals("ALAC", true) ||
            fmt.equals("APE", true) || fmt.equals("OGGFLAC", true)
}

private fun isMaster(song: AudioFile): Boolean {
    val fmt = song.format.ifBlank { song.encodingFormat.ifBlank { song.extension } }
    return fmt.startsWith("DSD", ignoreCase = true) ||
            fmt.equals("DSF", true) || fmt.equals("DFF", true) ||
            (song.bitsPerSample >= 24 && song.sampleRate >= 48000) ||
            song.sampleRate > 48000
}

private fun buildStats(songs: List<AudioFile>): List<StatsItem> {
    val total = songs.size
    if (total == 0) return emptyList()

    val lossy = songs.count { !isLossless(it) && !isMaster(it) }
    val lossless = songs.count { isLossless(it) && !isMaster(it) }
    val master = songs.count { isMaster(it) }

    return listOf(
        StatsItem(
            label = "\u6709\u635f",
            count = lossy,
            percentage = lossy.toFloat() / total * 100f,
            color = Color(0xFF7D5260),
            description = "MP3 / AAC / OGG \u7b49\u538b\u7f29\u97f3\u9891"
        ),
        StatsItem(
            label = "\u65e0\u635f",
            count = lossless,
            percentage = lossless.toFloat() / total * 100f,
            color = Color(0xFF6750A4),
            description = "FLAC / WAV / ALAC / APE \u7b49 CD \u89c4\u683c\u65e0\u635f"
        ),
        StatsItem(
            label = "\u6bcd\u5e26",
            count = master,
            percentage = master.toFloat() / total * 100f,
            color = Color(0xFFB3261E),
            description = "Hi-Res / DSD / 24bit \u6216\u9ad8\u91c7\u6837\u7387\u97f3\u9891"
        )
    )
}

@Composable
fun SongStatsScreen(onBack: () -> Unit) {
    var stats by remember { mutableStateOf<List<StatsItem>>(emptyList()) }
    var totalSongs by remember { mutableStateOf(0) }
    val colors = themeColors()
    val isDark = ThemeManager.isDarkMode(LocalContext.current)

    LaunchedEffect(Unit) {
        val songs = MusicRepository.getAllSongs()
        totalSongs = songs.size
        stats = buildStats(songs)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF1A1A1A) else Color.White)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = colors.primary, fontSize = 16.sp)
            }
            Text(
                "歌曲统计",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        if (stats.isNotEmpty()) {
            SectionHeader("音质统计")
            Text(
                "共 $totalSongs 首歌曲",
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(16.dp))
            DonutChart(
                items = stats,
                isDark = isDark,
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.CenterHorizontally)
            )

            Divider()

            stats.forEach { item ->
                QualityStatsRow(item, isDark)
            }
        } else {
            SectionHeader("音质统计")
            Spacer(Modifier.height(12.dp))
            Text(
                "暂无可统计的歌曲",
                fontSize = 14.sp,
                color = colors.secondaryText
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun QualityStatsRow(item: StatsItem, isDark: Boolean) {
    val colors = themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .drawBehind {
                    drawCircle(color = item.color.copy(alpha = 0.18f))
                    drawCircle(color = item.color, radius = 5.dp.toPx())
                }
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface
                )
                Text(
                    "${item.count} 首",
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.description,
                fontSize = 12.sp,
                color = colors.secondaryText
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .drawBehind {
                        val barWidth = size.width * (item.percentage / 100f)
                        drawRoundRect(
                            color = item.color.copy(alpha = 0.18f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                        )
                        drawRoundRect(
                            color = item.color,
                            size = Size(barWidth, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                        )
                    }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${String.format("%.1f", item.percentage)}%",
                fontSize = 12.sp,
                color = colors.secondaryText,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun DonutChart(
    items: List<StatsItem>,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = themeColors()
    val totalAngle = 360f
    var currentAngle = -90f

    Box(
        modifier = modifier.drawBehind {
            val strokeWidth = 28.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            for (item in items) {
                val sweepAngle = totalAngle * (item.percentage / 100f)
                drawArc(
                    color = item.color,
                    startAngle = currentAngle,
                    sweepAngle = sweepAngle - 2f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                currentAngle += sweepAngle
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "3 类",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Text(
                "音质",
                fontSize = 12.sp,
                color = colors.secondaryText
            )
        }
    }
}
