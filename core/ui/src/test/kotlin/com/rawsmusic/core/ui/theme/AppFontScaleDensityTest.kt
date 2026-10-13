package com.rawsmusic.core.ui.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class AppFontScaleDensityTest {
    @Test
    fun appFontScaleIsAppliedAfterThePlatformFontConversion() {
        val platformDensity = object : Density {
            override val density = 2f
            override val fontScale = 1.4f

            override fun TextUnit.toDp(): Dp {
                // Stand-in for Android's non-linear SP conversion above its normal-size range.
                val nonlinearExtra = (value - 16f).coerceAtLeast(0f) * 0.25f
                return (value * fontScale + nonlinearExtra).dp
            }
        }

        val scaled = platformDensity.withAppFontScale(1.2f)

        assertEquals(69.6f, with(scaled) { 20.sp.toPx() }, 0.001f)
        assertEquals(2f, scaled.density, 0.001f)
        assertEquals(1.68f, scaled.fontScale, 0.001f)
    }

    @Test
    fun defaultAppScaleKeepsTheOriginalPlatformDensityInstance() {
        val platformDensity = Density(density = 2f, fontScale = 1.3f)

        assertEquals(platformDensity, platformDensity.withAppFontScale(1f))
    }
}
