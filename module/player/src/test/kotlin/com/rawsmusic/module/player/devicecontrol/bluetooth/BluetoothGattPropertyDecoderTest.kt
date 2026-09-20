package com.rawsmusic.module.player.devicecontrol.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothGattPropertyDecoderTest {
    @Test
    fun decodesReadNotifyAndWriteBitsWithoutInventingOthers() {
        val properties = BluetoothGattPropertyDecoder.decode(0x02 or 0x08 or 0x10)
        assertEquals(3, properties.size)
        assertTrue(BluetoothGattInventory.Property.READ in properties)
        assertTrue(BluetoothGattInventory.Property.WRITE in properties)
        assertTrue(BluetoothGattInventory.Property.NOTIFY in properties)
        assertFalse(BluetoothGattInventory.Property.INDICATE in properties)
    }
}
