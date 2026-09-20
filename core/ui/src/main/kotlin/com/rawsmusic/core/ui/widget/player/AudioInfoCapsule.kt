package com.rawsmusic.core.ui.widget.player

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.module.data.prefs.AudioInfoCapsuleBackgroundStyle
import com.rawsmusic.module.data.prefs.AudioInfoCapsuleContentStyle
import com.rawsmusic.module.data.prefs.AudioInfoCapsulePreferences
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Shared visual implementation for the standard and immersive playback audio-information capsule.
 * Content state/click cycling and long-press behavior remain owned by AudioInfoCapsuleHelper.
 */
@Composable
fun AudioInfoCapsule(
    song: AudioFile?,
    coverPath: String?,
    text: String,
    playbackChainText: String = "",
    smartTransitionVisible: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible by AudioInfoCapsulePreferences.visible.collectAsState()
    if (!visible && !smartTransitionVisible) return

    val contentStyle by AudioInfoCapsulePreferences.contentStyle.collectAsState()
    val style by AudioInfoCapsulePreferences.backgroundStyle.collectAsState()
    val customArgb by AudioInfoCapsulePreferences.customColor.collectAsState()
    val customAlphaPercent by AudioInfoCapsulePreferences.customAlphaPercent.collectAsState()
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f

    val accentState = rememberCoverAccentColorState(coverPath)
    var stableAlbumColor by remember { mutableStateOf(accentState.color) }
    LaunchedEffect(coverPath, accentState.ready, accentState.color) {
        // Keep the previous album color while the new artwork palette is still loading. This avoids
        // old -> neutral -> new flashes when tracks change.
        if (accentState.ready) stableAlbumColor = accentState.color
    }
    val animatedAlbumColor by animateColorAsState(
        targetValue = stableAlbumColor,
        animationSpec = tween(durationMillis = 190),
        label = "audio-info-capsule-album-color",
    )

    val fill = when (style) {
        AudioInfoCapsuleBackgroundStyle.CLASSIC ->
            scheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.78f else 0.82f)
        AudioInfoCapsuleBackgroundStyle.TRANSPARENT -> Color.Transparent
        AudioInfoCapsuleBackgroundStyle.ALBUM_COLOR -> animatedAlbumColor.copy(alpha = 0.68f)
        AudioInfoCapsuleBackgroundStyle.THEME_COLOR -> scheme.primary.copy(alpha = 0.72f)
        AudioInfoCapsuleBackgroundStyle.CUSTOM_COLOR ->
            Color(customArgb).copy(alpha = customAlphaPercent.coerceIn(0, 100) / 100f)
    }
    val contentColor = if (usesReferenceStaticForeground()) {
        Color.White.copy(alpha = 0.90f)
    } else when (style) {
        AudioInfoCapsuleBackgroundStyle.CLASSIC -> scheme.onSurface
        AudioInfoCapsuleBackgroundStyle.TRANSPARENT ->
            if (isDark) Color.White.copy(alpha = 0.88f) else scheme.onBackground.copy(alpha = 0.84f)
        AudioInfoCapsuleBackgroundStyle.ALBUM_COLOR,
        AudioInfoCapsuleBackgroundStyle.THEME_COLOR,
        AudioInfoCapsuleBackgroundStyle.CUSTOM_COLOR ->
            readableCapsuleContentColor(
                fill = fill,
                fallback = if (isDark) Color.White.copy(alpha = 0.90f) else scheme.onBackground.copy(alpha = 0.88f),
                backdrop = scheme.background,
            )
    }

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "audio-info-capsule-scale",
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.6f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "audio-info-capsule-press-alpha",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = pressAlpha
            }
            .clip(RoundedCornerShape(50))
            .background(fill)
            .pointerInput(onClick, onLongClick, smartTransitionVisible) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        if (!smartTransitionVisible && contentStyle == AudioInfoCapsuleContentStyle.CYCLE) onClick()
                    },
                    onLongPress = { if (!smartTransitionVisible) onLongClick() },
                )
            }
            .height(18.dp)
            .widthIn(max = 320.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = smartTransitionVisible,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            },
            label = "audio-info-smart-transition",
        ) { showSmartTransition ->
            if (showSmartTransition) {
                SmartTransitionShimmerText(
                    text = stringResource(R.string.player_smart_crossfade),
                    color = contentColor,
                )
            } else {
                Text(
                    text = when (contentStyle) {
                        AudioInfoCapsuleContentStyle.CYCLE -> text.ifBlank { audioChainText(song) }
                        AudioInfoCapsuleContentStyle.PLAYBACK_CHAIN ->
                            playbackChainText.ifBlank { audioChainText(song) }
                    },
                    color = contentColor,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SmartTransitionShimmerText(text: String, color: Color) {
    var textWidthPx by remember { mutableStateOf(1) }
    val frameAnimationActive = LocalUiFrameAnimationActive.current
    val animationsEnabled = remember {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }
    val style = TextStyle(
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.Medium,
    )
    Box(modifier = Modifier.onSizeChanged { textWidthPx = it.width.coerceAtLeast(1) }) {
        Text(
            text = text,
            color = color,
            style = style,
            maxLines = 1,
        )
        if (animationsEnabled && frameAnimationActive) {
            SmartTransitionShimmerOverlay(
                text = text,
                widthPx = textWidthPx,
                style = style,
            )
        }
    }
}

@Composable
private fun SmartTransitionShimmerOverlay(
    text: String,
    widthPx: Int,
    style: TextStyle,
) {
    val transition = rememberInfiniteTransition(label = "smart-crossfade-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1_400,
                delayMillis = 450,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "smart-crossfade-shimmer-progress",
    )
    val width = widthPx.toFloat()
    val startX = -width + progress * width * 2.6f
    Text(
        text = text,
        style = style.copy(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.20f to Color.White.copy(alpha = 0.50f),
                    0.42f to Color.White,
                    0.58f to Color.White,
                    0.80f to Color.White.copy(alpha = 0.50f),
                    1.00f to Color.Transparent,
                ),
                start = androidx.compose.ui.geometry.Offset(startX, 0f),
                end = androidx.compose.ui.geometry.Offset(startX + width * 0.62f, 0f),
            ),
        ),
        maxLines = 1,
    )
}

private fun readableCapsuleContentColor(fill: Color, fallback: Color, backdrop: Color): Color {
    // At very low alpha the player backdrop dominates, so use the player foreground tone rather
    // than pretending the translucent source color is opaque.
    if (fill.alpha < 0.34f) return fallback
    val a = fill.alpha.coerceIn(0f, 1f)
    val composite = Color(
        red = fill.red * a + backdrop.red * (1f - a),
        green = fill.green * a + backdrop.green * (1f - a),
        blue = fill.blue * a + backdrop.blue * (1f - a),
        alpha = 1f,
    )
    return if (composite.luminance() >= 0.58f) {
        Color(0xFF171719).copy(alpha = 0.94f)
    } else {
        Color.White.copy(alpha = 0.94f)
    }
}
