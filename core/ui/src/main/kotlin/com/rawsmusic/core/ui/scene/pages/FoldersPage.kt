package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.rawsmusic.core.ui.widget.powerlist.ComposeGenericPowerList
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.FolderPowerListItem
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.stablePowerListHash64
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.roundToInt

private val FolderHeroCoverHeight = 330.dp
private val FolderHeroMetaHeight = 76.dp
private val FolderHeroTotalHeight = FolderHeroCoverHeight + FolderHeroMetaHeight

@Composable
fun FoldersPage(
    songs: List<AudioFile> = emptyList(),
    selectedFolderPath: String? = null,
    onBack: () -> Unit,
    onFolderClick: (String) -> Unit = {},
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    powerListState: ComposePowerListState = rememberComposePowerListState("folders"),
    modifier: Modifier = Modifier
) {
    val folderDetailSongsState = rememberComposePowerListState("folder_detail_songs")
    val folders by remember(songs) {
        derivedStateOf { songs.toFolderGroups() }
    }

    if (selectedFolderPath.isNullOrBlank()) {
        FolderListPage(
            folders = folders,
            state = powerListState,
            onFolderClick = onFolderClick,
            modifier = modifier
        )
    } else {
        val decodedPath = remember(selectedFolderPath) { Uri.decode(selectedFolderPath) }
        val folder = remember(decodedPath, folders) {
            folders.firstOrNull { it.path == decodedPath } ?: FolderGroupUi.empty(decodedPath)
        }
        FolderDetailPage(
            folder = folder,
            songListState = folderDetailSongsState,
            onBack = onBack,
            onPlayQueue = onPlayQueue,
            modifier = modifier
        )
    }
}

@Composable
private fun FolderListPage(
    folders: List<FolderGroupUi>,
    state: ComposePowerListState,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current

    val items = remember(folders) {
        folders.map { folder ->
            FolderPowerListItem(
                path = folder.path,
                name = folder.name,
                parentName = folder.parentName,
                cover = folder.coverKey,
                songCount = folder.songCount,
                totalDurationMs = folder.totalDurationMs
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        ComposeGenericPowerList(
            items = items,
            state = state,
            sharedCoverSceneId = NavScene.FOLDERS.name,
            modifier = Modifier.fillMaxSize(),
            onItemClick = { item, _, _ ->
                val folder = item as? FolderPowerListItem ?: return@ComposeGenericPowerList

                coverRegistry.freeze(
                    sceneId = NavScene.FOLDERS.name,
                    elementId = folder.sharedCoverElementId
                )

                onFolderClick(folder.path)
            }
        )
    }
}

@Composable
private fun FolderDetailPage(
    folder: FolderGroupUi,
    songListState: ComposePowerListState,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val headerHeightPx = with(density) { FolderHeroTotalHeight.toPx() }
    val headerOffsetPx = -songListState.viewportScrollYPx.coerceIn(0f, headerHeightPx)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        ComposePowerListFull(
            songs = folder.songs,
            state = songListState,
            contentTopPadding = FolderHeroTotalHeight,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            onSongClick = { _, index ->
                onPlayQueue(folder.songs, index)
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FolderHeroTotalHeight)
                .offset { IntOffset(0, headerOffsetPx.roundToInt()) }
        ) {
            FolderHeroHeader(
                folder = folder,
                songListState = songListState,
                onBack = onBack
            )
        }
    }
}

@Composable
private fun FolderHeroHeader(
    folder: FolderGroupUi,
    songListState: ComposePowerListState,
    onBack: () -> Unit
) {
    val spec = LocalSharedTransitionSpec.current
    val coverRegistry = LocalSharedCoverRegistry.current

    val onBg = MiuixTheme.colorScheme.onBackground
    val onBgVariant = MiuixTheme.colorScheme.onSurfaceVariantSummary

    val folderSharedTransition = spec.active && isFolderCoverSharedTransition(
        fromSceneId = spec.fromSceneId,
        toSceneId = spec.toSceneId
    )

    val enteringFolder =
        folderSharedTransition &&
            spec.fromSceneId == NavScene.FOLDERS.name &&
            spec.toSceneId == NavScene.FOLDER_HIERARCHY.name

    val leavingFolder =
        folderSharedTransition &&
            spec.fromSceneId == NavScene.FOLDER_HIERARCHY.name &&
            spec.toSceneId == NavScene.FOLDERS.name

    val sceneProgress = spec.progress.coerceIn(0f, 1f)

    val handoff = when {
        enteringFolder -> smoothStep(
            FOLDER_ENTER_HANDOFF_START,
            FOLDER_ENTER_HANDOFF_END,
            sceneProgress
        )
        leavingFolder -> smoothStep(
            FOLDER_LEAVE_HANDOFF_START,
            FOLDER_LEAVE_HANDOFF_END,
            sceneProgress
        )
        else -> 1f
    }

    val sceneAlpha = when {
        enteringFolder -> handoff
        leavingFolder -> 1f - handoff
        else -> 1f
    }

    val sceneScale = when {
        enteringFolder -> 0.92f + handoff * 0.08f
        leavingFolder -> 1f + handoff * 0.10f
        else -> 1f
    }

    // PowerList 缩放手势：轻微淡出/放大，不把 Hero 打到 0
    val powerListGestureActive =
        songListState.isTransitioning || songListState.isPinching

    val powerListProgress = if (powerListGestureActive) {
        songListState.transitionProgress.coerceIn(0f, 1f)
    } else {
        0f
    }

    val gestureFade = smoothStep(0.08f, 0.72f, powerListProgress)
    val gestureAlpha = 1f - gestureFade * 0.34f
    val gestureScale = 1f + gestureFade * 0.08f

    val finalAlpha = (sceneAlpha * gestureAlpha).coerceIn(0f, 1f)
    val finalScale = sceneScale * gestureScale

    val metaAlpha = when {
        enteringFolder -> smoothStep(0.50f, 0.88f, sceneProgress)
        leavingFolder -> 1f - smoothStep(0.00f, 0.18f, sceneProgress)
        else -> 1f
    }.coerceIn(0f, 1f) * gestureAlpha

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FolderHeroCoverHeight)
                .clipToBounds()
        ) {
            // 视觉层：graphicsLayer 控制 alpha，内部不再自己淡入
            CrossfadeAlbumArt(
                key = folder.coverKey,
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

            // 登记层：只给 SharedCoverOverlay 提供终点 bounds
            FolderSharedCover(
                scene = NavScene.FOLDER_HIERARCHY,
                folder = folder,
                radiusDp = 0f,
                drawContent = false,
                modifier = Modifier.fillMaxSize()
            )

            IconButton(
                onClick = {
                    coverRegistry.freeze(
                        sceneId = NavScene.FOLDER_HIERARCHY.name,
                        elementId = folder.sharedElementId
                    )
                    onBack()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 12.dp)
                    .graphicsLayer {
                        alpha = metaAlpha
                    }
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
                .height(FolderHeroMetaHeight)
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
                text = folder.name,
                color = onBg,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Text(
                text = folder.parentName,
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
                    text = "${folder.songCount} | ${formatDuration(folder.totalDurationMs)}",
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
private fun FolderSharedCover(
    scene: NavScene,
    folder: FolderGroupUi,
    radiusDp: Float,
    modifier: Modifier = Modifier,
    drawContent: Boolean = true
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val spec = LocalSharedTransitionSpec.current
    val sceneId = scene.name
    val elementId = folder.sharedElementId

    DisposableEffect(sceneId, elementId) {
        onDispose { coverRegistry.unregister(sceneId, elementId) }
    }

    val isFolderSharedTransition = isFolderCoverSharedTransition(
        fromSceneId = spec.fromSceneId,
        toSceneId = spec.toSceneId
    )

    val isEndpoint =
        spec.active &&
            isFolderSharedTransition &&
            (sceneId == spec.fromSceneId || sceneId == spec.toSceneId)

    val shouldHide = isEndpoint && coverRegistry.hasPair(
        fromSceneId = spec.fromSceneId,
        toSceneId = spec.toSceneId,
        elementId = elementId
    )

    if (drawContent) {
        CrossfadeAlbumArt(
            key = folder.coverKey,
            modifier = modifier
                .onGloballyPositioned { coordinates ->
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
                            coverKey = folder.coverKey,
                            radiusDp = radiusDp
                        )
                    )
                }
                .then(if (shouldHide) Modifier.alpha(0f) else Modifier)
                .graphicsLayer {
                    clip = true
                    shape = RoundedCornerShape(radiusDp.dp)
                },
            contentScale = ContentScale.Crop
        )
    } else {
        // 只注册 bounds，不绘制内容
        Box(
            modifier = modifier
                .onGloballyPositioned { coordinates ->
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
                            coverKey = folder.coverKey,
                            radiusDp = radiusDp
                        )
                    )
                }
        )
    }
}

@Stable
private data class FolderGroupUi(
    val path: String,
    val name: String,
    val parentName: String,
    val songs: List<AudioFile>,
    val coverKey: String,
    val totalDurationMs: Long
) {
    val songCount: Int get() = songs.size
    val sharedElementId: String get() = "cover:folder:${stablePowerListHash64(path)}"

    companion object {
        fun empty(path: String): FolderGroupUi {
            val file = File(path)
            return FolderGroupUi(
                path = path,
                name = file.name.ifBlank { path },
                parentName = file.parentFile?.name.orEmpty(),
                songs = emptyList(),
                coverKey = "",
                totalDurationMs = 0L
            )
        }
    }
}

private fun List<AudioFile>.toFolderGroups(): List<FolderGroupUi> {
    return asSequence()
        .filter { it.path.isNotBlank() }
        .groupBy { song -> File(song.path).parentFile?.absolutePath.orEmpty() }
        .filterKeys { it.isNotBlank() }
        .map { (folderPath, folderSongs) ->
            val folder = File(folderPath)
            val orderedSongs = folderSongs.sortedWith(
                compareBy<AudioFile> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy { it.displayTitle().lowercase() }
            )
            FolderGroupUi(
                path = folderPath,
                name = folder.name.ifBlank { folderPath },
                parentName = folder.parentFile?.name?.ifBlank { folder.parent.orEmpty() }.orEmpty(),
                songs = orderedSongs,
                coverKey = orderedSongs.firstOrNull()?.coverKey().orEmpty(),
                totalDurationMs = orderedSongs.sumOf { it.duration.coerceAtLeast(0L) }
            )
        }
        .sortedWith(
            compareByDescending<FolderGroupUi> { it.songCount }
                .thenBy { it.name.lowercase() }
        )
}

private fun AudioFile.coverKey(): String {
    return albumArtPath.ifBlank { path }
}

private fun AudioFile.displayTitle(): String {
    return title.ifBlank { File(path).nameWithoutExtension.ifBlank { "Unknown" } }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private const val FOLDER_ENTER_HANDOFF_START = 0.58f
private const val FOLDER_ENTER_HANDOFF_END = 0.86f

private const val FOLDER_LEAVE_HANDOFF_START = 0.04f
private const val FOLDER_LEAVE_HANDOFF_END = 0.22f

private fun isFolderCoverSharedTransition(
    fromSceneId: String,
    toSceneId: String
): Boolean {
    return (
        fromSceneId == NavScene.FOLDERS.name &&
            toSceneId == NavScene.FOLDER_HIERARCHY.name
        ) || (
        fromSceneId == NavScene.FOLDER_HIERARCHY.name &&
            toSceneId == NavScene.FOLDERS.name
        )
}

// smoothStep 已在 CollectionHeroLayout.kt 中定义为 internal
