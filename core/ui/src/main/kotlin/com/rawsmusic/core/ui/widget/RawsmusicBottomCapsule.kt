package com.rawsmusic.core.ui.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.core.ui.R

private val NavIconColor = Color(0xFF1C1B1F)
private val TextPrimary = Color(0xFF1C1B1F)
private val TextSecondary = Color(0xFF49454F)
private val ProgressActiveColor = Color(0xFF1C1B1F)
private val ProgressInactiveColor = Color(0x1F1C1B1F)
private val DividerColor = Color(0x1F1C1B1F)

private data class NavItem(
    val id: CapsuleNavId,
    val iconRes: Int
)

private val navItems = listOf(
    NavItem(CapsuleNavId.LIBRARY, R.drawable.ic_function_fill),
    NavItem(CapsuleNavId.EQ, R.drawable.ic_equalizer_line),
    NavItem(CapsuleNavId.SEARCH, R.drawable.ic_search_bold),
    NavItem(CapsuleNavId.SETTINGS, R.drawable.ic_home_3_fill)
)

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${String.format("%02d", seconds)}"
}

@Composable
fun RawsmusicBottomCapsule() {
    val manager = BottomCapsuleStateManager
    val mode by manager.mode.collectAsState()
    val title by manager.songTitle.collectAsState()
    val artist by manager.songArtist.collectAsState()
    val coverPath by manager.coverPath.collectAsState()
    val isPlaying by manager.isPlaying.collectAsState()
    val currentPosition by manager.currentPosition.collectAsState()
    val duration by manager.duration.collectAsState()
    val isSeeking by manager.isSeeking.collectAsState()
    val selectedNavId by manager.selectedNavId.collectAsState()
    val lyricText by manager.lyricText.collectAsState()
    val lyricTranslation by manager.lyricTranslation.collectAsState()
    val isDualLine by manager.isDualLine.collectAsState()

    if (mode == CapsuleMode.HIDDEN) return

    val backdrop = rememberLayerBackdrop()

    val progressFraction = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f

    val capsuleAlpha by animateFloatAsState(
        targetValue = if (mode == CapsuleMode.HIDDEN) 0f else 1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "capsule_alpha"
    )
    val capsuleOffsetY by animateFloatAsState(
        targetValue = if (mode == CapsuleMode.HIDDEN) 80f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "capsule_offset_y"
    )

    val miniPlayerRotationX by animateFloatAsState(
        targetValue = if (mode == CapsuleMode.FULL) 0f else -90f,
        animationSpec = tween(100, easing = FastOutSlowInEasing),
        label = "mini_rotation_x"
    )

    val miniPlayerHeight by animateDpAsState(
        targetValue = if (mode == CapsuleMode.FULL) 54.dp else 0.dp,
        animationSpec = tween(100, easing = FastOutSlowInEasing),
        label = "mini_height"
    )

    val dividerAlpha by animateFloatAsState(
        targetValue = if (mode == CapsuleMode.FULL) 1f else 0f,
        animationSpec = tween(100),
        label = "divider_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(28.dp) },
                effects = {
                    vibrancy(1.5f)
                    blur(8.dp.toPx())
                    lens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
                },
                highlight = { Highlight.Plain },
                shadow = { Shadow.Default }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (miniPlayerHeight > 0.dp || miniPlayerRotationX > -89f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(miniPlayerHeight)
                    .clipToBounds()
                    .graphicsLayer {
                        this.cameraDistance = 8f
                        this.rotationX = miniPlayerRotationX
                        this.transformOrigin = TransformOrigin(0.5f, 1f)
                        val foldProgress = -miniPlayerRotationX / 90f
                        this.alpha = 1f - (foldProgress * 0.3f)
                    }
            ) {
                CapsuleMiniPlayer(
                    songTitle = title,
                    songArtist = artist,
                    coverPath = coverPath,
                    isPlaying = isPlaying,
                    lyricText = lyricText,
                    lyricTranslation = lyricTranslation,
                    isDualLine = isDualLine,
                    alpha = 1f,
                    height = 54.dp,
                    onClick = { manager.onBarClick?.invoke() },
                    onPlayPauseClick = { manager.onPlayPauseClick?.invoke() }
                )
            }
        }

        if (dividerAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(0.5.dp)
                    .graphicsLayer { alpha = dividerAlpha }
                    .background(DividerColor)
            )
        }

        ProgressSection(
            progressFraction = progressFraction,
            currentPosition = currentPosition,
            duration = duration,
            isSeeking = isSeeking,
            onProgressChange = { fraction ->
                manager.setSeeking(true)
                val targetMs = (fraction * duration).toLong()
                manager.setSeekPosition(targetMs)
            },
            onProgressChangeFinished = {
                manager.setSeeking(false)
                manager.onSeekTo?.invoke(manager.currentPosition.value)
            }
        )

        NavigationRow(
            selectedNavId = selectedNavId,
            onNavClick = { id ->
                manager.setSelectedNavId(id)
                manager.onNavClick?.invoke(id)
            }
        )
    }
}

@Composable
private fun CapsuleMiniPlayer(
    songTitle: String,
    songArtist: String,
    coverPath: String,
    isPlaying: Boolean,
    lyricText: String,
    lyricTranslation: String,
    isDualLine: Boolean,
    alpha: Float,
    height: Dp,
    onClick: () -> Unit,
    onPlayPauseClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { this.alpha = alpha }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = coverPath.ifBlank { null },
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            if (lyricText.isNotBlank()) {
                Text(
                    text = lyricText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isDualLine && lyricTranslation.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = lyricTranslation,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = songTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (songArtist.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = songArtist,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.width(4.dp))

        Icon(
            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = if (isPlaying) "暂停" else "播放",
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPauseClick
                )
                .padding(8.dp),
            tint = TextPrimary
        )
    }
}

@Composable
private fun ProgressSection(
    progressFraction: Float,
    currentPosition: Long,
    duration: Long,
    isSeeking: Boolean,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
    ) {
        Slider(
            value = progressFraction.coerceIn(0f, 1f),
            onValueChange = onProgressChange,
            onValueChangeFinished = onProgressChangeFinished,
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
            colors = SliderDefaults.colors(
                thumbColor = ProgressActiveColor,
                activeTrackColor = ProgressActiveColor,
                inactiveTrackColor = ProgressInactiveColor
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentPosition),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
            Text(
                text = formatTime(duration),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun NavigationRow(
    selectedNavId: CapsuleNavId,
    onNavClick: (CapsuleNavId) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        navItems.forEach { item ->
            val isSelected = selectedNavId == item.id

            val indicatorWidth by animateDpAsState(
                targetValue = if (isSelected) 20.dp else 0.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh
                ), label = "indicator"
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onNavClick(item.id) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(item.iconRes),
                    contentDescription = null,
                    tint = NavIconColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.height(1.dp))
                Box(
                    modifier = Modifier
                        .width(indicatorWidth)
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(1.25.dp))
                        .background(NavIconColor)
                )
            }
        }
    }
}
