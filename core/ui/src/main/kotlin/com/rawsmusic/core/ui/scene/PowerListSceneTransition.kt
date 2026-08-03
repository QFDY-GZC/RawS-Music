package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import kotlin.math.roundToInt

data class PowerListSceneItemSnapshot(
    val sceneId: String,
    val itemId: String,
    val boundsInWindow: Rect
)

data class PowerListSceneAnchorPair(
    val itemId: String,
    val from: PowerListSceneItemSnapshot,
    val to: PowerListSceneItemSnapshot
)

@Stable
class PowerListSceneTransitionRegistry {
    private val live = mutableStateMapOf<String, PowerListSceneItemSnapshot>()
    private val frozen = mutableStateMapOf<String, PowerListSceneItemSnapshot>()
    private val remembered = mutableMapOf<String, PowerListSceneItemSnapshot>()
    private val returnTargets = mutableMapOf<String, PowerListSceneItemSnapshot>()
    private val preparedTransitionKey = mutableStateOf("")
    private val preparedAnchorItemId = mutableStateOf("")
    private val preparedAnchorPair = mutableStateOf<PowerListSceneAnchorPair?>(null)

    private fun key(sceneId: String, itemId: String): String = "$sceneId::$itemId"

    fun register(snapshot: PowerListSceneItemSnapshot) {
        if (snapshot.sceneId.isBlank() || snapshot.itemId.isBlank()) return
        val key = key(snapshot.sceneId, snapshot.itemId)
        val previous = live[key]
        if (previous == null || !previous.boundsInWindow.nearlyEquals(snapshot.boundsInWindow)) {
            live[key] = snapshot
        }
        remembered[key] = snapshot
    }

    fun unregister(sceneId: String, itemId: String) {
        live.remove(key(sceneId, itemId))
    }

    fun hasScene(sceneId: String): Boolean {
        val prefix = "$sceneId::"
        return live.keys.any { it.startsWith(prefix) }
    }

    fun hasRememberedScene(sceneId: String): Boolean {
        val prefix = "$sceneId::"
        return remembered.keys.any { it.startsWith(prefix) }
    }

    fun freezeTransition(
        transitionKey: String,
        fromSceneId: String,
        toSceneId: String,
        anchorItemId: String,
        allowRememberedTarget: Boolean = false,
        anchorFromBounds: Rect? = null,
        anchorToBounds: Rect? = null
    ) {
        frozen.clear()
        val fromPrefix = "$fromSceneId::"
        val toPrefix = "$toSceneId::"
        live.forEach { (key, snapshot) ->
            if (key.startsWith(fromPrefix) || (!allowRememberedTarget && key.startsWith(toPrefix))) {
                frozen[key] = snapshot
            }
        }
        if (allowRememberedTarget) {
            returnTargets.forEach { (key, snapshot) ->
                if (key.startsWith(toPrefix) && key !in frozen) {
                    frozen[key] = snapshot
                }
            }
            live.forEach { (key, snapshot) ->
                if (key.startsWith(toPrefix) && key !in frozen) frozen[key] = snapshot
            }
            remembered.forEach { (key, snapshot) ->
                if (key.startsWith(toPrefix) && key !in frozen) frozen[key] = snapshot
            }
        } else {
            // Retain the original target holder position for the reverse path.
            // Keep an independent copy because ring-slot reuse can later overwrite live/remembered.
            frozen.forEach { (key, snapshot) ->
                if (key.startsWith(fromPrefix)) returnTargets[key] = snapshot
            }
        }
        preparedTransitionKey.value = transitionKey
        preparedAnchorItemId.value = anchorItemId
        preparedAnchorPair.value = if (anchorFromBounds != null && anchorToBounds != null) {
            PowerListSceneAnchorPair(
                itemId = anchorItemId,
                from = PowerListSceneItemSnapshot(fromSceneId, anchorItemId, anchorFromBounds),
                to = PowerListSceneItemSnapshot(toSceneId, anchorItemId, anchorToBounds)
            )
        } else {
            null
        }
    }

    fun isPrepared(transitionKey: String): Boolean {
        return transitionKey.isNotBlank() && preparedTransitionKey.value == transitionKey
    }

    fun getFrozen(sceneId: String, itemId: String): PowerListSceneItemSnapshot? {
        return frozen[key(sceneId, itemId)]
    }

    fun getAnchorPair(
        transitionKey: String,
        fromSceneId: String,
        toSceneId: String
    ): PowerListSceneAnchorPair? {
        if (!isPrepared(transitionKey)) return null
        preparedAnchorPair.value?.let { return it }
        val itemId = preparedAnchorItemId.value.takeIf { it.isNotBlank() } ?: return null
        val from = getFrozen(fromSceneId, itemId) ?: return null
        val to = getFrozen(toSceneId, itemId) ?: return null
        return PowerListSceneAnchorPair(itemId = itemId, from = from, to = to)
    }

    fun clearTransition() {
        if (preparedTransitionKey.value.isEmpty() && frozen.isEmpty()) return
        preparedTransitionKey.value = ""
        preparedAnchorItemId.value = ""
        preparedAnchorPair.value = null
        frozen.clear()
    }
}

val LocalPowerListSceneTransitionRegistry = staticCompositionLocalOf {
    PowerListSceneTransitionRegistry()
}

fun Modifier.powerListSceneTransitionItem(
    sceneId: String,
    itemId: String
): Modifier = composed {
    if (sceneId.isBlank() || itemId.isBlank()) return@composed this

    val registry = LocalPowerListSceneTransitionRegistry.current
    val spec = LocalSharedTransitionSpec.current
    DisposableEffect(registry, sceneId, itemId) {
        onDispose { registry.unregister(sceneId, itemId) }
    }

    val prepared = spec.active && registry.isPrepared(spec.transitionKey)
    val self = if (prepared) registry.getFrozen(sceneId, itemId) else null
    val isSource = prepared && sceneId == spec.fromSceneId
    val isTarget = prepared && sceneId == spec.toSceneId
    val anchor = if (prepared) {
        registry.getAnchorPair(spec.transitionKey, spec.fromSceneId, spec.toSceneId)
    } else {
        null
    }
    val progress = spec.progress.coerceIn(0f, 1f)

    this
        .onGloballyPositioned { coordinates ->
            if (!prepared) {
                val bounds = coordinates.boundsInWindow()
                registry.register(
                    PowerListSceneItemSnapshot(
                        sceneId = sceneId,
                        itemId = itemId,
                        boundsInWindow = bounds
                    )
                )
            }
        }
        .graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            when {
                self == null || anchor == null || (!isSource && !isTarget) -> Unit
                isSource -> applyPowerListAffine(
                    own = self.boundsInWindow,
                    anchorFrom = anchor.from.boundsInWindow,
                    anchorTo = anchor.to.boundsInWindow,
                    progress = progress
                )
                isTarget -> applyPowerListAffine(
                    own = self.boundsInWindow,
                    anchorFrom = anchor.to.boundsInWindow,
                    anchorTo = anchor.from.boundsInWindow,
                    progress = 1f - progress
                )
            }
        }
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyPowerListAffine(
    own: Rect,
    anchorFrom: Rect,
    anchorTo: Rect,
    progress: Float
) {
    val anchorScale = anchorTo.width / anchorFrom.width.coerceAtLeast(1f)
    val mappedWidth = own.width * anchorScale
    val mappedHeight = own.height * anchorScale
    val mappedCenterX = anchorTo.center.x + (own.center.x - anchorFrom.center.x) * anchorScale
    val mappedCenterY = anchorTo.center.y + (own.center.y - anchorFrom.center.y) * anchorScale
    val mappedLeft = mappedCenterX - mappedWidth / 2f
    val mappedTop = mappedCenterY - mappedHeight / 2f
    val interpolatedLeft = own.left + (mappedLeft - own.left) * progress
    val interpolatedTop = own.top + (mappedTop - own.top) * progress
    val interpolatedWidth = (own.width + (mappedWidth - own.width) * progress)
        .roundToInt()
        .coerceAtLeast(1)
    val interpolatedHeight = (own.height + (mappedHeight - own.height) * progress)
        .roundToInt()
        .coerceAtLeast(1)

    translationX = (interpolatedLeft - own.left).roundToInt().toFloat()
    translationY = (interpolatedTop - own.top).roundToInt().toFloat()
    scaleX = interpolatedWidth / own.width.coerceAtLeast(1f)
    scaleY = interpolatedHeight / own.height.coerceAtLeast(1f)
    alpha = 1f
}

private fun Rect.nearlyEquals(other: Rect, tolerance: Float = 0.5f): Boolean {
    return abs(left - other.left) <= tolerance &&
        abs(top - other.top) <= tolerance &&
        abs(right - other.right) <= tolerance &&
        abs(bottom - other.bottom) <= tolerance
}
