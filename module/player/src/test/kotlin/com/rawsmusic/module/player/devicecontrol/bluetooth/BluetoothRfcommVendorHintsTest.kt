package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothRfcommVendorHintsTest {
    @Test
    fun opoHintAddsGaiaButExcludesFastPair() {
        val unknown = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val candidates = BluetoothRfcommVendorHints.candidates(
            listOf(
                OPO_BLE_CONTROL_SERVICE_UUID,
                BluetoothRfcommVendorHints.GOOGLE_FAST_PAIR_MESSAGE_STREAM_UUID,
                unknown,
            ),
        )

        val uuids = candidates.map { it.uuid }
        assertTrue(OPO_BLE_CONTROL_SERVICE_UUID in uuids)
        assertTrue(unknown in uuids)
        assertTrue(BluetoothRfcommVendorHints.OPO_GAIA_SPP_SERVICE_UUID in uuids)
        assertFalse(BluetoothRfcommVendorHints.GOOGLE_FAST_PAIR_MESSAGE_STREAM_UUID in uuids)
        assertEquals(3, uuids.size)
    }
}
