package com.rawsmusic.core.ui.widget

import android.graphics.RectF
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.shouldShowDefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.RawArtworkPolicy

private const val MINI_PLAYER_PREFS = "mini_player_prefs"
private const val KEY_ARTWORK_MODE = "artwork_mode"

@Composable
fun rememberMiniPlayerArtworkMode(): MutableState<MiniPlayerArtworkMode> {
    val context = LocalContext.current.applicationContext
    val state = remember {
        val prefs = context.getSharedPreferences(MINI_PLAYER_PREFS, android.content.Context.MODE_PRIVATE)
        mutableStateOf(
            MiniPlayerArtworkMode.fromPrefs(prefs.getString(KEY_ARTWORK_MODE, "normal"))
        )
    }
    LaunchedEffect(state.value) {
        context.getSharedPreferences(MINI_PLAYER_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ARTWORK_MODE, MiniPlayerArtworkMode.toPrefs(state.value))
            .apply()
    }
    return state
}

@Composable
fun MiniPlayerArtwork(
    mode: MiniPlayerArtworkMode,
    coverPath: String?,
    isPlaying: Boolean,
    contentDescription: String?,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onDoubleTapToggleMode: () -> Unit,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
    animateArtwork: Boolean = false
) {
    val currentCoverKey = coverPath?.takeIf { it.isNotBlank() }

    val appliedRotation = rememberMiniArtworkRotation(
        enabled = animateArtwork && isPlaying && mode == MiniPlayerArtworkMode.Vinyl
    )

    // A new song must own a new artwork composition. This disposes the previous request and handle
    // before the next one can render, so a late/failed request can never leave the old cover visible.
    key(currentCoverKey) {
        when (mode) {
            MiniPlayerArtworkMode.Normal -> {
                NormalMiniArtwork(
                    coverPath = currentCoverKey,
                    rotation = appliedRotation,
                    contentDescription = contentDescription,
                    onCoverBoundsChanged = onCoverBoundsChanged,
                    onDoubleTapToggleMode = onDoubleTapToggleMode,
                    onSingleTap = onSingleTap,
                    modifier = modifier.size(52.dp)
                )
            }
            MiniPlayerArtworkMode.Vinyl -> {
                VinylMiniArtwork(
                    coverPath = currentCoverKey,
                    rotation = appliedRotation,
                    contentDescription = contentDescription,
                    onCoverBoundsChanged = onCoverBoundsChanged,
                    onDoubleTapToggleMode = onDoubleTapToggleMode,
                    onSingleTap = onSingleTap,
                    modifier = modifier.requiredSize(width = 70.dp, height = 52.dp)
                )
            }
        }
    }
}

@Composable
private fun rememberMiniArtworkRotation(enabled: Boolean): Float {
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            rotation = 0f
            return@LaunchedEffect
        }
        var lastFrame = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val deltaMs = (now - lastFrame).coerceIn(0L, 250L)
            lastFrame = now
            rotation = (rotation + deltaMs * 360f / 20_000f) % 360f
            delay(83L)
        }
    }
    return rotation
}


@Composable
private fun NormalMiniArtwork(
    coverPath: String?,
    rotation: Float,
    contentDescription: String?,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onDoubleTapToggleMode: () -> Unit,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
    artworkSize: Dp = 44.dp
) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onSingleTap() },
                onDoubleTap = { onDoubleTapToggleMode() }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        CoverVisual(
            coverPath = coverPath,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(artworkSize)
                .rotate(rotation)
                .clip(CircleShape)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    onCoverBoundsChanged(RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
                },
            contentScale = ContentScale.Crop,
            targetSize = 256
        )
    }
}

@Composable
private fun VinylMiniArtwork(
    coverPath: String?,
    rotation: Float,
    contentDescription: String?,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onDoubleTapToggleMode: () -> Unit,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onSingleTap() },
                onDoubleTap = { onDoubleTapToggleMode() }
            )
        },
        contentAlignment = Alignment.CenterStart
    ) {
        // 黑胶唱片 + 中心小封面（一起旋转）
        Box(
            modifier = Modifier
                .size(39.dp)
                .offset(x = 18.dp)
                .rotate(rotation)
                .zIndex(0f),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_vinyl_record),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CoverVisual(
                    coverPath = coverPath,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    targetSize = 96
                )
            }
        }

        // 左侧大专辑图封套（不旋转）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .zIndex(2f)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    onCoverBoundsChanged(
                        RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            CoverVisual(
                coverPath = coverPath,
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
                targetSize = 256
            )
        }
    }
}

@Composable
private fun CoverVisual(
    coverPath: String?,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    targetSize: Int
) {
    when {
        !coverPath.isNullOrBlank() -> {
            BitmapImage(
                key = coverPath,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                targetWidth = targetSize,
                targetHeight = targetSize,
                priority = BitmapRequest.Priority.LOADING_LIST,
                surface = ArtworkSurface.MiniPlayer,
                fadeInMillis = RawArtworkPolicy.SMALL_SURFACE_FADE_MS
            )
        }
        else -> {
            val showDefault = shouldShowDefaultAlbumArtwork(coverPath, targetSize, targetSize)
            if (showDefault) {
                DefaultAlbumArtwork(modifier = modifier)
            } else {
                MiniCoverPlaceholder(modifier = modifier)
            }
        }
    }
}

@Composable
private fun MiniCoverPlaceholder(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.22f),
                    Color(0xFF8C7866).copy(alpha = 0.26f),
                    Color.Black.copy(alpha = 0.34f)
                ),
                center = Offset(size.width * 0.35f, size.height * 0.25f),
                radius = radius * 1.35f
            ),
            radius = radius,
            center = center
        )
        val path = Path().apply {
            moveTo(size.width * 0.34f, size.height * 0.32f)
            lineTo(size.width * 0.68f, size.height * 0.42f)
            lineTo(size.width * 0.60f, size.height * 0.70f)
            lineTo(size.width * 0.28f, size.height * 0.58f)
            close()
        }
        drawPath(path = path, color = Color.White.copy(alpha = 0.18f))
        drawCircle(
            color = Color.White.copy(alpha = 0.20f),
            radius = radius * 0.12f,
            center = center
        )
    }
}
