package com.rawsmusic.ui.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SettingsSearchConnectionTest {
    private fun ball(vararg values: Float) = SettingsSearchConnection.Ball(values)

    @Test fun allCapturedDensityScaleAndGapFixturesMatchNativeOutput() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/settings/search-connection-native.csv"))
        stream.bufferedReader().useLines { lines -> lines.forEachIndexed { row, line ->
            val values = line.split(',').map(String::toFloat)
            val result = SettingsSearchConnection.parameters(
                SettingsSearchConnection.Ball(values.subList(2,10).toFloatArray()),
                SettingsSearchConnection.Ball(values.subList(10,18).toFloatArray()),
                values[0],values[1].toInt())
            result.forEachIndexed { index, actual ->
                val expected = values[18+index]
                org.junit.Assert.assertEquals("Native fixture $row component $index",expected,actual,
                    .0002f*kotlin.math.max(1f,kotlin.math.abs(expected)))
            }
        } }
    }

    @Test fun scaledCancelPreservesNativeDeformationAndColourHandoff() {
        val expected = floatArrayOf(316f, 20f, 1f, 0f, 171f, 20f, 171f, 20f, 20f,
            8f, 15f, 1.3580999f, 8f, 15f, 1.3580999f, .9670876f, 0f,
            10.294646f, 8.7162f, .5233952f, .339525f, 1f, 0f, 0f)
        assertArrayEquals(expected, SettingsSearchConnection.parameters(
            ball(160f,20f,160f,20f,20f,1f,1f,1f),
            ball(327f,20f,30f,20f,20f,1f,2f,2f), 1f), .0002f)
    }

    @Test fun highDensityFourDpGapUsesPhysicalPixelRamps() {
        val expected = floatArrayOf(1127f,70f,1f,0f,672f,70f,672f,70f,70f,
            36f,105f,13.678658f,36f,105f,13.678658f,.9964687f,.352f,
            11.740632f,11.357317f,.6809389f,.977047f,1f,0f,0f)
        assertArrayEquals(expected, SettingsSearchConnection.parameters(
            ball(560f,70f,560f,70f,70f,1f,1f,1f),
            ball(1239f,70f,105f,70f,70f,1f,1f,1f), 3.5f), .0003f)
    }

    @Test fun separatedCapsulesDoNotRetainAConnection() {
        assertArrayEquals(FloatArray(24), SettingsSearchConnection.parameters(
            ball(560f,70f,560f,70f,70f,1f,1f,1f),
            ball(1260f,70f,105f,70f,70f,1f,1f,1f), 3.5f), 0f)
    }

    @Test fun missingOrInvalidCapsulesNeverPublishNonFiniteUniforms() {
        val first = ball(160f,20f,160f,20f,20f,1f,1f,1f)
        for (second in listOf(ball(0f,0f,0f,0f,0f,1f,1f,1f),
            ball(0f,0f,30f,20f,20f,1f,0f,1f),
            ball(Float.NaN,0f,30f,20f,20f,1f,1f,1f))) {
            assertArrayEquals(FloatArray(24), SettingsSearchConnection.parameters(first, second, 2f), 0f)
        }
    }
}
