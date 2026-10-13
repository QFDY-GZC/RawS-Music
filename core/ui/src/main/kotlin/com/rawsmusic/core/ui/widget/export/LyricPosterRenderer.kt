package com.rawsmusic.core.ui.widget.export

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.flow.prepareRawBackgroundFrameRenderer
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.player.LyricColorSurface
import com.rawsmusic.core.ui.widget.player.rememberCoverAccentColor
import com.rawsmusic.core.ui.widget.player.rememberResolvedLyricColor
import android.media.Image
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

/** One poster line: the primary lyric plus its optional translation. */
internal data class LyricPosterLine(val text: String, val translation: String?)

internal data class LyricPosterRequest(
    val lines: List<LyricPosterLine>,
    val title: String,
    val artist: String,
    val coverKey: String?,
    val cover: Bitmap?,
    val size: LyricVideoSize,
    val simpleBackgroundArgb: Int,
    /** Background animation time used for the still frame. */
    val backgroundTimeSeconds: Float,
    val signature: String,
)

/** Render the poster into a software ARGB bitmap (suitable for PNG). Main thread only. */
internal suspend fun renderLyricPoster(activity: Activity, request: LyricPosterRequest): Bitmap? {
    val width = request.size.width
    val density = width / 360f
    val textWidth = (width - 60f * density).toInt().coerceAtLeast(1)
    fun measuredHeight(text: String, fontSp: Float, lineSp: Float, bold: Boolean): Int {
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = fontSp * density
            isFakeBoldText = bold
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
            .setIncludePad(false).build()
        // Leave room for font metrics that differ from StaticLayout's baseline calculations.
        return ceil(layout.lineCount * lineSp * density * 1.12f).toInt()
    }
    val bodyHeight = request.lines.sumOf { line ->
        measuredHeight(line.text, 25f, 33f, true) +
            (line.translation?.takeIf { it.isNotBlank() }?.let { (4 * density).toInt() + measuredHeight(it, 15f, 21f, false) } ?: 0)
    } + (request.lines.size - 1).coerceAtLeast(0) * (16 * density).toInt()
    val size = request.size.copy(height = LyricPosterPolicy.height(
        request.size.height, bodyHeight, (34 * density).toInt(), (32 * density).toInt(),
        (54 * density).toInt(), (34 * density).toInt(),
    ))
    val background = prepareRawBackgroundFrameRenderer(
        context = activity,
        coverKey = request.coverKey,
        artwork = request.cover,
        simpleBackgroundArgb = request.simpleBackgroundArgb,
    )
    val coverImage = request.cover?.takeUnless { it.isRecycled }?.asImageBitmap()
    val host = OffscreenComposeFrameHost(activity, size.width, size.height) {
        LyricPosterContent(request, coverImage, size.width, size.height)
    }
    val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
    try {
        // A few frames let images, text layout and effects settle before the still is taken.
        repeat(POSTER_SETTLE_FRAMES) { frame -> host.advanceFrame(1_000_000_000L + frame * 16_666_667L) }
        // Register before posting: the frame can become available before this coroutine resumes.
        val available = CompletableDeferred<Image?>()
        reader.setOnImageAvailableListener({ source ->
            val acquired = runCatching { source.acquireLatestImage() }.getOrNull()
            source.setOnImageAvailableListener(null, null)
            if (!available.complete(acquired)) acquired?.close()
        }, Handler(Looper.getMainLooper()))
        val canvas = reader.surface.lockHardwareCanvas()
        try {
            background.drawFrame(canvas, size.width, size.height, request.backgroundTimeSeconds)
            host.draw(canvas)
        } finally {
            reader.surface.unlockCanvasAndPost(canvas)
        }
        val image = withTimeoutOrNull(POSTER_IMAGE_TIMEOUT_MS) { available.await() } ?: return null
        image.use {
            val plane = it.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth = rowStride / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, size.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == size.width) return padded
            val cropped = Bitmap.createBitmap(padded, 0, 0, size.width, size.height)
            padded.recycle()
            return cropped
        }
    } finally {
        host.release()
        reader.close()
    }
}

@Composable
private fun LyricPosterContent(
    request: LyricPosterRequest,
    cover: ImageBitmap?,
    widthPx: Int,
    heightPx: Int,
) {
    LyricExportDensity(widthPx, heightPx) {
        val dark = ThemeManager.isDarkMode(LocalContext.current)
        val foreground = if (usesReferenceStaticForeground() || dark) Color.White else Color(0xFF1B1B20)
        val accent = rememberCoverAccentColor(request.coverKey)
        val lyricColor = rememberResolvedLyricColor(
            surface = LyricColorSurface.LYRIC_PAGE,
            fallbackPrimary = foreground,
            albumAccent = accent,
        )
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 34.dp)) {
            Spacer(Modifier.weight(1f))
            if (request.lines.isEmpty()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ExportCover(cover, 200.dp)
                }
                Spacer(Modifier.weight(1f))
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                request.lines.forEach { line ->
                    Column {
                        Text(
                            text = line.text,
                            color = lyricColor.primary,
                            fontSize = 25.sp,
                            lineHeight = 33.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = translation,
                                color = lyricColor.secondary,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExportCover(cover, 54.dp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    ExportTitle(request.title, request.artist, foreground, centered = false)
                }
                if (request.signature.isNotBlank()) Text(
                    text = request.signature,
                    color = foreground.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private const val POSTER_SETTLE_FRAMES = 3
private const val POSTER_IMAGE_TIMEOUT_MS = 3_000L
