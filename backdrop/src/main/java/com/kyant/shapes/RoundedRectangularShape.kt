package com.kyant.shapes

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

class RoundedRectangularShape(
    private val cornerRadius: Dp
) : Shape {

    private val delegateShape = RoundedCornerShape(cornerRadius)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        return delegateShape.createOutline(size, layoutDirection, density)
    }

    fun corners(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): CornerRadii {
        val radiusPx = with(density) { cornerRadius.toPx() }
        return CornerRadii(
            topLeft = radiusPx,
            topRight = radiusPx,
            bottomRight = radiusPx,
            bottomLeft = radiusPx
        )
    }
}
