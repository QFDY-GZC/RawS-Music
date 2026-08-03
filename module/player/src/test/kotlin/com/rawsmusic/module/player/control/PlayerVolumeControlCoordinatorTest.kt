package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.player.usb.UsbOutputProfile
import com.rawsmusic.module.player.usb.UsbPcmOutputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVolumeControlCoordinatorTest {
    @Test
    fun androidOutputReceivesOnlyReplayGainAndDuckComposition() {
        var androidGain = -1f
        val coordinator = coordinator(
            duck = { 0.5f },
            onAndroidGain = { androidGain = it },
        )

        coordinator.applyReplayGain(
            AudioFile(trackGain = -6f, trackPeak = 1f),
            PlayerVolumeControlCoordinator.ReplayGainSettings(
                normalizationEnabled = false,
                replayGainEnabled = true,
                replayGainMode = 1,
            ),
        )

        assertTrue(coordinator.replayGainModifierForTest() in 0.50f..0.51f)
        assertTrue(androidGain in 0.25f..0.26f)
    }

    @Test
    fun transportTransitionSuppressesAllGainWrites() {
        var writes = 0
        val coordinator = coordinator(
            transitioning = { true },
            onAndroidGain = { writes++ },
        )

        coordinator.applyComposedVolume()

        assertEquals(0, writes)
    }

    @Test
    fun releasedControllerDoesNotWriteAndroidGain() {
        var writes = 0
        val coordinator = coordinator(
            released = { true },
            onAndroidGain = { writes++ },
        )

        coordinator.applyComposedVolume()

        assertEquals(0, writes)
    }


    @Test
    fun `USB hardware route applies PCM modifiers without Feature Unit writes`() {
        var pcmGain = -1f
        val coordinator = coordinator(
            usbProfile = { hardwareProfile() },
            onUsbPcmGain = { pcmGain = it },
        )

        coordinator.applyComposedVolume()

        assertTrue(pcmGain >= 0f)
    }

    private fun coordinator(
        released: () -> Boolean = { false },
        transitioning: () -> Boolean = { false },
        duck: () -> Float = { 1f },
        usbProfile: () -> UsbOutputProfile? = { null },
        onAndroidGain: (Float) -> Unit = {},
        onUsbPcmGain: (Float) -> Unit = {},
    ) = PlayerVolumeControlCoordinator(
        callbacks = PlayerVolumeControlCoordinator.Callbacks(
            isReleased = released,
            transportTransitioning = transitioning,
            currentUsbProfile = usbProfile,
            userVolume = { 0.2f },
            duckFactor = duck,
            setAndroidSoftwareGain = onAndroidGain,
            setUsbPcmGain = onUsbPcmGain,
            logDebug = {},
            logWarning = {},
        ),
    )

    private fun hardwareProfile() = UsbOutputProfile(
        exclusive = true,
        bitPerfect = false,
        hardwareVolumeRequested = true,
        hardwareVolumeValidated = true,
        targetSampleRate = 192_000,
        targetBitDepth = 24,
        targetSubslotBytes = 4,
        pcmOutputMode = UsbPcmOutputMode.PCM_24_IN_32,
        dsdConversionEnabled = false,
        dsdDoPEnabled = false,
        safeMode = false,
        noClockSet = false,
        noFeedback = false,
        noFeatureUnit = false,
        force1msPacket = false,
        preferSafeAlt = false,
        forceSoftwareVolume = false,
    )
}
