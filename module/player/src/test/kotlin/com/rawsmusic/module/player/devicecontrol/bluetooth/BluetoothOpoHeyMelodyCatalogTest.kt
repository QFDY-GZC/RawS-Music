package com.rawsmusic.module.player.devicecontrol.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BluetoothOpoHeyMelodyCatalogTest {
    @Test
    fun signedHeyMelodyCatalogKeepsAll82RootProfilesAndBothRfcommFamilies() {
        val profiles = BluetoothOpoDeviceProfiles.allProfiles()
        assertEquals(82, profiles.size)
        assertEquals(82, profiles.map { it.productIdHex }.distinct().size)
        assertEquals(63, profiles.count { it.serviceUuid == BluetoothOpoDeviceProfiles.SERVICE_UUID_079A })
        assertEquals(19, profiles.count { it.serviceUuid == BluetoothOpoDeviceProfiles.SERVICE_UUID_1107 })
    }

    @Test
    fun free4KeepsVerifiedSixBandPromptAndFeatureCapabilities() {
        val profile = BluetoothOpoDeviceProfiles.resolve("068C10")
        assertNotNull(profile)
        assertEquals(listOf(62, 250, 1000, 4000, 8000, 16000), profile.customEqFrequenciesHz)
        assertEquals(listOf(0, 1), profile.spatialTypes)
        assertEquals(1, profile.promptVolumeMin)
        assertEquals(10, profile.promptVolumeMax)
        assertTrue(profile.isFunctionEnabled("wearDetection"))
        assertTrue(profile.isFunctionEnabled("gameMode"))
        assertTrue(profile.isFunctionEnabled("multiDevicesConnect"))
        assertTrue(profile.isFunctionEnabled("highToneQuality"))
        assertTrue(profile.isFunctionEnabled("controlAutoVolumeSupport"))
        assertFalse(profile.isFunctionEnabled("headSetSoundRecord")) // whitelist explicitly says 0
    }

    @Test
    fun newerTenBandProfileAndLegacy1107ProfileAreNotCollapsedIntoFree4() {
        val air5Pro = BluetoothOpoDeviceProfiles.resolve("06C410")
        assertNotNull(air5Pro)
        assertEquals(listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000), air5Pro.customEqFrequenciesHz)
        assertEquals(2, air5Pro.promptVolumeMin)
        assertEquals(10, air5Pro.promptVolumeMax)

        val encoFree = BluetoothOpoDeviceProfiles.resolve("060410")
        assertNotNull(encoFree)
        assertEquals(BluetoothOpoDeviceProfiles.SERVICE_UUID_1107, encoFree.serviceUuid)
    }
    @Test
    fun ancRuntimeSupportBitmapOnlyGatesWhitelistModesMarkedDecideByEarDevice() {
        val free4 = BluetoothOpoDeviceProfiles.resolve("068C10")
        assertNotNull(free4)
        assertTrue(free4.ancModes.none { it.decideByEarDevice })
        assertEquals(
            listOf(4, 5, 6, 7, 11, 8, 3),
            free4.ancModesForRuntime(setOf(1, 2)).map { it.protocolIndex },
        )
        assertTrue(free4.isAncModeRuntimeSupported(8, setOf(1, 2)))

        val free2 = BluetoothOpoDeviceProfiles.resolve("062010")
        assertNotNull(free2)
        assertTrue(free2.ancModes.first { it.protocolIndex == 3 }.decideByEarDevice)
        assertEquals(listOf(0, 1, 2), free2.ancModesForRuntime(emptySet()).map { it.protocolIndex })
        assertEquals(listOf(0, 1, 2, 3), free2.ancModesForRuntime(setOf(3)).map { it.protocolIndex })
        assertFalse(free2.isAncModeRuntimeSupported(3, emptySet()))
        assertTrue(free2.isAncModeRuntimeSupported(3, setOf(3)))
    }

}
