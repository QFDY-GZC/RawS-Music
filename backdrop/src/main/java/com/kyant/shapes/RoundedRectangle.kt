package com.kyant.shapes

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceIn

/** Source-local rounded rectangle compatible with Backdrop's lens shader. */
class RoundedRectangle(
    val cornerRadius: Dp
) : RoundedRectangularShape {
    override fun corners(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): RoundedRectangularShape.Corners {
        val radius = with(density) { cornerRadius.toPx() }
            .fastCoerceIn(0f, size.minDimension * 0.5f)
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
        val radius = with(density) { cornerRadius.toPx() }
            .fastCoerceIn(0f, size.minDimension * 0.5f)
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                cornerRadius = CornerRadius(radius, radius)
            )
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is RoundedRectangle && cornerRadius == other.cornerRadius)

    override fun hashCode(): Int = cornerRadius.hashCode()

    override fun toString(): String = "RoundedRectangle(cornerRadius=$cornerRadius)"
}
