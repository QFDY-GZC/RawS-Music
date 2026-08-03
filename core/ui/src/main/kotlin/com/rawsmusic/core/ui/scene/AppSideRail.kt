package com.rawsmusic.core.ui.scene

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import kotlin.math.abs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class AppSideRailDestination {
    MUSIC_LIBRARY,
    PLAYLISTS,
    APPEARANCE,
    LYRICS,
    AI_MODELS,
    SOURCE_IMPORT,
    LOG_ANALYSIS,
}

private const val SIDE_RAIL_REVEAL_START_FRACTION = 0.5f

private enum class SideRailAnchor {
    Collapsed,
    Expanded,
}

private data class SideRailItem(
    val destination: AppSideRailDestination,
    val titleRes: Int,
    val iconRes: Int,
)

private val sideRailItems = listOf(
    SideRailItem(AppSideRailDestination.MUSIC_LIBRARY, R.string.side_rail_music_library, R.drawable.ic_music_2_fill),
    SideRailItem(AppSideRailDestination.PLAYLISTS, R.string.side_rail_playlists, R.drawable.ic_nav_custom_playlists),
    SideRailItem(AppSideRailDestination.APPEARANCE, R.string.side_rail_appearance, R.drawable.ic_palette),
    SideRailItem(AppSideRailDestination.LYRICS, R.string.side_rail_lyrics, R.drawable.ic_side_rail_lyrics),
    SideRailItem(AppSideRailDestination.AI_MODELS, R.string.side_rail_ai_models, R.drawable.ic_side_rail_ai),
    SideRailItem(AppSideRailDestination.SOURCE_IMPORT, R.string.side_rail_source_import, R.drawable.ic_source_download),
    SideRailItem(AppSideRailDestination.LOG_ANALYSIS, R.string.side_rail_log_analysis, R.drawable.ic_side_rail_log),
)

/**
 * Salt-style side rail: the rail and page are measured by the same Layout.
 * Opening the rail moves both children instead of drawing a modal drawer over the page.
 */
@Composable
internal fun AppSideRailHost(
    enabled: Boolean,
    onDestinationClick: (AppSideRailDestination) -> Unit,
    modifier: Modifier = Modifier,
    background: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val railWidth = 264.dp
    val railWidthPx = with(density) { railWidth.toPx() }
    // The collapsed rail may be revealed from the whole left half of the screen. Direction
    // arbitration below still requires a rightward, horizontally dominant drag, so vertical list
    // scrolling and leftward gestures remain available.
    var progress by remember { mutableFloatStateOf(0f) }
    val expanded = progress >= 0.5f

    suspend fun settle(target: SideRailAnchor) {
        Animatable(progress).animateTo(
            targetValue = if (target == SideRailAnchor.Expanded) 1f else 0f,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        ) {
            progress = value
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled && progress != 0f) {
            progress = 0f
        }
    }

    BackHandler(enabled = enabled && expanded) {
        scope.launch { settle(SideRailAnchor.Collapsed) }
    }

    Layout(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(enabled, railWidthPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val startedExpanded = progress > 0f
                    val revealStartLimitPx = size.width * SIDE_RAIL_REVEAL_START_FRACTION
                    if (!startedExpanded && down.position.x > revealStartLimitPx) {
                        return@awaitEachGesture
                    }

                    var lastX = down.position.x
                    var totalX = 0f
                    var totalY = 0f
                    var dragging = false
                    var pointer = down

                    do {
                        // Observe after descendants. Mini-player artwork/title gestures consume
                        // horizontal movement on Main, so the rail must not pre-empt their
                        // track-switch animation from the ancestor capture pass.
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                        if (!dragging && change.isConsumed) {
                            return@awaitEachGesture
                        }
                        val dx = change.position.x - lastX
                        val dy = change.position.y - pointer.position.y
                        lastX = change.position.x
                        totalX += dx
                        totalY += dy

                        val horizontalIntent =
                            abs(totalX) > viewConfiguration.touchSlop && abs(totalX) > abs(totalY)
                        val directionAccepted = startedExpanded || totalX > 0f
                        if (!dragging && horizontalIntent && directionAccepted) {
                            dragging = true
                        }
                        if (dragging) {
                            change.consume()
                            progress = (progress + dx / railWidthPx).coerceIn(0f, 1f)
                        }
                        pointer = change
                    } while (pointer.pressed)

                    if (dragging) {
                        val target = when {
                            totalX > railWidthPx * 0.16f -> SideRailAnchor.Expanded
                            totalX < -railWidthPx * 0.16f -> SideRailAnchor.Collapsed
                            progress >= 0.5f -> SideRailAnchor.Expanded
                            else -> SideRailAnchor.Collapsed
                        }
                        scope.launch { settle(target) }
                    }
                }
            },
        content = {
            Box(Modifier.fillMaxSize()) { background() }
            AppSideRail(
                modifier = Modifier.fillMaxHeight(),
                onItemClick = { destination ->
                    scope.launch {
                        settle(SideRailAnchor.Collapsed)
                        onDestinationClick(destination)
                    }
                },
            )
            Box(Modifier.fillMaxSize()) { content() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val railWidthInt = railWidthPx.toInt().coerceAtMost(width)
        val backgroundPlaceable = measurables[0].measure(
            constraints.copy(
                minWidth = width,
                maxWidth = width,
                minHeight = height,
                maxHeight = height,
            )
        )
        val railPlaceable = measurables[1].measure(
            constraints.copy(
                minWidth = railWidthInt,
                maxWidth = railWidthInt,
                minHeight = height,
                maxHeight = height,
            )
        )
        val contentPlaceable = measurables[2].measure(
            constraints.copy(
                minWidth = width,
                maxWidth = width,
                minHeight = height,
                maxHeight = height,
            )
        )
        val offset = (railWidthInt * progress).toInt()

        layout(width, height) {
            // The visual background stays fixed while rail and page move over it. This keeps the
            // flow/theme texture spatially continuous instead of exposing a second solid surface.
            backgroundPlaceable.placeRelative(0, 0)
            railPlaceable.placeRelative(offset - railWidthInt, 0)
            contentPlaceable.placeRelative(offset, 0)
        }
    }
}

@Composable
private fun AppSideRail(
    onItemClick: (AppSideRailDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 10.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = stringResource(R.string.side_rail_title),
            color = colors.onBackground,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )

        sideRailItems.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 15.dp,
                colors = CardDefaults.defaultColors(
                    color = colors.surfaceContainer.copy(alpha = 0.82f),
                    contentColor = colors.onSurface,
                ),
                onClick = { onItemClick(item.destination) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = null,
                        tint = colors.onSurface,
                        modifier = Modifier.size(23.dp),
                    )
                    Spacer(Modifier.width(13.dp))
                    Text(
                        text = stringResource(item.titleRes),
                        color = colors.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
