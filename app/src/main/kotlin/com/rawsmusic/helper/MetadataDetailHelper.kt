package com.rawsmusic.helper

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isDsdSourceFile
import com.rawsmusic.core.common.model.isLossyCodec
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.module.player.PlayerController
import kotlin.math.abs

class MetadataDetailHelper(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val getPlayerController: () -> PlayerController?,
    private val setGestureInterceptDisabled: (Boolean) -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    var isVisible by mutableStateOf(false)
        private set

    var entries by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    fun setup() = Unit

    fun open() {
        entries = buildEntries(getPlayerController()?.currentSong?.value)
        isVisible = true
        setGestureInterceptDisabled(true)
        onVisibilityChanged(true)
    }

    fun close() {
        isVisible = false
        setGestureInterceptDisabled(false)
        onVisibilityChanged(false)
    }

    private fun buildEntries(song: AudioFile?): List<Pair<String, String>> {
        song ?: return emptyList()
        val usbSr = getPlayerController()?.getUsbOutputSampleRate() ?: 0
        val srDisplay = when {
            usbSr > 0 && song.sampleRate != usbSr -> "${song.sampleRate} Hz -> ${usbSr} Hz (DAC)"
            usbSr > 0 -> "${usbSr} Hz (DAC)"
            song.sampleRate > 0 -> "${song.sampleRate} Hz"
            else -> "未知"
        }
        return listOf(
            "标题" to song.displayName,
            "艺术家" to song.artist,
            "专辑" to song.album,
            "流派" to song.genre,
            "作曲" to song.composer,
            "年份" to (if (song.year > 0) song.year.toString() else ""),
            "音轨" to (if (song.trackNumber > 0) song.trackNumber.toString() else ""),
            "时长" to AudioUtils.formatDuration(song.duration),
            "码率" to com.rawsmusic.core.common.utils.BitrateNormalizer.formatKbps(song.bitRate, song.duration, song.fileSize),
            "采样率" to srDisplay,
            "位深" to formatBitDepth(song),
            "格式" to song.format.ifBlank { song.extension },
            "文件大小" to formatFileSize(song.fileSize),
            "文件路径" to song.path
        )
    }

    private fun formatBitDepth(song: com.rawsmusic.core.common.model.AudioFile): String {
        val bits = song.bitsPerSample

        if (song.isDsdSourceFile()) {
            return "1 bit (DSD)"
        }

        if (bits > 0) {
            return when {
                song.encodingFormat.contains("FLOAT", true) && bits == 32 -> "32 bit (Float)"
                song.encodingFormat.contains("FLOAT", true) && bits == 64 -> "64 bit (Float)"
                song.encodingFormat.contains("FLOAT", true) -> "$bits bit (Float)"
                else -> "$bits bit"
            }
        }

        return if (song.isLossyCodec()) "有损编码" else "未知"
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
            size >= 1024 -> "%.1f KB".format(size / 1024.0)
            else -> "$size B"
        }
    }
}

@Composable
fun MetadataDetailOverlay(
    helper: MetadataDetailHelper,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = helper.isVisible,
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(320)) { it },
        exit = fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it },
        modifier = modifier.fillMaxSize()
    ) {
        var dragX by remember { mutableStateOf(0f) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6171412))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            if (dragX > size.width * 0.3f) helper.close()
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                        onDrag = { change, dragAmount ->
                            if (dragAmount.x > 0f && abs(dragAmount.x) > abs(dragAmount.y)) {
                                dragX += dragAmount.x
                                change.consume()
                            }
                        }
                    )
                }
                .padding(horizontal = 24.dp)
                .padding(top = 54.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { helper.close() },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Text("<", color = Color(0xB0FFFFFF), fontSize = 22.sp)
                    }
                    Text(
                        text = "元数据",
                        color = Color(0xFF4CAF50),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 30.dp)
                ) {
                    helper.entries.forEach { (label, value) ->
                        MetadataRow(label = label, value = value.ifBlank { "未知" })
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        Text(
            text = value,
            color = Color(0xE6F0EBE8),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
    }
}
