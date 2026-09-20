package com.rawsmusic.module.player.devicecontrol.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BluetoothLeAudioStandardTest {
    @Test
    fun decodesVcsVolumeState() {
        val state = BluetoothLeAudioStandard.decodeVcsVolumeState(byteArrayOf(0x80.toByte(), 0x01, 0x7f))!!
        assertEquals(128, state.volumeSetting)
        assertEquals(true, state.muted)
        assertEquals(127, state.changeCounter)
        assertNull(BluetoothLeAudioStandard.decodeVcsVolumeState(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun decodesSignedVocsOffset() {
        val state = BluetoothLeAudioStandard.decodeVocsVolumeOffsetState(byteArrayOf(0x01, 0xff.toByte(), 0x05))!!
        assertEquals(-255, state.volumeOffset)
        assertEquals(5, state.changeCounter)
    }

    @Test
    fun decodesAicsStateAndGainProperties() {
        val state = BluetoothLeAudioStandard.decodeAicsInputState(byteArrayOf((-6).toByte(), 0x00, 0x02, 0x09))!!
        val properties = BluetoothLeAudioStandard.decodeAicsGainSettingProperties(byteArrayOf(0x05, (-20).toByte(), 20))!!
        assertEquals(-6, state.gainSetting)
        assertEquals(BluetoothLeAudioStandard.AicsGainMode.MANUAL, state.gainMode)
        assertEquals(0.5, properties.stepDb)
        assertEquals(-3.0, properties.rawToDb(state.gainSetting))
        assertEquals(-10.0, properties.rawToDb(properties.minimumGainSetting))
        assertEquals(10.0, properties.rawToDb(properties.maximumGainSetting))
    }
}
