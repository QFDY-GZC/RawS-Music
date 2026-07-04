package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

internal val CollectionHeroCoverHeight = 330.dp
internal val CollectionHeroMetaHeight = 76.dp
internal val CollectionHeroTotalHeight = CollectionHeroCoverHeight + CollectionHeroMetaHeight

private const val COLLECTION_ENTER_HANDOFF_START = 0.58f
private const val COLLECTION_ENTER_HANDOFF_END = 0.86f
private const val COLLECTION_LEAVE_HANDOFF_START = 0.04f
private const val COLLECTION_LEAVE_HANDOFF_END = 0.22f

@Stable
internal data class CollectionHeroData(
    val stableKey: String,
    val sharedElementId: String,
    val coverKey: String,
    val title: String,
    val subtitle: String,
    val meta: String,
    val songs: List<AudioFile>
)

@Composable
internal fun CollectionHeroDetailPage(
    hero: CollectionHeroData,
    listScene: NavScene,
    detailScene: NavScene,
    songListState: ComposePowerListState,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val headerHeightPx = with(density) { CollectionHeroTotalHeight.toPx() }
    val headerOffsetPx = -songListState.viewportScrollYPx.coerceIn(0f, headerHeightPx)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        ComposePowerListFull(
            songs = hero.songs,
            state = songListState,
            contentTopPadding = CollectionHeroTotalHeight,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            onSongClick = { _, index ->
                onPlayQueue(hero.songs, index)
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CollectionHeroTotalHeight)
                .offset {
                    IntOffset(
                        x = 0,
                        y = headerOffsetPx.roundToInt()
                    )
                }
        ) {
            CollectionHeroHeader(
                hero = hero,
                listScene = listScene,
                detailScene = detailScene,
                songListState = songListState,
                onBack = onBack
            )
        }
    }
}

@Composable
private fun CollectionHeroHeader(
    hero: CollectionHeroData,
    listScene: NavScene,
    detailScene: NavScene,
    songListState: ComposePowerListState,
    onBack: () -> Unit
) {
    val spec = LocalSharedTransitionSpec.current
    val coverRegistry = LocalSharedCoverRegistry.current
    val onBg = MiuixTheme.colorScheme.onBackground
    val onBgVariant = MiuixTheme.colorScheme.onSurfaceVariantSummary

    val collectionTransition = spec.active && isCollectionSharedCoverTransition(
        fromSceneId = spec.fromSceneId,
        toSceneId = spec.toSceneId,
        listScene = listScene,
        detailScene = detailScene
    )

    val entering = collectionTransition &&
        spec.fromSceneId == listScene.name &&
        spec.toSceneId == detailScene.name

    val leaving = collectionTransition &&
        spec.fromSceneId == detailScene.name &&
        spec.toSceneId == listScene.name

    val p = spec.progress.coerceIn(0f, 1f)
    val handoff = when {
        entering -> smoothStep(COLLECTION_ENTER_HANDOFF_START, COLLECTION_ENTER_HANDOFF_END, p)
        leaving -> smoothStep(COLLECTION_LEAVE_HANDOFF_START, COLLECTION_LEAVE_HANDOFF_END, p)
        else -> 1f
    }

    val sceneAlpha = when {
        entering -> handoff
        leaving -> 1f - handoff
        else -> 1f
    }

    val sceneScale = when {
        entering -> 0.92f + handoff * 0.08f
        leaving -> 1f + handoff * 0.10f
        else -> 1f
    }

    val gesturePulse = rememberCollectionHeroZoomPulse(songListState)
    val gestureAlpha = 1f - gesturePulse * 0.34f
    val gestureScale = 1f + gesturePulse * 0.08f

    val finalAlpha = (sceneAlpha * gestureAlpha).coerceIn(0f, 1f)
    val finalScale = sceneScale * gestureScale

    val metaSceneAlpha = when {
        entering -> smoothStep(0.50f, 0.88f, p)
        leaving -> 1f - smoothStep(0.00f, 0.18f, p)
        else -> 1f
    }
    val metaAlpha = (metaSceneAlpha * gestureAlpha).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CollectionHeroCoverHeight)
                .clipToBounds()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background)
            )

            CrossfadeAlbumArt(
                key = hero.coverKey,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = finalAlpha
                        scaleX = finalScale
                        scaleY = finalScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        clip = true
                    },
                contentScale = ContentScale.Crop,
                showPlaceholder = false
            )

            CollectionSharedCoverAnchor(
                scene = detailScene,
                elementId = hero.sharedElementId,
                coverKey = hero.coverKey,
                radiusDp = 0f,
                modifier = Modifier.fillMaxSize()
            )

            IconButton(
                onClick = {
                    coverRegistry.freeze(
                        sceneId = detailScene.name,
                        elementId = hero.sharedElementId
                    )
                    onBack()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 12.dp)
                    .graphicsLayer { alpha = metaAlpha }
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = "返回",
                    tint = onBg
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CollectionHeroMetaHeight)
                .graphicsLayer {
                    alpha = metaAlpha
                    scaleX = 0.97f + metaAlpha * 0.03f
                    scaleY = 0.97f + metaAlpha * 0.03f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .padding(horizontal = 28.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = hero.title,
                color = onBg,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Text(
                text = hero.subtitle,
                color = onBgVariant,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Music,
                    contentDescription = null,
                    tint = onBgVariant,
                    modifier = Modifier.size(16.dp)
                )

                Text(
                    text = hero.meta,
                    color = onBgVariant,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun rememberCollectionHeroZoomPulse(
    state: ComposePowerListState
): Float {
    val active = state.isTransitioning || state.isPinching
    if (!active) return 0f
    val p = state.transitionProgress.coerceIn(0f, 1f)
    return 1f - abs(p * 2f - 1f)
}

@Composable
internal fun CollectionSharedCoverAnchor(
    scene: NavScene,
    elementId: String,
    coverKey: String,
    radiusDp: Float,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val sceneId = scene.name

    DisposableEffect(sceneId, elementId) {
        onDispose {
            coverRegistry.unregister(sceneId, elementId)
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            if (coverKey.isBlank() || elementId.isBlank()) return@onGloballyPositioned
            val position = coordinates.positionInWindow()
            val size = coordinates.size
            coverRegistry.register(
                sceneId = sceneId,
                elementId = elementId,
                snapshot = SharedCoverSnapshot(
                    sceneId = sceneId,
                    elementId = elementId,
                    boundsInWindow = Rect(
                        left = position.x,
                        top = position.y,
                        right = position.x + size.width,
                        bottom = position.y + size.height
                    ),
                    coverKey = coverKey,
                    radiusDp = radiusDp
                )
            )
        }
    )
}

internal fun isCollectionSharedCoverTransition(
    fromSceneId: String,
    toSceneId: String,
    listScene: NavScene,
    detailScene: NavScene
): Boolean {
    return (fromSceneId == listScene.name && toSceneId == detailScene.name) ||
        (fromSceneId == detailScene.name && toSceneId == listScene.name)
}

internal fun smoothStep(
    edge0: Float,
    edge1: Float,
    x: Float
): Float {
    if (edge0 == edge1) return if (x >= edge1) 1f else 0f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
