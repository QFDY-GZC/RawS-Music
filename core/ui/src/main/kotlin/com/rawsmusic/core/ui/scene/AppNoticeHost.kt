package com.rawsmusic.core.ui.scene

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeEvent
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.module.data.prefs.GlobalLiquidGlassSettings
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.SettingsSurfaceStyle
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val NOTICE_ANIMATION_MS = 250
private const val NOTICE_VISIBLE_MS = 2000L
private val NoticeMotionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Activity-scene notice prototype.
 *
 * The bus is UI-toolkit agnostic. This host owns presentation only: one retained popup surface,
 * a 250 ms bottom/fade entrance, 2 s steady state and the exact reverse automatic exit.
 * Horizontal drag never changes layout; it moves the retained layer and may fling it away.
 */
@Composable
fun AppNoticeHost(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val material by PersonalizationPreferences.noticeSurfaceStyle.collectAsState()
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    val bottomInset = LocalBottomChromeInsets.current.contentBottom
    val scope = rememberCoroutineScope()

    var activeNotice by remember { mutableStateOf<AppNoticeEvent?>(null) }
    val verticalOffset = remember { Animatable(0f) }
    val visibilityAlpha = remember { Animatable(0f) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var swipeMotionActive by remember { mutableStateOf(false) }
    var popupHeightPx by remember { mutableIntStateOf(0) }
    var popupWidthPx by remember { mutableIntStateOf(0) }

    val slideDistancePx = with(density) { 54.dp.toPx() }
    val dismissDistancePx = with(density) { 72.dp.toPx() }
    val windowWidthPx = windowInfo.containerSize.width.toFloat().coerceAtLeast(1f)
    val maxPopupWidth = with(density) {
        (windowInfo.containerSize.width.toDp() - 28.dp)
            .coerceAtMost(440.dp)
            .coerceAtLeast(220.dp)
    }

    LaunchedEffect(Unit) {
        AppNoticeBus.events.collect { event ->
            activeNotice = event
        }
    }

    LaunchedEffect(activeNotice?.id) {
        val notice = activeNotice ?: return@LaunchedEffect
        dragOffsetX = 0f
        swipeMotionActive = false
        verticalOffset.snapTo(slideDistancePx)
        visibilityAlpha.snapTo(0f)
        coroutineScope {
            launch {
                verticalOffset.animateTo(
                    0f,
                    animationSpec = tween(NOTICE_ANIMATION_MS, easing = NoticeMotionEasing),
                )
            }
            launch {
                visibilityAlpha.animateTo(
                    1f,
                    animationSpec = tween(NOTICE_ANIMATION_MS, easing = NoticeMotionEasing),
                )
            }
        }
        delay(NOTICE_VISIBLE_MS)
        coroutineScope {
            launch {
                verticalOffset.animateTo(
                    slideDistancePx,
                    animationSpec = tween(NOTICE_ANIMATION_MS, easing = NoticeMotionEasing),
                )
            }
            launch {
                visibilityAlpha.animateTo(
                    0f,
                    animationSpec = tween(NOTICE_ANIMATION_MS, easing = NoticeMotionEasing),
                )
            }
        }
        if (activeNotice?.id == notice.id) {
            activeNotice = null
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val notice = activeNotice ?: return@Box
        val dragFade = (1f - abs(dragOffsetX) / (windowWidthPx * 0.9f))
            .coerceIn(0.18f, 1f)
        val iconSize = 22.dp
        val noticeShape = RoundedCornerShape(18.dp)
        val draggableState = rememberDraggableState { delta ->
            dragOffsetX += delta
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .padding(bottom = bottomInset + 42.dp)
                .widthIn(min = 176.dp, max = maxPopupWidth)
                .heightIn(min = 46.dp)
                .graphicsLayer {
                    translationX = dragOffsetX
                    translationY = verticalOffset.value
                    alpha = visibilityAlpha.value * dragFade
                    shape = noticeShape
                    clip = swipeMotionActive
                    compositingStrategy = if (swipeMotionActive) {
                        CompositingStrategy.Offscreen
                    } else {
                        CompositingStrategy.Auto
                    }
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        swipeMotionActive = true
                    },
                    onDragStopped = { velocity ->
                        val shouldDismiss =
                            abs(dragOffsetX) >= dismissDistancePx || abs(velocity) >= 1100f
                        if (shouldDismiss) {
                            val direction = when {
                                abs(velocity) >= 120f -> sign(velocity)
                                dragOffsetX != 0f -> sign(dragOffsetX)
                                else -> 1f
                            }
                            val noticeId = notice.id
                            val target = direction * (windowWidthPx + popupWidthPx)
                            scope.launch {
                                val horizontal = Animatable(dragOffsetX)
                                coroutineScope {
                                    launch {
                                        horizontal.animateTo(
                                            target,
                                            animationSpec = tween(
                                                NOTICE_ANIMATION_MS,
                                                easing = NoticeMotionEasing,
                                            ),
                                        ) {
                                            dragOffsetX = value
                                        }
                                    }
                                    launch {
                                        visibilityAlpha.animateTo(
                                            0f,
                                            animationSpec = tween(190),
                                        )
                                    }
                                }
                                if (activeNotice?.id == noticeId) {
                                    activeNotice = null
                                }
                            }
                        } else {
                            scope.launch {
                                val horizontal = Animatable(dragOffsetX)
                                horizontal.animateTo(
                                    0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.82f,
                                        stiffness = 520f,
                                    ),
                                ) {
                                    dragOffsetX = value
                                }
                                swipeMotionActive = false
                            }
                        }
                    },
                )
                .onSizeChanged {
                    popupWidthPx = it.width
                    popupHeightPx = it.height
                }
                .appNoticeSurface(
                    material = material,
                    backdrop = backdrop,
                    shape = noticeShape,
                    glassSettings = glassSettings,
                    surfaceColor = MiuixTheme.colorScheme.surfaceContainer,
                    showOuterShadow = !swipeMotionActive,
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppNoticeGlyph(
                    icon = notice.icon,
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(iconSize),
                )
            }
            Text(
                text = notice.message,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(start = 10.dp, end = 2.dp)
                    .widthIn(max = (maxPopupWidth - 58.dp).coerceAtLeast(120.dp)),
            )
        }
    }
}

@Composable
private fun AppNoticeGlyph(
    icon: AppNoticeIcon,
    tint: Color,
    modifier: Modifier,
) {
    if (icon == AppNoticeIcon.ERROR) {
        Image(
            painter = painterResource(R.drawable.ic_notice_error),
            contentDescription = null,
            modifier = modifier,
        )
        return
    }
    if (icon == AppNoticeIcon.QUEUE) {
        Icon(
            imageVector = MiuixIcons.Regular.Playlist,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
        return
    }
    val resource = when (icon) {
        AppNoticeIcon.USB -> R.drawable.ic_usb_line
        AppNoticeIcon.AUDIO -> R.drawable.ic_music_note
        AppNoticeIcon.AUDIO_EFFECTS -> R.drawable.ic_audio_effects_custom
        AppNoticeIcon.EQUALIZER -> R.drawable.ic_equalizer_bars
        AppNoticeIcon.SCAN -> R.drawable.ic_notice_scan
        AppNoticeIcon.PLAYLIST -> R.drawable.ic_nav_custom_playlists
        AppNoticeIcon.FOLDER -> R.drawable.ic_folder
        AppNoticeIcon.DOWNLOAD -> R.drawable.ic_source_download
        AppNoticeIcon.SHARE -> R.drawable.ic_share
        AppNoticeIcon.METADATA -> R.drawable.ic_metadata_outline
        AppNoticeIcon.LYRICS -> R.drawable.ic_side_rail_lyrics
        AppNoticeIcon.AI -> R.drawable.ic_side_rail_ai
        AppNoticeIcon.PLAY_NEXT -> R.drawable.ic_speed_fill
        AppNoticeIcon.ARTWORK -> R.drawable.ic_album
        AppNoticeIcon.TRANSCODE -> R.drawable.ic_selection_transcode
        AppNoticeIcon.INFO,
        AppNoticeIcon.WARNING,
        AppNoticeIcon.ERROR,
        AppNoticeIcon.QUEUE,
        -> null
    }
    if (resource != null) {
        Icon(
            painter = painterResource(resource),
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
        return
    }
    Canvas(modifier = modifier) {
        val stroke = 2.15.dp.toPx()
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.40f,
            center = center,
            style = Stroke(width = stroke),
        )
        drawLine(
            tint,
            Offset(center.x, size.height * 0.30f),
            Offset(center.x, size.height * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            tint,
            radius = size.minDimension * 0.035f,
            center = Offset(center.x, size.height * 0.71f),
        )
    }
}

private fun Modifier.appNoticeSurface(
    material: SettingsSurfaceStyle,
    backdrop: Backdrop?,
    shape: RoundedCornerShape,
    glassSettings: GlobalLiquidGlassSettings,
    surfaceColor: Color,
    showOuterShadow: Boolean,
): Modifier {
    if (material == SettingsSurfaceStyle.SOLID || backdrop == null || !noticeBackdropSupported) {
        return this.background(
            color = surfaceColor.copy(alpha = 0.94f),
            shape = shape,
        )
    }
    return when (material) {
        SettingsSurfaceStyle.SOLID -> this
        SettingsSurfaceStyle.ACRYLIC -> this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy(0.72f * glassSettings.vibrancyStrength)
                blur((glassSettings.blurRadiusDp * 1.55f).dp.toPx())
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = (0.20f * glassSettings.highlightStrength).coerceIn(0f, 0.6f)
                )
            },
            shadow = {
                Shadow(
                    radius = if (showOuterShadow) 10.dp else 0.dp,
                    color = Color.Black.copy(
                        alpha = if (showOuterShadow) {
                            (0.24f * glassSettings.shadowStrength).coerceIn(0f, 0.45f)
                        } else {
                            0f
                        }
                    ),
                )
            },
            onDrawSurface = {
                drawRect(surfaceColor.copy(alpha = 0.68f))
            },
        )
        SettingsSurfaceStyle.LIQUID_GLASS -> this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                val minDimension = size.minDimension
                vibrancy(glassSettings.vibrancyStrength)
                blur((glassSettings.blurRadiusDp * 0.25f).coerceAtMost(8f).dp.toPx())
                lens(
                    refractionHeight =
                        glassSettings.refractionHeightFraction * minDimension * 0.35f,
                    refractionAmount =
                        glassSettings.refractionAmountFraction * minDimension * 1.25f,
                    depthEffect = true,
                    chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                )
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = (0.34f * glassSettings.highlightStrength).coerceIn(0f, 1f)
                )
            },
            shadow = {
                Shadow(
                    radius = if (showOuterShadow) 10.dp else 0.dp,
                    color = Color.Black.copy(
                        alpha = if (showOuterShadow) {
                            (0.18f * glassSettings.shadowStrength).coerceIn(0f, 0.4f)
                        } else {
                            0f
                        }
                    ),
                )
            },
            onDrawSurface = {
                drawRect(surfaceColor.copy(alpha = 0.16f))
            },
        )
    }
}

private val noticeBackdropSupported: Boolean by lazy {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}
