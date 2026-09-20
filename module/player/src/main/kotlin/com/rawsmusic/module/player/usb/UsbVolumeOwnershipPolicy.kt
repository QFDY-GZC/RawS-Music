package com.rawsmusic.module.player.usb

/**
 * Chooses the owner of user-visible volume for one already-resolved USB output profile.
 *
 * Effective bit-perfect PCM never delegates user volume to the PCM data plane. If a
 * validated Feature Unit is unavailable, the digital stream stays fixed at unity.
 */
internal object UsbVolumeOwnershipPolicy {
    fun resolve(
        bitPerfect: Boolean,
        hardwareVolumeEffective: Boolean,
        dsdConversionEnabled: Boolean,
        dsdSourceDirect: Boolean,
        fixedDigitalVolume: Boolean,
    ): UsbVolumePath = when {
        fixedDigitalVolume -> UsbVolumePath.Fixed
        dsdSourceDirect && hardwareVolumeEffective -> UsbVolumePath.HardwareUserVolume
        dsdSourceDirect -> UsbVolumePath.Fixed
        bitPerfect && hardwareVolumeEffective -> UsbVolumePath.HardwareUserVolume
        bitPerfect -> UsbVolumePath.Fixed
        dsdConversionEnabled && hardwareVolumeEffective -> UsbVolumePath.HardwareUserVolume
        dsdConversionEnabled -> UsbVolumePath.Software
        hardwareVolumeEffective -> UsbVolumePath.HardwareUserVolume
        else -> UsbVolumePath.Software
    }
}
