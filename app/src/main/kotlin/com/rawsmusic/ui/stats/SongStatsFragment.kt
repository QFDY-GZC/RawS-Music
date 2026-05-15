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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.ui.settings.LiquidGlassCard

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
            label = "有损",
            count = lossy,
            percentage = lossy.toFloat() / total * 100f,
            color = Color(0xFF7D5260),
            description = "MP3 / AAC / OGG 等压缩音频"
        ),
        StatsItem(
            label = "无损",
            count = lossless,
            percentage = lossless.toFloat() / total * 100f,
            color = Color(0xFF6750A4),
            description = "FLAC / WAV / ALAC / APE 等 CD 规格无损"
        ),
        StatsItem(
            label = "母带",
            count = master,
            percentage = master.toFloat() / total * 100f,
            color = Color(0xFFB3261E),
            description = "Hi-Res / DSD / 24bit 或高采样率音频"
        )
    )
}

@Composable
fun SongStatsScreen(onBack: () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    var stats by remember { mutableStateOf<List<StatsItem>>(emptyList()) }
    var totalSongs by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val songs = MusicRepository.getAllSongs()
        totalSongs = songs.size
        stats = buildStats(songs)
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
                    "歌曲统计",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1B1F)
                )
            }

            Spacer(Modifier.height(8.dp))

            if (stats.isNotEmpty()) {
                LiquidGlassCard(backdrop) {
                    Text(
                        "音质统计",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1C1B1F)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "共 $totalSongs 首歌曲",
                        fontSize = 14.sp,
                        color = Color(0xFF79747E)
                    )
                    Spacer(Modifier.height(18.dp))
                    DonutChart(
                        items = stats,
                        modifier = Modifier
                            .size(200.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }

                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    stats.forEach { item ->
                        QualityStatsCard(backdrop, item)
                    }
                }
            } else {
                LiquidGlassCard(backdrop) {
                    Text(
                        "暂无可统计的歌曲",
                        fontSize = 14.sp,
                        color = Color(0xFF79747E),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QualityStatsCard(
    backdrop: com.kyant.backdrop.Backdrop,
    item: StatsItem
) {
    LiquidGlassCard(backdrop) {
        Row(
            Modifier.fillMaxWidth(),
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
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1C1B1F)
                    )
                    Text(
                        "${item.count} 首",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF49454F)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    item.description,
                    fontSize = 12.sp,
                    color = Color(0xFF79747E)
                )
                Spacer(Modifier.height(10.dp))
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
                Spacer(Modifier.height(4.dp))
                Text(
                    "${String.format("%.1f", item.percentage)}%",
                    fontSize = 12.sp,
                    color = Color(0xFF79747E),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun DonutChart(
    items: List<StatsItem>,
    modifier: Modifier = Modifier
) {
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
                color = Color(0xFF1C1B1F)
            )
            Text(
                "音质",
                fontSize = 12.sp,
                color = Color(0xFF79747E)
            )
        }
    }
}
