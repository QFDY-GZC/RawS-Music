package com.kyant.shapes

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * A pill/capsule whose radius is always half of its shortest side.
 *
 * Unlike the old local placeholder, this implements [RoundedRectangularShape],
 * so Backdrop's lens effect receives valid corner radii and actually executes.
 */
object Capsule : RoundedRectangularShape {
    override fun corners(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): RoundedRectangularShape.Corners {
        val radius = size.minDimension * 0.5f
        return RoundedRectangularShape.Corners(
            topLeft = radius,
            topRight = radius,
            bottomRight = radius,
            bottomLeft = radius
        )
    }

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val radius = size.minDimension * 0.5f
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                cornerRadius = CornerRadius(radius, radius)
            )
        )
    }

    operator fun invoke(): Capsule = this
}
