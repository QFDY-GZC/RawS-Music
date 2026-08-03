package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadStatus
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadTask
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SourceDownloadsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val tasks by MusicSourceDownloadController.tasks.collectAsState()
    val ordered = tasks.sortedWith(
        compareByDescending<MusicSourceDownloadTask> { it.isActive }
            .thenByDescending { it.updatedAtMs }
    )

    LaunchedEffect(Unit) {
        MusicSourceDownloadController.initialize(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SourceSectionTopBar(stringResource(R.string.source_download_title), onBack)
        if (ordered.isEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(4.dp))
                SourceEmptyPanel(
                    title = stringResource(R.string.source_download_empty),
                    summary = stringResource(R.string.source_download_empty_summary),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 174.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "download-summary") {
                    DownloadSummaryHeader(
                        activeCount = ordered.count { it.isActive },
                        finishedCount = ordered.count { !it.isActive },
                        onClearFinished = MusicSourceDownloadController::clearFinished,
                    )
                }
                items(ordered, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onCancel = { MusicSourceDownloadController.cancel(task.id) },
                        onRetry = { MusicSourceDownloadController.retry(context, task.id) },
                        onRemove = { MusicSourceDownloadController.removeRecord(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadSummaryHeader(
    activeCount: Int,
    finishedCount: Int,
    onClearFinished: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.source_online_download), color = scheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.source_download_summary, activeCount, finishedCount),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
        }
        if (finishedCount > 0) {
            TextButton(
                text = stringResource(R.string.source_clear_records),
                onClick = onClearFinished,
            )
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: MusicSourceDownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.74f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceArtwork(
                item = task.item,
                localPath = null,
                targetSize = 256,
                cornerRadiusDp = 15,
                modifier = Modifier.size(58.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.item.title,
                    color = scheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    task.item.artists.joinToString(" / ").ifBlank { stringResource(R.string.source_unknown_singer) },
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(task.requestedQuality.sourceQualityLabel())
                        if (task.resolvedQuality != task.requestedQuality) {
                            append(" · 实际 ${task.resolvedQuality.sourceQualityLabel()}")
                        }
                        append(" · ${task.status.downloadStatusLabel()}")
                    },
                    color = task.status.downloadStatusColor(scheme.primary, scheme.onSurfaceVariantSummary),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            DownloadTaskAction(task, onCancel, onRetry, onRemove)
        }

        Spacer(Modifier.height(11.dp))
        when (task.status) {
            MusicSourceDownloadStatus.Resolving -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.source_resolving_download), color = scheme.onSurfaceVariantSummary, fontSize = 11.sp)
                }
            }
            MusicSourceDownloadStatus.Queued,
            MusicSourceDownloadStatus.Downloading,
            MusicSourceDownloadStatus.Paused -> {
                LinearProgressIndicator(
                    progress = task.progressFraction,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(7.dp))
                Row {
                    Text(
                        if (task.totalBytes > 0L) {
                            "${formatBytes(task.bytesDownloaded)} / ${formatBytes(task.totalBytes)}"
                        } else {
                            formatBytes(task.bytesDownloaded)
                        },
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (task.totalBytes > 0L) {
                        Text(
                            "${(task.progressFraction * 100f).toInt()}%",
                            color = scheme.primary,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            MusicSourceDownloadStatus.Completed -> {
                Text(
                    stringResource(R.string.source_saved_file, task.fileName.ifBlank { stringResource(R.string.source_audio_file) }),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.error.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        task.error,
                        color = Color(0xFFE3A13B),
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
            MusicSourceDownloadStatus.Failed,
            MusicSourceDownloadStatus.Cancelled -> {
                Text(
                    task.error.ifBlank { task.status.downloadStatusLabel() },
                    color = Color(0xFFE15B64),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskAction(
    task: MusicSourceDownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val action = when {
        task.isActive -> DownloadTaskUiAction(
            stringResource(R.string.source_download_action_cancel),
            R.drawable.ic_close,
            onCancel,
            true,
        )
        task.status == MusicSourceDownloadStatus.Failed || task.status == MusicSourceDownloadStatus.Cancelled ->
            DownloadTaskUiAction(stringResource(R.string.source_download_action_retry), R.drawable.ic_retry, onRetry, false)
        else -> DownloadTaskUiAction(stringResource(R.string.source_download_action_remove), R.drawable.ic_delete, onRemove, false)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = action.onClick, modifier = Modifier.size(42.dp)) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = action.label,
                tint = if (action.destructive) Color(0xFFE15B64) else scheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            text = action.label,
            color = if (action.destructive) Color(0xFFE15B64) else scheme.onSurfaceVariantSummary,
            fontSize = 10.sp,
        )
    }
}

private data class DownloadTaskUiAction(
    val label: String,
    val iconRes: Int,
    val onClick: () -> Unit,
    val destructive: Boolean,
)

@Composable
private fun MusicSourceDownloadStatus.downloadStatusLabel(): String = when (this) {
    MusicSourceDownloadStatus.Resolving -> stringResource(R.string.source_download_status_resolving)
    MusicSourceDownloadStatus.Queued -> stringResource(R.string.source_download_status_queued)
    MusicSourceDownloadStatus.Downloading -> stringResource(R.string.source_download_status_downloading)
    MusicSourceDownloadStatus.Paused -> stringResource(R.string.source_download_status_paused)
    MusicSourceDownloadStatus.Completed -> stringResource(R.string.source_download_status_completed)
    MusicSourceDownloadStatus.Failed -> stringResource(R.string.source_download_status_failed)
    MusicSourceDownloadStatus.Cancelled -> stringResource(R.string.source_download_status_cancelled)
}

private fun MusicSourceDownloadStatus.downloadStatusColor(primary: Color, secondary: Color): Color = when (this) {
    MusicSourceDownloadStatus.Completed -> primary
    MusicSourceDownloadStatus.Failed, MusicSourceDownloadStatus.Cancelled -> Color(0xFFE15B64)
    else -> secondary
}

private fun formatBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var amount = value.toDouble()
    var index = 0
    while (amount >= 1024.0 && index < units.lastIndex) {
        amount /= 1024.0
        index++
    }
    return if (index == 0) "${amount.toLong()} ${units[index]}"
    else String.format(Locale.ROOT, "%.1f %s", amount, units[index])
}
