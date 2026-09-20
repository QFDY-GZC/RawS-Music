package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.util.UUID

/**
 * Whitelist of standard Bluetooth SIG characteristics that RawSMusic may read during capability probe.
 * Unknown vendor characteristics are deliberately excluded even when they advertise READ.
 */
internal object BluetoothStandardReadPlan {
    enum class Kind {
        VCS_VOLUME_STATE,
        VCS_VOLUME_FLAGS,
        VOCS_VOLUME_OFFSET_STATE,
        VOCS_AUDIO_LOCATION,
        VOCS_OUTPUT_DESCRIPTION,
        AICS_INPUT_STATE,
        AICS_GAIN_SETTING_PROPERTIES,
        AICS_INPUT_TYPE,
        AICS_INPUT_STATUS,
        AICS_INPUT_DESCRIPTION,
    }

    data class Target(
        val serviceIndex: Int,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val kind: Kind,
    )

    fun fromInventory(inventory: BluetoothGattInventory): List<Target> = buildList {
        inventory.services.forEachIndexed { serviceIndex, service ->
            when (service.uuid) {
                BluetoothLeAudioStandard.VCS_SERVICE -> {
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.VOLUME_STATE, Kind.VCS_VOLUME_STATE)
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.VOLUME_FLAGS, Kind.VCS_VOLUME_FLAGS)
                }
                BluetoothLeAudioStandard.VOCS_SERVICE -> {
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.VOLUME_OFFSET_STATE, Kind.VOCS_VOLUME_OFFSET_STATE)
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.AUDIO_LOCATION, Kind.VOCS_AUDIO_LOCATION)
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.AUDIO_OUTPUT_DESCRIPTION, Kind.VOCS_OUTPUT_DESCRIPTION)
                }
                BluetoothLeAudioStandard.AICS_SERVICE -> {
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.AUDIO_INPUT_STATE, Kind.AICS_INPUT_STATE)
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.GAIN_SETTING_PROPERTIES, Kind.AICS_GAIN_SETTING_PROPERTIES)
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.AUDIO_INPUT_TYPE, Kind.AICS_INPUT_TYPE)
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.AUDIO_INPUT_STATUS, Kind.AICS_INPUT_STATUS)
                    addIfReadable(serviceIndex, service, BluetoothLeAudioStandard.AUDIO_INPUT_DESCRIPTION, Kind.AICS_INPUT_DESCRIPTION)
                }
            }
        }
    }

    private fun MutableList<Target>.addIfReadable(
        serviceIndex: Int,
        service: BluetoothGattInventory.Service,
        characteristicUuid: UUID,
        kind: Kind,
    ) {
        val characteristic = service.characteristics.firstOrNull { it.uuid == characteristicUuid } ?: return
        if (BluetoothGattInventory.Property.READ !in characteristic.properties) return
        add(Target(serviceIndex, service.uuid, characteristicUuid, kind))
    }
}
