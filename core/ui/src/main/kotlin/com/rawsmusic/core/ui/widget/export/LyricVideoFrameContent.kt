package com.rawsmusic.core.ui.widget.export

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.player.ComposeLyricView
import com.rawsmusic.core.ui.widget.player.LocalLyricExportTimeline
import com.rawsmusic.core.ui.widget.player.LyricColorSurface
import com.rawsmusic.core.ui.widget.player.LyricTextPosition
import com.rawsmusic.core.ui.widget.player.rememberCoverAccentColor
import com.rawsmusic.core.ui.widget.player.rememberResolvedLyricColor
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.lyric.model.Song
import kotlin.math.min

/**
 * Design width, in dp, of the shorter edge of every exported frame. Pinning the density to this
 * makes a 720p and a 1080p export share one layout (only sharper), independent of the phone.
 */
internal const val LYRIC_EXPORT_SHORT_EDGE_DP = 360f

@Composable
internal fun LyricExportDensity(
    widthPx: Int,
    heightPx: Int,
    content: @Composable () -> Unit,
) {
    val density = Density(
        density = min(widthPx, heightPx) / LYRIC_EXPORT_SHORT_EDGE_DP,
        fontScale = 1f,
    )
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalUiFrameAnimationActive provides true,
        LocalLyricExportTimeline provides true,
        content = content,
    )
}

/**
 * One lyric-video frame (the flow background is drawn separately, underneath).
 *
 * The lyric body is the real [ComposeLyricView] with the same colour/font/effect preferences as the
 * lyric page, so rise, active-line highlight and per-word karaoke fill are the shipped effects,
 * advanced by the exporter's frame clock through [LocalLyricExportTimeline].
 */
@Composable
internal fun LyricVideoFrameContent(
    song: Song?,
    positionMs: Long,
    title: String,
    artist: String,
    coverKey: String?,
    cover: ImageBitmap?,
    widthPx: Int,
    heightPx: Int,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    signature: String,
) {
    LyricExportDensity(widthPx, heightPx) {
        val dark = ThemeManager.isDarkMode(LocalContext.current)
        val headerTextColor = if (usesReferenceStaticForeground() || dark) Color.White else Color(0xFF1B1B20)
        val accent = rememberCoverAccentColor(coverKey)
        val lyricColor = rememberResolvedLyricColor(
            surface = LyricColorSurface.LYRIC_PAGE,
            fallbackPrimary = headerTextColor,
            albumAccent = accent,
        )
        val hasLyrics = !song?.lyrics.isNullOrEmpty()
        val landscape = widthPx > heightPx

        @Composable
        fun Lyrics(modifier: Modifier, topPadding: Dp, bottomPadding: Dp) {
            ComposeLyricView(
                song = song,
                positionMs = positionMs,
                isPlaying = true,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                textColor = lyricColor.primary,
                dimColor = lyricColor.dim,
                secondaryColor = lyricColor.secondary,
                rainbowText = lyricColor.rainbow,
                fontSizeSp = AppPreferences.UI.lyricFontSizeSp,
                textPosition = LyricTextPosition.from(AppPreferences.UI.lyricTextPosition),
                blurEnabled = AppPreferences.UI.lyricBlurEnabled,
                highlightAll = AppPreferences.UI.lyricHighlightAllEnabled,
                karaokeGlowEnabled = AppPreferences.UI.lyricKaraokeGlowEnabled,
                karaokeLiftEnabled = AppPreferences.UI.lyricKaraokeLiftEnabled,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
                interactionEnabled = false,
                zoomGestureEnabled = false,
                swipeRightGestureEnabled = false,
                modifier = modifier,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(0.40f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ExportCover(cover, 170.dp)
                        Spacer(Modifier.height(14.dp))
                        ExportTitle(title, artist, headerTextColor, centered = true)
                    }
                    Spacer(Modifier.width(18.dp))
                    if (hasLyrics) {
                        Lyrics(Modifier.weight(0.60f).fillMaxHeight(), topPadding = 120.dp, bottomPadding = 120.dp)
                    } else {
                        Spacer(Modifier.weight(0.60f))
                    }
                }
            } else if (hasLyrics) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExportCover(cover, 58.dp)
                        Spacer(Modifier.width(14.dp))
                        ExportTitle(title, artist, headerTextColor, centered = false)
                    }
                    val lyricTop = (heightPx / (min(widthPx, heightPx) / LYRIC_EXPORT_SHORT_EDGE_DP) * 0.22f).dp
                    Lyrics(Modifier.fillMaxWidth().weight(1f), topPadding = lyricTop, bottomPadding = 140.dp)
                }
            } else {
                // No lyrics: the selected time range plays over the artwork itself.
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ExportCover(cover, 240.dp)
                    Spacer(Modifier.height(22.dp))
                    ExportTitle(title, artist, headerTextColor, centered = true)
                }
            }
            if (signature.isNotBlank()) Text(
                text = signature,
                color = headerTextColor.copy(alpha = 0.42f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
internal fun ExportCover(cover: ImageBitmap?, side: Dp) {
    val shape = RoundedCornerShape(side * 0.08f)
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(side).clip(shape),
        )
    } else {
        Box(Modifier.size(side).clip(shape).background(Color.White.copy(alpha = 0.12f)))
    }
}

@Composable
internal fun ExportTitle(title: String, artist: String, color: Color, centered: Boolean) {
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            text = title,
            color = color,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (artist.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = artist,
                color = color.copy(alpha = 0.70f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
