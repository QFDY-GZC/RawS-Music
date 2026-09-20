package com.rawsmusic.module.player.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqPresetTest {
    @Test
    fun `parses current AutoEq parametric text and preserves deep negative preamp`() {
        val preset = AutoEqPreset.parse(
            name = "Test Headphone",
            source = "Test Source",
            deviceType = "711 in-ear",
            originPath = "results/Test Source/711 in-ear/Test Headphone/Test Headphone ParametricEQ.txt",
            text = """
                Preamp: -18.5 dB
                Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
                Filter 2: ON PK Fc 1000 Hz Gain -2.5 dB Q 1.41
                Filter 3: OFF PK Fc 2000 Hz Gain 3.0 dB Q 2.00
                Filter 4: ON HSC Fc 10000 Hz Gain 1.5 dB Q 0.70
            """.trimIndent(),
        )
        assertNotNull(preset)
        preset!!
        assertEquals(-18.5f, preset.safePreamp, 0.0001f)
        assertEquals(3, preset.filters.size)
        assertEquals("711 in-ear", preset.deviceType)
        assertTrue(preset.cacheIdentity.startsWith("results/"))
    }

    @Test
    fun `json round trip keeps source rig and path identity`() {
        val original = AutoEqPreset(
            name = "Same Model",
            source = "oratory1990",
            deviceType = "over-ear",
            originPath = "results/oratory1990/over-ear/Same Model/Same Model ParametricEQ.txt",
            preamp = -14f,
            filters = listOf(AutoEqFilter("PK", 1000f, 2f, 1.4f)),
        )
        val restored = AutoEqPreset.fromJson(original.toJson())
        assertNotNull(restored)
        assertEquals(original.cacheIdentity, restored!!.cacheIdentity)
        assertEquals(-14f, restored.safePreamp, 0.0001f)
    }
    @Test
    fun `same headphone from different measurements has distinct cache identity`() {
        val a = AutoEqPreset(
            name = "Same Model",
            source = "crinacle",
            deviceType = "711 in-ear",
            originPath = "results/crinacle/711 in-ear/Same Model/Same Model ParametricEQ.txt",
        )
        val b = AutoEqPreset(
            name = "Same Model",
            source = "Rtings",
            deviceType = "Bruel & Kjaer 5128 in-ear",
            originPath = "results/Rtings/Bruel & Kjaer 5128 in-ear/Same Model/Same Model ParametricEQ.txt",
        )
        assertTrue(a.cacheIdentity != b.cacheIdentity)
    }

    @Test
    fun `preamp parser accepts unit casing and rejects malformed numeric value`() {
        val uppercaseUnit = AutoEqPreset.parse(
            name = "Upper",
            text = """
                Preamp: -7.25 DB
                Filter 1: ON PK Fc 1000 Hz Gain 2.0 dB Q 1.00
            """.trimIndent(),
        )
        assertNotNull(uppercaseUnit)
        assertEquals(-7.25f, uppercaseUnit!!.safePreamp, 0.0001f)

        val malformed = AutoEqPreset.parse(
            name = "Malformed",
            text = """
                Preamp: nope dB
                Filter 1: ON PK Fc 1000 Hz Gain 2.0 dB Q 1.00
            """.trimIndent(),
        )
        assertTrue(malformed == null)
    }

}
