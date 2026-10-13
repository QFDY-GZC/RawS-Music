package com.rawsmusic.core.ui.scene.pages

internal data class FolderHierarchyCoverEndpoint(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val radiusDp: Float,
    val coverKey: String,
    val folderId: Long,
    val promotedBitmap: android.graphics.Bitmap? = null,
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)

    fun unscaledAround(scale: Float, pivotX: Float, pivotY: Float): FolderHierarchyCoverEndpoint {
        val safeScale = scale.coerceIn(0.5f, 1.5f)
        if (kotlin.math.abs(safeScale - 1f) < 0.0001f) return this
        // CollectionHero reports positionInWindow plus the untransformed coordinates.size.
        // Undo the role transform on the origin only; dividing the local size again doubles
        // the header when CURRENT is prepared at scale=0.5.
        val x = pivotX + (left - pivotX) / safeScale
        val y = pivotY + (top - pivotY) / safeScale
        return copy(left = x, top = y, right = x + width, bottom = y + height)
    }
}
