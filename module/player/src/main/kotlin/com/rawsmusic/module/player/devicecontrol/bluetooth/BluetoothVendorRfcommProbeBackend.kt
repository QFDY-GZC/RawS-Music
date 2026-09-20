package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackend
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlSession
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteValue
import com.rawsmusic.module.player.devicecontrol.FixedDeviceControlSession
import com.rawsmusic.module.player.devicecontrol.SerializedDeviceControlWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Classic-vendor RFCOMM lane.
 *
 * Unknown services remain probe-only. A 079A or legacy 1107 path upgrades to a concrete OPO adapter
 * only after device-reported product ID matches the signed HeyMelody profile and that profile names
 * the same RFCOMM service UUID. All exposed writes are serialized and require runtime capability,
 * SET ACK and authoritative GET readback. Custom EQ follows HeyMelody's device-side lifecycle:
 * action=1 creates an eqId=0 draft, 0x8122 returns the assigned ID, and action=2 updates/applies the
 * complete dynamic-band EqInfo without imposing a fixed band count.
 */
internal class BluetoothVendorRfcommProbeBackend(
    context: Context,
) : DeviceControlBackend {
    private val appContext = context.applicationContext

    override suspend fun open(device: DeviceControlDevice, generation: Long): DeviceControlSession? {
        if (device.connectionKind != DeviceConnectionKind.BLUETOOTH) return null
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return FixedDeviceControlSession(
                device = device,
                generation = generation,
                initialState = DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                initialMessage = "bluetooth_connect_permission_required",
            )
        }

        return when (val resolved = BluetoothBoundDeviceLookup.resolve(appContext, device)) {
            is BluetoothBoundDeviceLookup.Result.Found -> Session(
                context = appContext,
                device = device,
                generation = generation,
                bluetoothDevice = resolved.device,
                identitySource = resolved.source,
                fastPath = BluetoothOpoRfcommFastPath.matches(device, resolved.device),
            )
            is BluetoothBoundDeviceLookup.Result.Failed -> FixedDeviceControlSession(
                device = device,
                generation = generation,
                initialState = DeviceControlSnapshot.ProbeState.UNSUPPORTED,
                initialMessage = "bluetooth_rfcomm_device_lookup_failed:${resolved.reason}",
            )
        }
    }

    private class Session(
        context: Context,
        override val device: DeviceControlDevice,
        override val generation: Long,
        private val bluetoothDevice: BluetoothDevice,
        private val identitySource: String,
        private val fastPath: Boolean,
    ) : DeviceControlSession {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val client = BluetoothRfcommServiceProbeClient(context)
        private val opoHelloClient = BluetoothOpoRfcommHelloProbeClient(context)
        private val opoControlClient = BluetoothOpoRfcommControlClient(context)
        private val _snapshot = MutableStateFlow(
            DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.IDLE,
            ),
        )
        override val snapshot: StateFlow<DeviceControlSnapshot> = _snapshot.asStateFlow()

        private var probeJob: Job? = null
        @Volatile private var latestDecoded: BluetoothOpoRfcommReadOnlyAdapter.DecodeResult? = null
        private data class CustomEqRuntime(
            val eqId: Int?,
            val name: String,
            val minGainDb: Int,
            val maxGainDb: Int,
            val frequenciesHz: List<Int>,
            val gainsDb: List<Int>,
        )
        private var customEqRuntime: CustomEqRuntime? = null
        private var closed = false
        private val writer = SerializedDeviceControlWriter(
            scope = scope,
            generation = generation,
            currentGeneration = { generation },
            canExecute = { !closed && _snapshot.value.probeState == DeviceControlSnapshot.ProbeState.READY },
            execute = ::executeWrite,
        )

        override suspend fun probe() {
            if (closed) return
            probeJob?.cancel()
            val baseDiagnostics = bluetoothDevice.toControlIdentityDiagnosticLines(identitySource)
            val cachedVendorUuids = bluetoothDevice.cachedVendorServiceUuids()
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.PROBING,
                message = "bluetooth_rfcomm_vendor_service_probing",
                diagnostics = baseDiagnostics,
            )
            probeJob = scope.launch {
                if (fastPath) {
                    val progress = mutableListOf("bt.opo.fast_path model=OPPO_Enco_Free4 bearer=079A_RFCOMM")
                    fun publishProgress(line: String) {
                        progress += line
                        if (progress.size > 72) progress.removeAt(0)
                        val current = _snapshot.value
                        val ready = current.capabilities.isNotEmpty()

                        // Do not make the Compose settings tree recompose for every chunk/frame
                        // diagnostic. Before READY only surface stage milestones; after READY keep
                        // enrichment diagnostics buffered until the next coherent observation.
                        if (ready) return
                        val milestone = line.contains("connected=true") ||
                            line.contains("hello_ack confirmed=") ||
                            line.contains("readonly_query_result") ||
                            line.contains("fast_ready checkpoint=")
                        if (!milestone) return

                        _snapshot.value = current.copy(
                            probeState = DeviceControlSnapshot.ProbeState.PROBING,
                            message = "bluetooth_opo_rfcomm_fast_probing",
                            diagnostics = (baseDiagnostics + current.diagnostics + progress).distinct(),
                        )
                    }

                    when (val hello = opoHelloClient.probe(
                        bluetoothDevice,
                        serviceUuid = BluetoothOpoRfcommProtocol.SERVICE_UUID,
                        fastPath = true,
                        onProgress = ::publishProgress,
                        onObservation = { observation ->
                            val decoded = BluetoothOpoRfcommReadOnlyAdapter.decode(device, observation, BluetoothOpoRfcommProtocol.SERVICE_UUID)
                            latestDecoded = decoded
                            val ready = decoded.capabilities.isNotEmpty()
                            _snapshot.value = DeviceControlSnapshot(
                                generation = generation,
                                device = decoded.device,
                                capabilities = decoded.capabilities,
                                probeState = if (ready) DeviceControlSnapshot.ProbeState.READY
                                else DeviceControlSnapshot.ProbeState.PROBING,
                                message = if (ready) "bluetooth_opo_rfcomm_readonly_ready;enriching"
                                else "bluetooth_opo_rfcomm_fast_probing",
                                diagnostics = (baseDiagnostics + progress + decoded.diagnostics).distinct(),
                            )
                        },
                    )) {
                        BluetoothOpoRfcommHelloProbeClient.Result.PermissionRequired -> {
                            _snapshot.value = DeviceControlSnapshot(
                                generation = generation,
                                device = device,
                                probeState = DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                                message = "bluetooth_connect_permission_required",
                                diagnostics = (baseDiagnostics + progress).distinct(),
                            )
                        }
                        is BluetoothOpoRfcommHelloProbeClient.Result.Ready -> {
                            val observation = hello.observation
                            val decoded = observation?.let { BluetoothOpoRfcommReadOnlyAdapter.decode(device, it, BluetoothOpoRfcommProtocol.SERVICE_UUID) }
                                ?: latestDecoded
                            val capabilities = decoded?.capabilities.orEmpty()
                            _snapshot.value = DeviceControlSnapshot(
                                generation = generation,
                                device = decoded?.device ?: device,
                                capabilities = capabilities,
                                probeState = if (capabilities.isNotEmpty()) DeviceControlSnapshot.ProbeState.READY
                                else DeviceControlSnapshot.ProbeState.UNSUPPORTED,
                                message = when {
                                    capabilities.isNotEmpty() -> "bluetooth_opo_rfcomm_ready"
                                    (observation?.matchedReadOnlyResponses ?: 0) > 0 ->
                                        "bluetooth_opo_rfcomm_readonly_responses_observed;no_renderable_controls"
                                    observation?.helloAckConfirmed == true ->
                                        "bluetooth_opo_rfcomm_hello_confirmed;readonly_queries_no_response_without_register"
                                    else -> "bluetooth_opo_rfcomm_fast_path_failed"
                                },
                                diagnostics = (baseDiagnostics + progress + hello.diagnostics + decoded?.diagnostics.orEmpty()).distinct(),
                            )
                        }
                    }
                    return@launch
                }

                val progress = mutableListOf<String>()
                when (val result = client.probe(
                    bluetoothDevice,
                    cachedVendorUuids,
                    onProgress = { line ->
                        progress += line
                        _snapshot.value = DeviceControlSnapshot(
                            generation = generation,
                            device = device,
                            probeState = DeviceControlSnapshot.ProbeState.PROBING,
                            message = "bluetooth_rfcomm_vendor_service_probing",
                            diagnostics = (baseDiagnostics + progress).distinct(),
                        )
                    },
                )) {
                    BluetoothRfcommServiceProbeClient.Result.PermissionRequired -> {
                        _snapshot.value = DeviceControlSnapshot(
                            generation = generation,
                            device = device,
                            probeState = DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                            message = "bluetooth_connect_permission_required",
                            diagnostics = (baseDiagnostics + progress).distinct(),
                        )
                    }
                    is BluetoothRfcommServiceProbeClient.Result.Ready -> {
                        val connectable = result.services.filter { it.connectable }
                        val opoServiceUuids = connectable
                            .map { it.uuid }
                            .filter { it in BluetoothOpoRfcommProtocol.KNOWN_SERVICE_UUIDS }
                            .distinct()
                        val opoConnectable = opoServiceUuids.isNotEmpty()
                        var opoObservation: BluetoothOpoRfcommProtocol.StreamObservation? = null
                        var decoded: BluetoothOpoRfcommReadOnlyAdapter.DecodeResult? = null
                        var fallbackDecoded: BluetoothOpoRfcommReadOnlyAdapter.DecodeResult? = null
                        val extraDiagnostics = mutableListOf<String>()
                        if (connectable.any { it.uuid.toString() == "99999999-9999-9999-9999-999999999999" }) {
                            extraDiagnostics += "bt.rfcomm role_hint uuid=99999999-9999-9999-9999-999999999999 role=unknown_auxiliary"
                        }
                        for (opoServiceUuid in opoServiceUuids) {
                            extraDiagnostics += "bt.rfcomm role_hint uuid=$opoServiceUuid role=opo_heymelody_control_candidate"
                            when (val hello = opoHelloClient.probe(
                                bluetoothDevice,
                                serviceUuid = opoServiceUuid,
                                onProgress = { line ->
                                    extraDiagnostics += line
                                    _snapshot.value = DeviceControlSnapshot(
                                        generation = generation,
                                        device = device,
                                        probeState = DeviceControlSnapshot.ProbeState.PROBING,
                                        message = "bluetooth_opo_rfcomm_readonly_probing",
                                        diagnostics = (baseDiagnostics + result.diagnostics + extraDiagnostics).distinct(),
                                    )
                                },
                            )) {
                                BluetoothOpoRfcommHelloProbeClient.Result.PermissionRequired -> {
                                    _snapshot.value = DeviceControlSnapshot(
                                        generation = generation,
                                        device = device,
                                        probeState = DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                                        message = "bluetooth_connect_permission_required",
                                        diagnostics = (baseDiagnostics + result.diagnostics + extraDiagnostics).distinct(),
                                    )
                                    return@launch
                                }
                                is BluetoothOpoRfcommHelloProbeClient.Result.Ready -> {
                                    val observation = hello.observation
                                    extraDiagnostics += hello.diagnostics
                                    if (observation != null) {
                                        val candidateDecoded = BluetoothOpoRfcommReadOnlyAdapter.decode(device, observation, opoServiceUuid)
                                        if (fallbackDecoded == null) {
                                            fallbackDecoded = candidateDecoded
                                            opoObservation = observation
                                        }
                                        if (candidateDecoded.profile != null) {
                                            decoded = candidateDecoded
                                            opoObservation = observation
                                            extraDiagnostics += "bt.opo.profile owner_selected=true productId=${candidateDecoded.profile.productIdHex} service=$opoServiceUuid"
                                            break
                                        }
                                        extraDiagnostics += "bt.opo.profile owner_selected=false service=$opoServiceUuid reason=product_or_transport_mismatch"
                                    }
                                }
                            }
                        }
                        if (decoded == null) decoded = fallbackDecoded
                        if (decoded != null) latestDecoded = decoded
                        _snapshot.value = DeviceControlSnapshot(
                            generation = generation,
                            device = decoded?.device ?: device,
                            capabilities = decoded?.capabilities.orEmpty(),
                            probeState = if (!decoded?.capabilities.isNullOrEmpty()) {
                                DeviceControlSnapshot.ProbeState.READY
                            } else {
                                DeviceControlSnapshot.ProbeState.UNSUPPORTED
                            },
                            message = when {
                                !decoded?.capabilities.isNullOrEmpty() ->
                                    "bluetooth_opo_rfcomm_ready"
                                result.services.isEmpty() -> "bluetooth_rfcomm_no_vendor_candidates"
                                connectable.isEmpty() -> "bluetooth_rfcomm_vendor_service_not_connectable"
                                (opoObservation?.matchedReadOnlyResponses ?: 0) > 0 ->
                                    "bluetooth_opo_rfcomm_readonly_responses_observed;no_renderable_controls"
                                opoObservation?.helloAckConfirmed == true ->
                                    "bluetooth_opo_rfcomm_hello_confirmed;readonly_queries_no_response_without_register"
                                opoObservation?.hasOpoCandidate == true ->
                                    "bluetooth_opo_rfcomm_stream_observed;hello_ack_unconfirmed"
                                opoConnectable -> "bluetooth_opo_rfcomm_service_confirmed;hello_response_unconfirmed"
                                else -> "bluetooth_rfcomm_vendor_adapter_not_found;connectable_services=${connectable.size}"
                            },
                            diagnostics = (
                                baseDiagnostics + result.diagnostics + extraDiagnostics + decoded?.diagnostics.orEmpty()
                            ).distinct(),
                        )
                    }
                }
            }
        }

        override suspend fun refresh() = probe()

        override suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult =
            writer.submit(request)

        private suspend fun executeWrite(request: DeviceControlWriteRequest): DeviceControlWriteResult {
            val decoded = latestDecoded ?: return DeviceControlWriteResult.Rejected("opo_profile_not_ready")
            val profile = decoded.profile ?: return DeviceControlWriteResult.Rejected("opo_unknown_product_profile")
            val state = decoded.state

            fun runtimeFromDeviceOrDraft(): CustomEqRuntime {
                customEqRuntime?.let { return it }
                val existing = state.eqEntries.firstOrNull { it.isSelected }
                    ?: state.currentEqId?.let { id -> state.eqEntries.firstOrNull { it.eqId == id } }
                    ?: state.eqEntries.firstOrNull()
                val runtime = if (existing != null && existing.frequenciesHz == profile.customEqFrequenciesHz &&
                    existing.frequenciesHz.size == existing.gainsDb.size) {
                    CustomEqRuntime(
                        eqId = existing.eqId,
                        name = existing.name.ifEmpty { "RawSMusic" },
                        minGainDb = existing.minGainDb,
                        maxGainDb = existing.maxGainDb,
                        frequenciesHz = existing.frequenciesHz,
                        gainsDb = existing.gainsDb,
                    )
                } else {
                    CustomEqRuntime(
                        eqId = null,
                        name = "RawSMusic",
                        minGainDb = profile.customEqMinGainDb,
                        maxGainDb = profile.customEqMaxGainDb,
                        frequenciesHz = profile.customEqFrequenciesHz,
                        gainsDb = List(profile.customEqFrequenciesHz.size) { 0 },
                    )
                }
                customEqRuntime = runtime
                return runtime
            }

            val customBandMatch = Regex("bluetooth:opo:eq:custom:band:(\\d+):gain").matchEntire(request.controlId.value)
            if (customBandMatch != null) {
                if (BluetoothOpoRfcommProtocol.CMD_SET_EQ_DETAIL !in state.supportedCommands) {
                    return DeviceControlWriteResult.Rejected("opo_custom_eq_set_not_in_capability_bitmap")
                }
                val numeric = request.value as? DeviceControlWriteValue.Number
                    ?: return DeviceControlWriteResult.Rejected("opo_custom_eq_number_required")
                val bandIndex = customBandMatch.groupValues[1].toIntOrNull()
                    ?: return DeviceControlWriteResult.Rejected("opo_custom_eq_band_invalid")
                val runtime = runtimeFromDeviceOrDraft()
                if (bandIndex !in runtime.frequenciesHz.indices) {
                    return DeviceControlWriteResult.Rejected("opo_custom_eq_band_out_of_range")
                }
                val gain = numeric.value.toInt()
                if (numeric.value != gain.toDouble() || gain !in runtime.minGainDb..runtime.maxGainDb) {
                    return DeviceControlWriteResult.Rejected("opo_custom_eq_gain_out_of_range")
                }
                val nextGains = runtime.gainsDb.toMutableList().also { it[bandIndex] = gain }.toList()
                val action = if (runtime.eqId == null) 1 else 2
                val result = opoControlClient.setCustomEq(
                    device = bluetoothDevice,
                    action = action,
                    minGainDb = runtime.minGainDb,
                    maxGainDb = runtime.maxGainDb,
                    eqId = runtime.eqId ?: 0,
                    name = runtime.name,
                    frequenciesHz = runtime.frequenciesHz,
                    gainsDb = nextGains,
                    serviceUuid = profile.serviceUuid,
                )
                return when (result) {
                    BluetoothOpoRfcommControlClient.Result.PermissionRequired -> {
                        _snapshot.value = _snapshot.value.copy(
                            probeState = DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                            message = "bluetooth_connect_permission_required",
                        )
                        DeviceControlWriteResult.Rejected("bluetooth_connect_permission_required")
                    }
                    is BluetoothOpoRfcommControlClient.Result.Rejected -> DeviceControlWriteResult.Rejected(result.reason)
                    is BluetoothOpoRfcommControlClient.Result.Applied -> {
                        val entry = result.customEqEntry
                            ?: return DeviceControlWriteResult.Rejected("opo_custom_eq_readback_missing")
                        customEqRuntime = CustomEqRuntime(
                            eqId = entry.eqId,
                            name = entry.name.ifEmpty { runtime.name },
                            minGainDb = entry.minGainDb,
                            maxGainDb = entry.maxGainDb,
                            frequenciesHz = entry.frequenciesHz,
                            gainsDb = entry.gainsDb,
                        )
                        val current = _snapshot.value
                        val customOption = com.rawsmusic.module.player.devicecontrol.DeviceChoiceOption(
                            entry.eqId.toString(),
                            entry.name.ifBlank { "Custom EQ" },
                        )
                        val updatedCapabilities = current.capabilities.map { capability ->
                            when {
                                capability is com.rawsmusic.module.player.devicecontrol.DeviceControlCapability.GraphicEq &&
                                    capability.id.value == "bluetooth:opo:eq:custom" -> {
                                    capability.copy(
                                        label = entry.name.ifBlank { "Custom EQ" },
                                        bands = capability.bands.mapIndexed { index, band ->
                                            val value = entry.gainsDb.getOrNull(index)?.toDouble() ?: band.gain.current
                                            band.copy(gain = band.gain.copy(current = value))
                                        },
                                    )
                                }
                                capability is com.rawsmusic.module.player.devicecontrol.DeviceControlCapability.Choice &&
                                    capability.control.id.value == "bluetooth:opo:eq:preset:value" -> {
                                    capability.copy(control = capability.control.copy(
                                        currentValue = entry.eqId.toString(),
                                        options = (capability.control.options + customOption)
                                            .distinctBy(com.rawsmusic.module.player.devicecontrol.DeviceChoiceOption::value),
                                    ))
                                }
                                else -> capability
                            }
                        }
                        val updated = current.copy(
                            capabilities = updatedCapabilities,
                            probeState = DeviceControlSnapshot.ProbeState.READY,
                            message = "bluetooth_opo_rfcomm_ready",
                            diagnostics = (current.diagnostics + result.diagnostics +
                                "bt.opo.custom_eq applied action=$action eqId=${entry.eqId} band=$bandIndex gain=$gain readback=true").distinct(),
                        )
                        _snapshot.value = updated
                        DeviceControlWriteResult.Applied(updated)
                    }
                }
            }

            if (request.controlId.value == "bluetooth:opo:prompt_volume:value") {
                val numeric = request.value as? DeviceControlWriteValue.Number
                    ?: return DeviceControlWriteResult.Rejected("opo_prompt_volume_number_required")
                if (!profile.isFunctionEnabled("promptVolume")) {
                    return DeviceControlWriteResult.Rejected("opo_prompt_volume_not_in_verified_profile")
                }
                val min = profile.promptVolumeMin ?: 1
                val max = profile.promptVolumeMax ?: 10
                val value = numeric.value.toInt()
                if (numeric.value != value.toDouble() || value !in min..max) {
                    return DeviceControlWriteResult.Rejected("opo_prompt_volume_out_of_range")
                }
                if (state.promptVolumeValue == null) {
                    return DeviceControlWriteResult.Rejected("opo_prompt_volume_readback_missing")
                }
                if (BluetoothOpoRfcommProtocol.CMD_SET_PROMPT_VOLUME !in state.supportedCommands) {
                    return DeviceControlWriteResult.Rejected("opo_prompt_volume_set_not_in_capability_bitmap")
                }
                return when (val volumeResult = opoControlClient.setPromptVolume(
                    bluetoothDevice, value, profile.serviceUuid,
                )) {
                    BluetoothOpoRfcommControlClient.Result.PermissionRequired -> DeviceControlWriteResult.Rejected("bluetooth_connect_permission_required")
                    is BluetoothOpoRfcommControlClient.Result.Rejected -> DeviceControlWriteResult.Rejected(volumeResult.reason)
                    is BluetoothOpoRfcommControlClient.Result.Applied -> {
                        val actual = volumeResult.promptVolumeValue
                            ?: return DeviceControlWriteResult.Rejected("opo_prompt_volume_readback_missing")
                        val current = _snapshot.value
                        val updatedCaps = current.capabilities.map { capability ->
                            if (capability is com.rawsmusic.module.player.devicecontrol.DeviceControlCapability.Range &&
                                capability.control.id == request.controlId) {
                                capability.copy(control = capability.control.copy(current = actual.toDouble()))
                            } else capability
                        }
                        val updated = current.copy(
                            capabilities = updatedCaps,
                            diagnostics = (current.diagnostics + volumeResult.diagnostics +
                                "bt.opo.prompt_volume applied value=$actual readback=true").distinct(),
                        )
                        _snapshot.value = updated
                        DeviceControlWriteResult.Applied(updated)
                    }
                }
            }

            val featureMatch = Regex("bluetooth:opo:feature:(\\d+):enabled").matchEntire(request.controlId.value)
            if (featureMatch != null) {
                val toggle = request.value as? DeviceControlWriteValue.Toggle
                    ?: return DeviceControlWriteResult.Rejected("opo_toggle_value_required")
                val featureId = featureMatch.groupValues[1].toIntOrNull()
                    ?: return DeviceControlWriteResult.Rejected("opo_feature_id_invalid")
                val verifiedIds = BluetoothOpoHeyMelodyFeatureMap.supported(profile).map { it.id }.toSet()
                if (featureId !in verifiedIds) return DeviceControlWriteResult.Rejected("opo_feature_not_in_verified_profile")
                if (featureId !in state.featureSwitchStatuses) return DeviceControlWriteResult.Rejected("opo_feature_readback_missing")
                if (BluetoothOpoRfcommProtocol.CMD_SET_FEATURE_SWITCH !in state.supportedCommands) {
                    return DeviceControlWriteResult.Rejected("opo_feature_set_not_in_capability_bitmap")
                }
                return when (val featureResult = opoControlClient.setFeatureSwitch(
                    bluetoothDevice, featureId, toggle.enabled, profile.serviceUuid,
                )) {
                    BluetoothOpoRfcommControlClient.Result.PermissionRequired -> DeviceControlWriteResult.Rejected("bluetooth_connect_permission_required")
                    is BluetoothOpoRfcommControlClient.Result.Rejected -> DeviceControlWriteResult.Rejected(featureResult.reason)
                    is BluetoothOpoRfcommControlClient.Result.Applied -> {
                        val actual = featureResult.featureSwitchStatus
                            ?: return DeviceControlWriteResult.Rejected("opo_feature_readback_missing")
                        val current = _snapshot.value
                        val updatedCaps = current.capabilities.map { capability ->
                            if (capability is com.rawsmusic.module.player.devicecontrol.DeviceControlCapability.Toggle &&
                                capability.control.id == request.controlId) {
                                capability.copy(control = capability.control.copy(current = actual != 0))
                            } else capability
                        }
                        val updated = current.copy(
                            capabilities = updatedCaps,
                            diagnostics = (current.diagnostics + featureResult.diagnostics +
                                "bt.opo.feature applied id=$featureId enabled=${actual != 0} readback=true").distinct(),
                        )
                        _snapshot.value = updated
                        DeviceControlWriteResult.Applied(updated)
                    }
                }
            }

            val choice = request.value as? DeviceControlWriteValue.Choice
                ?: return DeviceControlWriteResult.Rejected("opo_choice_value_required")
            val requested = choice.value.toIntOrNull()
                ?: return DeviceControlWriteResult.Rejected("opo_choice_value_invalid")

            val result = when (request.controlId.value) {
                "bluetooth:opo:anc:mode:value" -> {
                    if (BluetoothOpoRfcommProtocol.CMD_SET_ANC !in state.supportedCommands) {
                        return DeviceControlWriteResult.Rejected("opo_anc_set_not_in_capability_bitmap")
                    }
                    if (profile.ancModes.none { it.protocolIndex == requested }) {
                        return DeviceControlWriteResult.Rejected("opo_anc_mode_not_in_verified_profile")
                    }
                    // HeyMelody only applies the action=2/type=1 support bitmap to modes whose
                    // signed whitelist entry has decideByEarDevice=true. Do not require every leaf
                    // protocolIndex to appear in that bitmap: Free4 reports support=0x0006 while
                    // valid/current leaf modes include 3/4/5/6/7/8/11.
                    if (!profile.isAncModeRuntimeSupported(requested, state.supportedAncProtocolIndices)) {
                        return DeviceControlWriteResult.Rejected("opo_anc_mode_not_runtime_supported")
                    }
                    opoControlClient.setAncMode(bluetoothDevice, requested, profile.serviceUuid)
                }
                "bluetooth:opo:eq:preset:value" -> {
                    val official = profile.eqPresetsForFirmware(state.firmwareVersion).map { it.protocolIndex }.toSet()
                    if (requested in official) {
                        if (BluetoothOpoRfcommProtocol.CMD_SET_EQ_PRESET !in state.supportedCommands) {
                            return DeviceControlWriteResult.Rejected("opo_eq_preset_set_not_in_capability_bitmap")
                        }
                        opoControlClient.setEqPreset(bluetoothDevice, requested, profile.serviceUuid)
                    } else {
                        val runtime = runtimeFromDeviceOrDraft()
                        if (runtime.eqId != requested) {
                            return DeviceControlWriteResult.Rejected("opo_eq_preset_not_in_verified_profile_or_custom_entries")
                        }
                        if (BluetoothOpoRfcommProtocol.CMD_SET_EQ_DETAIL !in state.supportedCommands) {
                            return DeviceControlWriteResult.Rejected("opo_custom_eq_set_not_in_capability_bitmap")
                        }
                        opoControlClient.setCustomEq(
                            device = bluetoothDevice,
                            action = 2,
                            minGainDb = runtime.minGainDb,
                            maxGainDb = runtime.maxGainDb,
                            eqId = runtime.eqId ?: 0,
                            name = runtime.name,
                            frequenciesHz = runtime.frequenciesHz,
                            gainsDb = runtime.gainsDb,
                            serviceUuid = profile.serviceUuid,
                        )
                    }
                }
                "bluetooth:opo:spatial:type:value" -> {
                    if (requested !in profile.spatialTypes) {
                        return DeviceControlWriteResult.Rejected("opo_spatial_type_not_in_verified_profile")
                    }
                    if (BluetoothOpoRfcommProtocol.CMD_SET_HEADSET_SPATIAL !in state.supportedCommands) {
                        return DeviceControlWriteResult.Rejected("opo_spatial_set_not_in_capability_bitmap")
                    }
                    opoControlClient.setHeadsetSpatialType(bluetoothDevice, requested, profile.serviceUuid)
                }
                else -> return DeviceControlWriteResult.Rejected("opo_control_not_writable")
            }

            return when (result) {
                BluetoothOpoRfcommControlClient.Result.PermissionRequired -> {
                    _snapshot.value = _snapshot.value.copy(
                        probeState = DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                        message = "bluetooth_connect_permission_required",
                    )
                    DeviceControlWriteResult.Rejected("bluetooth_connect_permission_required")
                }
                is BluetoothOpoRfcommControlClient.Result.Rejected ->
                    DeviceControlWriteResult.Rejected(result.reason)
                is BluetoothOpoRfcommControlClient.Result.Applied -> {
                    result.customEqEntry?.let { entry ->
                        customEqRuntime = CustomEqRuntime(
                            eqId = entry.eqId,
                            name = entry.name.ifBlank { "RawSMusic" },
                            minGainDb = entry.minGainDb,
                            maxGainDb = entry.maxGainDb,
                            frequenciesHz = entry.frequenciesHz,
                            gainsDb = entry.gainsDb,
                        )
                    }
                    val newValue = (result.currentAncProtocolIndex ?: result.currentEqId ?: result.headsetSpatialType ?: requested).toString()
                    val current = _snapshot.value
                    val updatedCapabilities = current.capabilities.map { capability ->
                        if (capability is com.rawsmusic.module.player.devicecontrol.DeviceControlCapability.Choice &&
                            capability.control.id == request.controlId
                        ) {
                            capability.copy(control = capability.control.copy(currentValue = newValue))
                        } else capability
                    }
                    val updated = current.copy(
                        capabilities = updatedCapabilities,
                        probeState = DeviceControlSnapshot.ProbeState.READY,
                        message = "bluetooth_opo_rfcomm_ready",
                        diagnostics = (current.diagnostics + result.diagnostics +
                            "bt.opo.write applied control=${request.controlId.value} value=$newValue readback=true").distinct(),
                    )
                    _snapshot.value = updated
                    DeviceControlWriteResult.Applied(updated)
                }
            }
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            probeJob?.cancel()
            writer.close()
            scope.cancel()
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.IDLE,
                message = "closed",
            )
        }
    }
}
