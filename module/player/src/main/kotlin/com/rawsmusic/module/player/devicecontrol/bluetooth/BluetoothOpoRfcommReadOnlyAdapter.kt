package com.rawsmusic.module.player.devicecontrol.bluetooth

import com.rawsmusic.module.player.devicecontrol.DeviceChoiceControl
import com.rawsmusic.module.player.devicecontrol.DeviceChoiceOption
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlId
import com.rawsmusic.module.player.devicecontrol.DeviceNumericControl
import com.rawsmusic.module.player.devicecontrol.DeviceNumericRange
import com.rawsmusic.module.player.devicecontrol.DeviceToggleControl
import java.util.UUID
import com.rawsmusic.module.player.devicecontrol.GraphicEqBand

/**
 * Maps a proven OPO RFCOMM observation into the common Hardware Device Control model.
 *
 * Device-reported topology always wins. For exact HeyMelody product IDs whose firmware returns
 * status=0,count=0 for 0x8122, the signed HeyMelody profile may supply semantics/topology only
 * after 0x8103 identified the physical model. Runtime command capability and ANC support bits
 * still gate every writable control.
 */
internal object BluetoothOpoRfcommReadOnlyAdapter {
    data class DecodeResult(
        val device: DeviceControlDevice,
        val capabilities: List<DeviceControlCapability>,
        val diagnostics: List<String>,
        val state: BluetoothOpoRfcommProtocol.DecodedReadOnlyState,
        val profile: BluetoothOpoDeviceProfiles.Profile?,
    )

    fun decode(
        baseDevice: DeviceControlDevice,
        observation: BluetoothOpoRfcommProtocol.StreamObservation,
        serviceUuid: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID,
    ): DecodeResult {
        val state = BluetoothOpoRfcommProtocol.decodeReadOnlyState(observation)
        val resolvedProfile = BluetoothOpoDeviceProfiles.resolve(state.productId)
        val profile = resolvedProfile?.takeIf { it.serviceUuid == serviceUuid }
        val diagnostics = mutableListOf<String>()
        if (resolvedProfile != null && profile == null) {
            diagnostics += "bt.opo.profile transport_mismatch productId=${resolvedProfile.productIdHex} expected=${resolvedProfile.serviceUuid} actual=$serviceUuid writesDisabled=true"
        }

        val decodedDevice = baseDevice.copy(
            productId = state.productId?.toIntOrNull(16) ?: baseDevice.productId,
            model = profile?.modelName ?: state.productId ?: baseDevice.model,
            firmware = state.firmwareVersion ?: baseDevice.firmware,
        )

        diagnostics += buildString {
            append("bt.opo.decode helloAck=").append(observation.helloAckConfirmed)
            append(" matchedReadResponses=").append(observation.matchedReadOnlyResponses)
            append(" productId=").append(state.productId ?: "unknown")
            append(" model=").append(profile?.modelName ?: "unknown")
            append(" firmware=").append(state.firmwareVersion?.replace(Regex("\\s+"), " ") ?: "unknown")
        }

        state.capabilityBitmapHex?.let { diagnostics += "bt.opo.capability bitmapHex=$it" }
        if (state.supportedCommands.isNotEmpty()) {
            diagnostics += buildString {
                append("bt.opo.capability commands=").append(state.supportedCommands.size)
                append(" ancQuery=").append(BluetoothOpoRfcommProtocol.CMD_QUERY_ANC in state.supportedCommands)
                append(" ancSet=").append(BluetoothOpoRfcommProtocol.CMD_SET_ANC in state.supportedCommands)
                append(" eqCurrent=").append(BluetoothOpoRfcommProtocol.CMD_QUERY_EQ_CURRENT in state.supportedCommands)
                append(" eqAll=").append(BluetoothOpoRfcommProtocol.CMD_QUERY_EQ_ALL in state.supportedCommands)
                append(" eqPresetSet=").append(BluetoothOpoRfcommProtocol.CMD_SET_EQ_PRESET in state.supportedCommands)
                append(" eqDetailSet=").append(BluetoothOpoRfcommProtocol.CMD_SET_EQ_DETAIL in state.supportedCommands)
            }
        } else {
            diagnostics += "bt.opo.capability commands=0"
        }

        diagnostics += buildString {
            append("bt.opo.anc current=").append(state.ancMode ?: "unknown")
            append(" protocolIndex=").append(state.currentAncProtocolIndex ?: "unknown")
            append(" supportedIndices=")
            append(if (state.supportedAncProtocolIndices.isEmpty()) "unknown" else state.supportedAncProtocolIndices.sorted().joinToString(","))
        }
        state.currentEqId?.let { diagnostics += "bt.opo.eq currentEqId=$it" }
        state.eqDecodeError?.let { diagnostics += "bt.opo.eq decodeError=$it" }
        diagnostics += "bt.opo.eq entries=${state.eqEntries.size}"
        if (state.featureSwitchStatuses.isNotEmpty()) {
            diagnostics += "bt.opo.features readback=" + state.featureSwitchStatuses.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        }
        state.headsetSpatialType?.let { diagnostics += "bt.opo.spatial currentType=$it" }
        state.promptVolumeValue?.let { diagnostics += "bt.opo.prompt_volume current=$it" }

        profile?.let { resolved ->
            diagnostics += buildString {
                append("bt.opo.profile source=HeyMelody_16.9.1 productId=").append(resolved.productIdHex)
                append(" customEqBands=").append(resolved.customEqFrequenciesHz.size)
                append(" freqs=").append(resolved.customEqFrequenciesHz.joinToString(","))
                append(" range=").append(resolved.customEqMinGainDb).append("..").append(resolved.customEqMaxGainDb).append("dB")
                append(" maxCustomPresets=").append(resolved.customEqMaxPresets)
                append(" detailSetCap=").append(BluetoothOpoRfcommProtocol.CMD_SET_EQ_DETAIL in state.supportedCommands)
            }
            if (state.eqEntries.isEmpty()) {
                diagnostics += "bt.opo.eq topologySource=heymelody_profile deviceEqEntries=0 customEditorWriteDeferred=true"
            }
        }

        state.eqEntries.forEachIndexed { index, entry ->
            diagnostics += buildString {
                append("bt.opo.eq[").append(index).append("]")
                append(" id=").append(entry.eqId)
                append(" selected=").append(entry.isSelected)
                append(" name=").append(sanitizeLabel(entry.name).ifEmpty { "EQ ${entry.eqId}" })
                append(" range=").append(entry.minGainDb).append("..").append(entry.maxGainDb).append("dB")
                append(" bands=").append(entry.frequenciesHz.size)
                if (entry.frequenciesHz.isNotEmpty()) append(" freqs=").append(entry.frequenciesHz.joinToString(","))
            }
        }

        val capabilities = mutableListOf<DeviceControlCapability>()
        renderAnc(state, profile, serviceUuid, capabilities, diagnostics)
        renderEq(state, profile, serviceUuid, capabilities, diagnostics)
        renderFeatureSwitches(state, profile, serviceUuid, capabilities, diagnostics)
        renderSpatial(state, profile, serviceUuid, capabilities, diagnostics)
        renderPromptVolume(state, profile, serviceUuid, capabilities, diagnostics)

        return DecodeResult(
            device = decodedDevice,
            capabilities = capabilities,
            diagnostics = diagnostics,
            state = state,
            profile = profile,
        )
    }

    private fun renderAnc(
        state: BluetoothOpoRfcommProtocol.DecodedReadOnlyState,
        profile: BluetoothOpoDeviceProfiles.Profile?,
        serviceUuid: UUID,
        capabilities: MutableList<DeviceControlCapability>,
        diagnostics: MutableList<String>,
    ) {
        val currentIndex = state.currentAncProtocolIndex
        if (profile != null && currentIndex != null) {
            val runtimeSupported = state.supportedAncProtocolIndices
            // Mirror HeyMelody NoiseReductionItem.isNoiseReductionModeSupported():
            // runtime action=2/type=1 support bits only gate whitelist entries explicitly marked
            // decideByEarDevice=true. Ordinary whitelist ANC modes remain available even when their
            // leaf protocolIndex is not present in the support bitmap (Free4 is the concrete case:
            // support value 0x0006 advertises parent groups while current leaf may be index 8).
            val supportedOptions = profile.ancModesForRuntime(runtimeSupported)
            val currentProfileMode = profile.ancModes.firstOrNull { it.protocolIndex == currentIndex }
            val writable = BluetoothOpoRfcommProtocol.CMD_SET_ANC in state.supportedCommands &&
                currentProfileMode != null &&
                profile.isAncModeRuntimeSupported(currentIndex, runtimeSupported)
            val optionModels = if (supportedOptions.isNotEmpty()) supportedOptions else listOfNotNull(currentProfileMode)
            if (optionModels.isNotEmpty()) {
                capabilities += DeviceControlCapability.Choice(
                    id = DeviceControlId("bluetooth:opo:anc:mode"),
                    label = BluetoothOpoHeyMelodyUiStrings.NOISE_CONTROL,
                    control = DeviceChoiceControl(
                        id = DeviceControlId("bluetooth:opo:anc:mode:value"),
                        label = BluetoothOpoHeyMelodyUiStrings.NOISE_CONTROL,
                        access = if (writable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                        currentValue = currentIndex.toString(),
                        options = optionModels.map { mode ->
                            DeviceChoiceOption(
                                mode.protocolIndex.toString(),
                                BluetoothOpoHeyMelodyUiStrings.ancMode(mode.modeType, mode.label),
                            )
                        },
                        address = DeviceControlBackendAddress.BluetoothRfcomm(
                            serviceUuid = serviceUuid.toString(),
                            commandKey = "opo.anc.mode",
                        ),
                    ),
                )
                diagnostics += "bt.opo.anc renderedProfile=true writable=$writable options=${optionModels.map { it.protocolIndex }} " +
                    "runtimeGateOnly=${profile.ancModes.filter { it.decideByEarDevice }.map { it.protocolIndex }}"
                return
            }
        }

        // Conservative fallback for unknown models / missing runtime support bitmap.
        state.ancMode?.let { currentAnc ->
            capabilities += DeviceControlCapability.Choice(
                id = DeviceControlId("bluetooth:opo:anc:current"),
                label = BluetoothOpoHeyMelodyUiStrings.NOISE_CONTROL,
                control = DeviceChoiceControl(
                    id = DeviceControlId("bluetooth:opo:anc:current:value"),
                    label = BluetoothOpoHeyMelodyUiStrings.NOISE_CONTROL,
                    access = DeviceControlAccess.READ_ONLY,
                    currentValue = currentAnc,
                    options = listOf(DeviceChoiceOption(currentAnc, currentAnc)),
                    address = DeviceControlBackendAddress.BluetoothRfcomm(
                        serviceUuid = serviceUuid.toString(),
                        commandKey = "opo.anc.current",
                    ),
                ),
            )
            diagnostics += "bt.opo.anc renderedReadOnlyCurrent=true"
        }
    }

    private fun renderEq(
        state: BluetoothOpoRfcommProtocol.DecodedReadOnlyState,
        profile: BluetoothOpoDeviceProfiles.Profile?,
        serviceUuid: UUID,
        capabilities: MutableList<DeviceControlCapability>,
        diagnostics: MutableList<String>,
    ) {
        val currentEqId = state.currentEqId
        val officialPresets = profile?.eqPresetsForFirmware(state.firmwareVersion).orEmpty()
        val customEntries = state.eqEntries

        if (currentEqId != null) {
            val options = buildList<DeviceChoiceOption> {
                officialPresets.forEach { preset ->
                    add(
                        DeviceChoiceOption(
                            preset.protocolIndex.toString(),
                            BluetoothOpoHeyMelodyUiStrings.eqMode(preset.modeType, preset.label),
                        ),
                    )
                }
                customEntries.forEach { entry ->
                    add(DeviceChoiceOption(entry.eqId.toString(), sanitizeLabel(entry.name).ifEmpty { "${BluetoothOpoHeyMelodyUiStrings.CUSTOM_EQUALIZER} ${entry.eqId}" }))
                }
                if (this.none { it.value == currentEqId.toString() }) {
                    add(DeviceChoiceOption(currentEqId.toString(), "${BluetoothOpoHeyMelodyUiStrings.EQUALIZER} $currentEqId"))
                }
            }.distinctBy(DeviceChoiceOption::value)
            val presetWritable = profile != null &&
                BluetoothOpoRfcommProtocol.CMD_SET_EQ_PRESET in state.supportedCommands &&
                options.isNotEmpty()
            capabilities += DeviceControlCapability.Choice(
                id = DeviceControlId("bluetooth:opo:eq:preset"),
                label = BluetoothOpoHeyMelodyUiStrings.EQUALIZER,
                control = DeviceChoiceControl(
                    id = DeviceControlId("bluetooth:opo:eq:preset:value"),
                    label = BluetoothOpoHeyMelodyUiStrings.EQUALIZER,
                    access = if (presetWritable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                    currentValue = currentEqId.toString(),
                    options = options,
                    address = DeviceControlBackendAddress.BluetoothRfcomm(
                        serviceUuid = serviceUuid.toString(),
                        commandKey = "opo.eq.preset",
                    ),
                ),
            )
            diagnostics += "bt.opo.eq renderedPresetChoice=true writable=$presetWritable options=${options.map { it.value }}"
        }

        val selectedCustom = customEntries.firstOrNull { it.isSelected }
            ?: currentEqId?.let { id -> customEntries.firstOrNull { it.eqId == id } }
            ?: customEntries.firstOrNull()
        val detailWritable = profile != null &&
            profile.customEqMaxPresets > 0 &&
            BluetoothOpoRfcommProtocol.CMD_SET_EQ_DETAIL in state.supportedCommands

        val frequencies: List<Int>
        val gains: List<Int>
        val minGain: Int
        val maxGain: Int
        val editorLabel: String
        val editorSource: String
        if (selectedCustom != null && selectedCustom.frequenciesHz.size == selectedCustom.gainsDb.size &&
            selectedCustom.frequenciesHz.isNotEmpty()) {
            frequencies = selectedCustom.frequenciesHz
            gains = selectedCustom.gainsDb
            minGain = selectedCustom.minGainDb
            maxGain = selectedCustom.maxGainDb
            editorLabel = sanitizeLabel(selectedCustom.name).ifEmpty { BluetoothOpoHeyMelodyUiStrings.CUSTOM_EQUALIZER }
            editorSource = "device:${selectedCustom.eqId}"
        } else if (profile != null && detailWritable && profile.customEqFrequenciesHz.isNotEmpty()) {
            // HeyMelody creates a new EqInfo with no explicit eqId (Java default 0), the verified
            // profile frequency array, and zeroed gains. This is a NEW-PRESET draft, not claimed
            // hardware state; the first slider write performs action=1 and replaces it with 0x8122
            // authoritative readback.
            frequencies = profile.customEqFrequenciesHz
            gains = List(frequencies.size) { 0 }
            minGain = profile.customEqMinGainDb
            maxGain = profile.customEqMaxGainDb
            editorLabel = BluetoothOpoHeyMelodyUiStrings.CUSTOM_EQUALIZER
            editorSource = "heymelody_profile_new"
        } else {
            frequencies = emptyList()
            gains = emptyList()
            minGain = 0
            maxGain = 0
            editorLabel = BluetoothOpoHeyMelodyUiStrings.CUSTOM_EQUALIZER
            editorSource = "none"
        }

        if (frequencies.isNotEmpty() && frequencies.size == gains.size) {
            val writable = detailWritable && profile != null &&
                (selectedCustom == null || frequencies == profile.customEqFrequenciesHz)
            val bands = frequencies.indices.map { bandIndex ->
                val frequency = frequencies[bandIndex]
                val gain = gains[bandIndex]
                val bandId = DeviceControlId("bluetooth:opo:eq:custom:band:$bandIndex")
                GraphicEqBand(
                    id = bandId,
                    frequencyHz = frequency.toDouble(),
                    label = formatFrequency(frequency),
                    gain = DeviceNumericControl(
                        id = DeviceControlId("${bandId.value}:gain"),
                        label = formatFrequency(frequency),
                        unit = "dB",
                        access = if (writable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                        current = gain.toDouble(),
                        ranges = listOf(DeviceNumericRange(minGain.toDouble(), maxGain.toDouble(), 1.0)),
                        address = DeviceControlBackendAddress.BluetoothRfcomm(
                            serviceUuid = serviceUuid.toString(),
                            commandKey = "opo.eq.custom.band.$bandIndex",
                        ),
                    ),
                )
            }
            capabilities += DeviceControlCapability.GraphicEq(
                id = DeviceControlId("bluetooth:opo:eq:custom"),
                label = editorLabel,
                bands = bands,
            )
            diagnostics += "bt.opo.eq renderedCustomEditor=true writable=$writable source=$editorSource bands=${bands.size}"
        } else if (currentEqId != null && profile == null) {
            diagnostics += "bt.opo.eq customEditor=false unknownProfile=true eqId=$currentEqId"
        }
    }

    private fun renderFeatureSwitches(
        state: BluetoothOpoRfcommProtocol.DecodedReadOnlyState,
        profile: BluetoothOpoDeviceProfiles.Profile?,
        serviceUuid: UUID,
        capabilities: MutableList<DeviceControlCapability>,
        diagnostics: MutableList<String>,
    ) {
        if (profile == null) return
        val supported = BluetoothOpoHeyMelodyFeatureMap.supported(profile)
        supported.forEach { spec ->
            val status = state.featureSwitchStatuses[spec.id] ?: return@forEach
            val writable = BluetoothOpoRfcommProtocol.CMD_SET_FEATURE_SWITCH in state.supportedCommands
            val id = DeviceControlId("bluetooth:opo:feature:${spec.id}")
            capabilities += DeviceControlCapability.Toggle(
                id = id,
                label = BluetoothOpoHeyMelodyUiStrings.feature(spec.id, spec.label),
                control = DeviceToggleControl(
                    id = DeviceControlId("${id.value}:enabled"),
                    label = BluetoothOpoHeyMelodyUiStrings.feature(spec.id, spec.label),
                    access = if (writable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                    current = status != 0,
                    address = DeviceControlBackendAddress.BluetoothRfcomm(
                        serviceUuid = serviceUuid.toString(),
                        commandKey = "opo.feature.${spec.id}",
                    ),
                ),
            )
        }
        if (supported.isNotEmpty()) {
            diagnostics += "bt.opo.features rendered=${supported.count { it.id in state.featureSwitchStatuses }} profileSupported=${supported.map { it.id }}"
        }
    }

    private fun renderSpatial(
        state: BluetoothOpoRfcommProtocol.DecodedReadOnlyState,
        profile: BluetoothOpoDeviceProfiles.Profile?,
        serviceUuid: UUID,
        capabilities: MutableList<DeviceControlCapability>,
        diagnostics: MutableList<String>,
    ) {
        if (profile == null || profile.spatialTypes.isEmpty()) return
        val current = state.headsetSpatialType ?: return
        val types = profile.spatialTypes.distinct()
        val options = buildList {
            types.forEach { add(DeviceChoiceOption(it.toString(), spatialTypeLabel(it, types))) }
            if (current !in types) add(DeviceChoiceOption(current.toString(), spatialTypeLabel(current, types)))
        }
        val writable = BluetoothOpoRfcommProtocol.CMD_SET_HEADSET_SPATIAL in state.supportedCommands && current in types
        capabilities += DeviceControlCapability.Choice(
            id = DeviceControlId("bluetooth:opo:spatial:type"),
            label = BluetoothOpoHeyMelodyUiStrings.SPATIAL_AUDIO,
            control = DeviceChoiceControl(
                id = DeviceControlId("bluetooth:opo:spatial:type:value"),
                label = BluetoothOpoHeyMelodyUiStrings.SPATIAL_AUDIO,
                access = if (writable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                currentValue = current.toString(),
                options = options,
                address = DeviceControlBackendAddress.BluetoothRfcomm(
                    serviceUuid = serviceUuid.toString(),
                    commandKey = "opo.spatial.type",
                ),
            ),
        )
        diagnostics += "bt.opo.spatial rendered=true writable=$writable current=$current options=$types"
    }


    private fun renderPromptVolume(
        state: BluetoothOpoRfcommProtocol.DecodedReadOnlyState,
        profile: BluetoothOpoDeviceProfiles.Profile?,
        serviceUuid: UUID,
        capabilities: MutableList<DeviceControlCapability>,
        diagnostics: MutableList<String>,
    ) {
        if (profile == null || !profile.isFunctionEnabled("promptVolume")) return
        val current = state.promptVolumeValue ?: return
        val min = profile.promptVolumeMin ?: 1
        val max = profile.promptVolumeMax ?: 10
        if (min > max || current !in min..max) {
            diagnostics += "bt.opo.prompt_volume skipped=true invalidRangeOrReadback min=$min max=$max current=$current"
            return
        }
        val writable = BluetoothOpoRfcommProtocol.CMD_SET_PROMPT_VOLUME in state.supportedCommands
        val id = DeviceControlId("bluetooth:opo:prompt_volume")
        capabilities += DeviceControlCapability.Range(
            id = id,
            label = BluetoothOpoHeyMelodyUiStrings.PROMPT_VOLUME,
            control = DeviceNumericControl(
                id = DeviceControlId("${id.value}:value"),
                label = BluetoothOpoHeyMelodyUiStrings.PROMPT_VOLUME,
                unit = null,
                access = if (writable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                current = current.toDouble(),
                ranges = listOf(DeviceNumericRange(min.toDouble(), max.toDouble(), 1.0)),
                address = DeviceControlBackendAddress.BluetoothRfcomm(
                    serviceUuid = serviceUuid.toString(),
                    commandKey = "opo.prompt_volume",
                ),
            ),
        )
        diagnostics += "bt.opo.prompt_volume rendered=true writable=$writable current=$current range=$min..$max"
    }

    private fun spatialTypeLabel(type: Int, supportedTypes: List<Int>): String =
        BluetoothOpoHeyMelodyUiStrings.spatialType(type, supportedTypes)

    private fun sanitizeLabel(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(80)

    private fun formatFrequency(hz: Int): String = when {
        hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000} kHz"
        hz >= 1000 -> "${"%.1f".format(hz / 1000.0)} kHz"
        else -> "$hz Hz"
    }
}
