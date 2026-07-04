package com.kyant.shapes

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

object Capsule : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = minOf(size.width, size.height) / 2f
        return Outline.Rounded(
            RoundRect(size.toRect(), CornerRadius(radius, radius))
        )
    }

    operator fun invoke() = this
}
