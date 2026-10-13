package com.rawsmusic.core.ui.scene

import androidx.compose.ui.geometry.Rect
import kotlin.math.max

/** Frozen rectangle endpoint; only its scalar is sampled while a transition is running. */
internal data class CollectionRectMotion(
    val endpointScale: Float,
    val endpointX: Float,
    val endpointY: Float,
) {
    fun scale(amount: Float): Float = 1f + (endpointScale - 1f) * amount.coerceIn(0f, 1f)
    fun offsetX(amount: Float): Float = endpointX * amount.coerceIn(0f, 1f)
    fun offsetY(amount: Float): Float = endpointY * amount.coerceIn(0f, 1f)
}

internal fun collectionDetailRectMotion(item: Rect, width: Float, height: Float): CollectionRectMotion {
    // The captured rectangle extends by the item's origin on both axes. This is not a
    // cover-only fit: text and other children travel with their complete holder.
    val rectWidth = item.right
    val rectHeight = item.bottom
    val scale = if (height > width && rectWidth < width) rectWidth / width else rectHeight / height
    return CollectionRectMotion(scale, item.left, item.top)
}

internal fun collectionGridRectMotion(item: Rect, width: Float, height: Float): CollectionRectMotion {
    val expansion = max(2f, width / item.right.coerceAtLeast(1f))
    val expandedWidth = (width * expansion + 0.5f).toInt()
    val expandedHeight = (height * expansion + 0.5f).toInt()
    val scale = if (height > width && expandedWidth < width) expandedWidth / width else expandedHeight / height
    return CollectionRectMotion(scale, -(item.left * expansion).toInt().toFloat(), -(item.top * expansion).toInt().toFloat())
}

internal fun RetainedSceneItemTransform.withCollectionRectMotion(
    motion: CollectionRectMotion,
    amount: () -> Float,
): RetainedSceneItemTransform = copy(
    pivotX = 0f, pivotY = 0f,
    rectangleMapped = true,
    scaleProvider = { motion.scale(amount()) },
    translationXProvider = { motion.offsetX(amount()) },
    translationYProvider = { motion.offsetY(amount()) },
)
