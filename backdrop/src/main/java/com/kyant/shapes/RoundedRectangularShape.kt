package com.kyant.shapes

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Minimal local contract required by Backdrop's rounded-rectangle lens shader.
 *
 * Kept in source so RawSMusic can build offline without resolving the external
 * Shapes artifact. Implementations must report the same radii used by their
 * [Shape.createOutline] implementation.
 */
interface RoundedRectangularShape : Shape {
    fun corners(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Corners

    data class Corners(
        val topLeft: Float,
        val topRight: Float,
        val bottomRight: Float,
        val bottomLeft: Float
    )
}
