package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.PlayerHiResBadgeCorner
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.max

/** Decode an imported badge at a deliberately small ceiling; the badge is a watermark, not artwork. */
internal fun decodePlayerHiResBadgeBitmap(path: String, maxSidePx: Int = 256): Bitmap? {
    if (path.isBlank()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / sample > maxSidePx * 2) sample *= 2
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }.getOrNull()?.takeUnless { it.isRecycled }
}

internal fun PlayerHiResBadgeCorner.composeAlignment(): Alignment = when (this) {
    PlayerHiResBadgeCorner.TOP_LEFT -> Alignment.TopStart
    PlayerHiResBadgeCorner.TOP_RIGHT -> Alignment.TopEnd
    PlayerHiResBadgeCorner.BOTTOM_LEFT -> Alignment.BottomStart
    PlayerHiResBadgeCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
}

/**
 * A child of one physical artwork holder. The holder owns all pager transforms; the standard player
 * owns the outer play/pause scale. Keeping the watermark here makes both motions automatic.
 */
@Composable
internal fun PlayerHiResBadgeOverlay(
    corner: PlayerHiResBadgeCorner,
    customBitmap: Bitmap?,
    contentInset: Dp,
    contentScaleFactor: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentInset)
            .graphicsLayer {
                scaleX = contentScaleFactor
                scaleY = contentScaleFactor
            },
    ) {
        val alignment = corner.composeAlignment()
        val badgeModifier = Modifier
            .align(alignment)
            .padding(12.dp)
        if (customBitmap != null && !customBitmap.isRecycled) {
            Image(
                bitmap = customBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = badgeModifier.size(54.dp),
            )
        } else {
            Box(
                modifier = badgeModifier
                    .width(58.dp)
                    .height(28.dp)
                    .background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "HI-RES",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
