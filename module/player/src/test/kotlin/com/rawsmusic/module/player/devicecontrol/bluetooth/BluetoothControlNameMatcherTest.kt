package com.rawsmusic.module.player.devicecontrol.bluetooth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothControlNameMatcherTest {
    @Test
    fun `exact and LE-suffixed control names match`() {
        assertTrue(bluetoothControlNamesMatch("OPPO Enco Free4", "OPPO Enco Free4"))
        assertTrue(bluetoothControlNamesMatch("OPPO Enco Free4", "OPPO Enco Free4 LE"))
    }

    @Test
    fun `nearby unrelated devices never match`() {
        assertFalse(bluetoothControlNamesMatch("OPPO Enco Free4", "OPPO Enco Air4"))
        assertFalse(bluetoothControlNamesMatch("OPPO Enco Free4", "Nearby Speaker"))
        assertFalse(bluetoothControlNamesMatch("AB", "AB LE"))
    }
}
