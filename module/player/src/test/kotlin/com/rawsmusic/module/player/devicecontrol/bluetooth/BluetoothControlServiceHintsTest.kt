package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothControlServiceHintsTest {
    @Test
    fun `vendor hints keep OPPO custom UUIDs and drop classic audio profiles`() {
        val opo = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5")
        val vendor9999 = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val a2dpSink = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")
        val avrcp = UUID.fromString("0000110e-0000-1000-8000-00805f9b34fb")
        val hfp = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        assertEquals(
            listOf(opo, vendor9999),
            filterBluetoothVendorServiceHints(listOf(opo, a2dpSink, avrcp, hfp, vendor9999)),
        )
    }

    @Test
    fun `OPO 079A prefers companion-first control lookup`() {
        val opo = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5")
        val unknown = UUID.fromString("12345678-1234-5678-1234-567812345678")
        assertTrue(prefersBluetoothControlCompanionLookup(listOf(opo)))
        assertFalse(prefersBluetoothControlCompanionLookup(listOf(unknown)))
    }
}
