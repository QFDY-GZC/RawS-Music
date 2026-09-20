package com.rawsmusic.module.player.devicecontrol.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRouteIdentityMatcherTest {
    private val validAddress: (String) -> Boolean = { value ->
        Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$").matches(value)
    }

    @Test
    fun routeAddressWinsOverNames() {
        val result = BluetoothRouteIdentityMatcher.match(
            route = BluetoothAudioRouteSnapshot(7, 8, "Wrong Name", "AA:BB:CC:DD:EE:02"),
            knownDevices = listOf(
                BluetoothKnownDeviceIdentity("AA:BB:CC:DD:EE:01", "Wrong Name"),
                BluetoothKnownDeviceIdentity("AA:BB:CC:DD:EE:02", "Correct Device"),
            ),
            isValidBluetoothAddress = validAddress,
        )
        assertTrue(result is BluetoothRouteIdentityMatch.Matched)
        result as BluetoothRouteIdentityMatch.Matched
        assertEquals("AA:BB:CC:DD:EE:02", result.device.address)
        assertEquals(BluetoothRouteIdentityMatch.Confidence.ROUTE_ADDRESS, result.confidence)
    }

    @Test
    fun uniqueBondedNameIsSafeFallback() {
        val result = BluetoothRouteIdentityMatcher.match(
            route = BluetoothAudioRouteSnapshot(7, 26, "Earbuds X", "ble:opaque-route"),
            knownDevices = listOf(
                BluetoothKnownDeviceIdentity("AA:BB:CC:DD:EE:01", "Earbuds X"),
                BluetoothKnownDeviceIdentity("AA:BB:CC:DD:EE:02", "Other"),
            ),
            isValidBluetoothAddress = validAddress,
        )
        result as BluetoothRouteIdentityMatch.Matched
        assertEquals("AA:BB:CC:DD:EE:01", result.device.address)
        assertEquals(BluetoothRouteIdentityMatch.Confidence.UNIQUE_BONDED_NAME, result.confidence)
    }

    @Test
    fun ambiguousNameIsNeverGuessed() {
        val result = BluetoothRouteIdentityMatcher.match(
            route = BluetoothAudioRouteSnapshot(7, 8, "Earbuds X", null),
            knownDevices = listOf(
                BluetoothKnownDeviceIdentity("AA:BB:CC:DD:EE:01", "Earbuds X"),
                BluetoothKnownDeviceIdentity("AA:BB:CC:DD:EE:02", "Earbuds X"),
            ),
            isValidBluetoothAddress = validAddress,
        )
        assertEquals(
            BluetoothRouteIdentityMatch.Unresolved("ambiguous_bonded_device_name"),
            result,
        )
    }
}
