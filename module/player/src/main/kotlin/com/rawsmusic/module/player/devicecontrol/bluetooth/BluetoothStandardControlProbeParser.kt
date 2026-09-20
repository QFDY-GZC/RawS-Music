package com.rawsmusic.module.player.devicecontrol.bluetooth

import com.rawsmusic.module.player.devicecontrol.DeviceChoiceControl
import com.rawsmusic.module.player.devicecontrol.DeviceChoiceOption
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlId
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceNumericControl
import com.rawsmusic.module.player.devicecontrol.DeviceNumericRange
import com.rawsmusic.module.player.devicecontrol.DeviceToggleControl

/** Maps safe standard Bluetooth reads into the same dynamic capability model used by USB. */
internal object BluetoothStandardControlProbeParser {
    fun parse(
        device: DeviceControlDevice,
        generation: Long,
        data: BluetoothStandardProbeData,
    ): DeviceControlSnapshot {
        val values = data.values.associateBy { it.target.serviceIndex to it.target.kind }
        val capabilities = buildList {
            data.inventory.services.forEachIndexed { serviceIndex, service ->
                val instance = serviceInstanceOrdinal(data.inventory, serviceIndex)
                when (service.uuid) {
                    BluetoothLeAudioStandard.VCS_SERVICE -> parseVcs(serviceIndex, service, instance, values)?.let(::add)
                    BluetoothLeAudioStandard.VOCS_SERVICE -> parseVocs(serviceIndex, service, instance, values)?.let(::add)
                    BluetoothLeAudioStandard.AICS_SERVICE -> addAll(parseAics(serviceIndex, service, instance, values))
                }
            }
        }
        val hadStandardReadTargets = BluetoothStandardReadPlan.fromInventory(data.inventory).isNotEmpty()
        val state = when {
            capabilities.isNotEmpty() -> DeviceControlSnapshot.ProbeState.READY
            hadStandardReadTargets && data.diagnostics.isNotEmpty() -> DeviceControlSnapshot.ProbeState.ERROR
            else -> DeviceControlSnapshot.ProbeState.UNSUPPORTED
        }
        val inventoryDiagnostics = data.inventory.toDiagnosticLines()
        val diagnostics = (inventoryDiagnostics + data.diagnostics).distinct()
        return DeviceControlSnapshot(
            generation = generation,
            device = device,
            capabilities = capabilities,
            probeState = state,
            message = data.diagnostics.takeIf { it.isNotEmpty() }?.joinToString(";"),
            diagnostics = diagnostics,
        )
    }

    private fun parseVcs(
        serviceIndex: Int,
        service: BluetoothGattInventory.Service,
        serviceInstance: Int,
        values: Map<Pair<Int, BluetoothStandardReadPlan.Kind>, BluetoothStandardReadValue>,
    ): DeviceControlCapability.Volume? {
        val stateBytes = values[serviceIndex to BluetoothStandardReadPlan.Kind.VCS_VOLUME_STATE]?.value ?: return null
        val state = BluetoothLeAudioStandard.decodeVcsVolumeState(stateBytes) ?: return null
        val writable = hasWriteControlPoint(service, BluetoothLeAudioStandard.VOLUME_CONTROL_POINT)
        val access = if (writable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY
        val address = standardAddress(
            BluetoothLeAudioStandard.VCS_SERVICE,
            BluetoothLeAudioStandard.VOLUME_CONTROL_POINT,
            serviceInstance,
        )
        val suffix = instanceSuffix(serviceInstance)
        return DeviceControlCapability.Volume(
            id = DeviceControlId("bt.vcs.$serviceInstance.volume"),
            label = "Bluetooth volume$suffix",
            channels = listOf(
                DeviceNumericControl(
                    id = DeviceControlId("bt.vcs.$serviceInstance.volume.setting"),
                    label = "Volume",
                    access = access,
                    current = state.volumeSetting.toDouble(),
                    ranges = listOf(DeviceNumericRange(0.0, 255.0, 1.0)),
                    address = address,
                ),
            ),
            mutes = listOf(
                DeviceToggleControl(
                    id = DeviceControlId("bt.vcs.$serviceInstance.mute"),
                    label = "Mute",
                    access = access,
                    current = state.muted,
                    address = address,
                ),
            ),
        )
    }

    private fun parseVocs(
        serviceIndex: Int,
        service: BluetoothGattInventory.Service,
        serviceInstance: Int,
        values: Map<Pair<Int, BluetoothStandardReadPlan.Kind>, BluetoothStandardReadValue>,
    ): DeviceControlCapability.Range? {
        val stateBytes = values[serviceIndex to BluetoothStandardReadPlan.Kind.VOCS_VOLUME_OFFSET_STATE]?.value ?: return null
        val state = BluetoothLeAudioStandard.decodeVocsVolumeOffsetState(stateBytes) ?: return null
        val description = values[serviceIndex to BluetoothStandardReadPlan.Kind.VOCS_OUTPUT_DESCRIPTION]
            ?.value
            ?.let(BluetoothLeAudioStandard::decodeUtf8)
            ?.takeIf { it.isNotBlank() }
        val label = description?.let { "$it volume offset" } ?: "Volume offset${instanceSuffix(serviceInstance)}"
        val access = if (hasWriteControlPoint(service, BluetoothLeAudioStandard.VOLUME_OFFSET_CONTROL_POINT)) {
            DeviceControlAccess.READ_WRITE
        } else {
            DeviceControlAccess.READ_ONLY
        }
        val control = DeviceNumericControl(
            id = DeviceControlId("bt.vocs.$serviceInstance.offset"),
            label = label,
            unit = null, // VOCS defines this as an offset value, not a dB quantity.
            access = access,
            current = state.volumeOffset.toDouble(),
            ranges = listOf(DeviceNumericRange(-255.0, 255.0, 1.0)),
            address = standardAddress(
                BluetoothLeAudioStandard.VOCS_SERVICE,
                BluetoothLeAudioStandard.VOLUME_OFFSET_CONTROL_POINT,
                serviceInstance,
            ),
        )
        return DeviceControlCapability.Range(control.id, label, control)
    }

    private fun parseAics(
        serviceIndex: Int,
        service: BluetoothGattInventory.Service,
        serviceInstance: Int,
        values: Map<Pair<Int, BluetoothStandardReadPlan.Kind>, BluetoothStandardReadValue>,
    ): List<DeviceControlCapability> {
        val stateBytes = values[serviceIndex to BluetoothStandardReadPlan.Kind.AICS_INPUT_STATE]?.value ?: return emptyList()
        val state = BluetoothLeAudioStandard.decodeAicsInputState(stateBytes) ?: return emptyList()
        val description = values[serviceIndex to BluetoothStandardReadPlan.Kind.AICS_INPUT_DESCRIPTION]
            ?.value
            ?.let(BluetoothLeAudioStandard::decodeUtf8)
            ?.takeIf { it.isNotBlank() }
        val baseLabel = description ?: "Audio input${instanceSuffix(serviceInstance)}"
        val result = mutableListOf<DeviceControlCapability>()
        val controlPointWritable = hasWriteControlPoint(service, BluetoothLeAudioStandard.AUDIO_INPUT_CONTROL_POINT)
        val controlPointAddress = standardAddress(
            BluetoothLeAudioStandard.AICS_SERVICE,
            BluetoothLeAudioStandard.AUDIO_INPUT_CONTROL_POINT,
            serviceInstance,
        )

        val properties = values[serviceIndex to BluetoothStandardReadPlan.Kind.AICS_GAIN_SETTING_PROPERTIES]
            ?.value
            ?.let(BluetoothLeAudioStandard::decodeAicsGainSettingProperties)
        if (properties != null) {
            val step = properties.stepDb.takeIf { it > 0.0 }
            val control = DeviceNumericControl(
                id = DeviceControlId("bt.aics.$serviceInstance.gain"),
                label = "$baseLabel gain",
                unit = "dB",
                access = if (controlPointWritable && state.gainMode in setOf(
                    BluetoothLeAudioStandard.AicsGainMode.MANUAL_ONLY,
                    BluetoothLeAudioStandard.AicsGainMode.MANUAL,
                )) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                current = properties.rawToDb(state.gainSetting),
                ranges = listOf(
                    DeviceNumericRange(
                        min = properties.rawToDb(properties.minimumGainSetting),
                        max = properties.rawToDb(properties.maximumGainSetting),
                        step = step,
                    ),
                ),
                address = controlPointAddress,
            )
            result += DeviceControlCapability.Range(control.id, control.label, control)
        }

        if (state.mute != BluetoothLeAudioStandard.AicsMuteState.UNKNOWN) {
            val mute = DeviceToggleControl(
                id = DeviceControlId("bt.aics.$serviceInstance.mute"),
                label = "$baseLabel mute",
                access = if (controlPointWritable && state.mute != BluetoothLeAudioStandard.AicsMuteState.DISABLED) {
                    DeviceControlAccess.READ_WRITE
                } else {
                    DeviceControlAccess.READ_ONLY
                },
                current = state.mute != BluetoothLeAudioStandard.AicsMuteState.NOT_MUTED,
                address = controlPointAddress,
            )
            result += DeviceControlCapability.Toggle(mute.id, mute.label, mute)
        }

        if (state.gainMode != BluetoothLeAudioStandard.AicsGainMode.UNKNOWN) {
            val choice = DeviceChoiceControl(
                id = DeviceControlId("bt.aics.$serviceInstance.gain_mode"),
                label = "$baseLabel gain mode",
                access = if (controlPointWritable && state.gainMode in setOf(
                    BluetoothLeAudioStandard.AicsGainMode.MANUAL,
                    BluetoothLeAudioStandard.AicsGainMode.AUTOMATIC,
                )) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                currentValue = state.gainMode.name.lowercase(),
                options = if (state.gainMode in setOf(
                    BluetoothLeAudioStandard.AicsGainMode.MANUAL,
                    BluetoothLeAudioStandard.AicsGainMode.AUTOMATIC,
                )) {
                    listOf(
                        DeviceChoiceOption("manual", "Manual"),
                        DeviceChoiceOption("automatic", "Automatic"),
                    )
                } else {
                    listOf(DeviceChoiceOption(state.gainMode.name.lowercase(), when (state.gainMode) {
                        BluetoothLeAudioStandard.AicsGainMode.MANUAL_ONLY -> "Manual only"
                        BluetoothLeAudioStandard.AicsGainMode.AUTOMATIC_ONLY -> "Automatic only"
                        else -> state.gainMode.name.lowercase()
                    }))
                },
                address = controlPointAddress,
            )
            result += DeviceControlCapability.Choice(choice.id, choice.label, choice)
        }
        return result
    }

    private fun hasWriteControlPoint(service: BluetoothGattInventory.Service, uuid: java.util.UUID): Boolean =
        service.characteristics.any { characteristic ->
            characteristic.uuid == uuid && BluetoothGattInventory.Property.WRITE in characteristic.properties
        }

    private fun standardAddress(serviceUuid: java.util.UUID, characteristicUuid: java.util.UUID, instance: Int) =
        DeviceControlBackendAddress.BluetoothStandard(
            serviceUuid = serviceUuid.toString(),
            characteristicUuid = characteristicUuid.toString(),
            serviceInstanceIndex = instance,
        )

    private fun serviceInstanceOrdinal(inventory: BluetoothGattInventory, serviceIndex: Int): Int {
        val uuid = inventory.services[serviceIndex].uuid
        return inventory.services.take(serviceIndex).count { it.uuid == uuid }
    }

    private fun instanceSuffix(instance: Int): String = if (instance == 0) "" else " ${instance + 1}"
}
