package com.rawsmusic.module.player.devicecontrol.usb.moondrop

import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlId
import com.rawsmusic.module.player.devicecontrol.DeviceNumericControl
import com.rawsmusic.module.player.devicecontrol.DeviceNumericRange
import com.rawsmusic.module.player.devicecontrol.DeviceToggleControl
import com.rawsmusic.module.player.devicecontrol.ParametricEqBand
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteValue
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorAdapterContext
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorAdapterProbeResult
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorAdapterRegistry
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorControlInventory
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorDeviceAdapter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * MOONDROP vendor-protocol based USB adapter, Phase 6.
 *
 * This adapter intentionally does not infer controls from the brand alone. A control is published
 * only after the attached device returns a structurally valid response for that exact feature.
 * Family detection remains read-only. For descriptor-confirmed SPV devices, Phase 6 enables only
 * write paths whose full official wire layout can be re-validated against live device readback:
 * PEQ band frequency/Q/gain (full coefficient form) and conservative attenuation-only pre-gain.
 * ADC/DAC gain and preset selection remain read-only until their user-safe ranges/options are
 * derived. CxAudio/Jiu/Comture/Bluetrum/Jieli stay passive until their exact handshakes exist.
 */
class MoondropUsbVendorAdapter : UsbVendorDeviceAdapter {
    override val adapterId: String = ADAPTER_ID

    override fun match(device: DeviceControlDevice, inventory: UsbVendorControlInventory): Int {
        // Current MOONDROP/SPV dongles may expose only the generic USB product string. The
        // vendor-defined 0xFF01 HID collection with bidirectional 0x4B/63-byte reports is a much
        // stronger protocol identity than a display name or VID/PID and is safe to probe read-only.
        if (MoondropSpvHidIdentity.fingerprint(inventory) != null) return 720

        if (!MoondropUsbIdentity.isOfficialNameCandidate(device.displayName)) return 0
        val candidates = MoondropEndpointSelector.candidates(inventory)
        if (candidates.isEmpty()) return 0
        return when {
            candidates.any { it.appCommandEp4 } -> 420
            candidates.any { it.interfaceClass == 0x03 } -> 360
            else -> 300
        }
    }

    override suspend fun probe(context: UsbVendorAdapterContext): UsbVendorAdapterProbeResult {
        val diagnostics = mutableListOf<String>()
        diagnostics += "usb.moondrop candidate=true name=${context.device.displayName}"
        val spvFingerprint = MoondropSpvHidIdentity.fingerprint(context.inventory)
        spvFingerprint?.let { fp ->
            diagnostics += "usb.moondrop descriptor_family=spv if=${fp.interfaceNumber} report4bBytes=${fp.commandReportBytes} report54Bytes=${fp.otaReportBytes ?: 0}"
        }
        val candidates = MoondropEndpointSelector.candidates(context.inventory)
        diagnostics += "usb.moondrop endpoint_candidates=${candidates.size}"
        candidates.forEachIndexed { index, candidate ->
            diagnostics += buildString {
                append("usb.moondrop endpoint_candidate index=$index if=")
                append(candidate.target.interfaceNumber)
                append(" out=0x")
                append(candidate.target.outEndpointAddress.toString(16))
                append(" in=0x")
                append(candidate.target.inEndpointAddress.toString(16))
                append(" class=0x")
                append(candidate.interfaceClass.toString(16))
                append(" transfer=")
                append(candidate.transferTypes.sorted().joinToString(","))
                append(" ep4=")
                append(candidate.appCommandEp4)
            }
        }
        val endpoint = if (spvFingerprint != null) {
            candidates.singleOrNull { it.target.interfaceNumber == spvFingerprint.interfaceNumber }
        } else {
            MoondropEndpointSelector.selectUnambiguous(context.inventory)
        } ?: return UsbVendorAdapterProbeResult.Unsupported(
                reason = if (candidates.isEmpty()) {
                    "moondrop_control_endpoint_not_found"
                } else {
                    "moondrop_control_endpoint_ambiguous"
                },
                diagnostics = diagnostics,
            )

        val firmwareResult = MoondropSpvProtocol.read(
            context.transport,
            endpoint.target,
            MoondropSpvProtocol.COMMAND_FIRMWARE,
        )
        val firmware = firmwareResult.getOrNull()
        if (firmware == null) {
            val error = firmwareResult.exceptionOrNull()?.message?.replace(' ', '_') ?: "unknown"
            diagnostics += "usb.moondrop family=unresolved spv_firmware_get=false error=$error"
            val likelyCx = context.inventory.hidInterfaces.any { iface ->
                iface.hidReports.any { it.reportId == 1 && maxOf(it.inputBytes, it.outputBytes, it.featureBytes) in 60L..64L }
            }
            if (likelyCx) diagnostics += "usb.moondrop family_candidate=synaptics_cxaudio hid_report_id=1 size=60..64"
            return UsbVendorAdapterProbeResult.Unsupported(
                reason = "moondrop_family_not_confirmed",
                diagnostics = diagnostics,
            )
        }

        diagnostics += "usb.moondrop family=spv"
        diagnostics += "usb.moondrop spv.firmware=${MoondropSpvProtocol.printableAsciiOrHex(firmware.payload)}"
        val capabilities = probeSpvCapabilities(context, endpoint, diagnostics)
        return UsbVendorAdapterProbeResult.Ready(
            capabilities = capabilities,
            message = "moondrop_spv_runtime_capabilities=${capabilities.size}",
            diagnostics = diagnostics,
        )
    }

    override suspend fun write(
        context: UsbVendorAdapterContext,
        request: DeviceControlWriteRequest,
        current: DeviceControlSnapshot,
    ): DeviceControlWriteResult {
        if (current.probeState != DeviceControlSnapshot.ProbeState.READY) {
            return DeviceControlWriteResult.Rejected("moondrop_controls_not_ready")
        }
        val endpoint = resolveSpvEndpoint(context)
            ?: return DeviceControlWriteResult.Rejected("moondrop_spv_endpoint_not_available")
        val number = (request.value as? DeviceControlWriteValue.Number)?.value
            ?: return DeviceControlWriteResult.Rejected("moondrop_numeric_control_requires_number")
        if (!number.isFinite()) return DeviceControlWriteResult.Rejected("moondrop_numeric_value_not_finite")

        return when {
            request.controlId.value == "usb:moondrop:spv:pre_gain" ->
                writeSpvPreGain(context, endpoint, number, current)
            request.controlId.value.startsWith("usb:moondrop:spv:peq:band:") ->
                writeSpvPeqControl(context, endpoint, request.controlId, number, current)
            else -> DeviceControlWriteResult.Rejected(
                "moondrop_control_write_not_enabled:${request.controlId.value}",
            )
        }
    }

    private suspend fun writeSpvPreGain(
        context: UsbVendorAdapterContext,
        endpoint: MoondropEndpointPair,
        requested: Double,
        current: DeviceControlSnapshot,
    ): DeviceControlWriteResult {
        // RawSMusic intentionally uses a conservative attenuation-only authoring envelope here.
        // The native wire field can represent a much wider Q8.8 range, but the official Flutter
        // UI range has not yet been derived and positive pre-gain can clip badly.
        if (requested !in SPV_SAFE_PRE_GAIN_MIN_DB..SPV_SAFE_PRE_GAIN_MAX_DB) {
            return DeviceControlWriteResult.Rejected("spv_pre_gain_outside_safe_envelope")
        }
        val packet = MoondropSpvProtocol.scalarQ8_8WriteRequest(
            MoondropSpvProtocol.COMMAND_PRE_GAIN, requested,
        )
        val sent = MoondropSpvProtocol.writePacket(context.transport, endpoint.target, packet)
        if (sent.isFailure) {
            return DeviceControlWriteResult.Failed("spv_pre_gain_write_failed", sent.exceptionOrNull())
        }
        // Official Lyz4.t waits 100 ms before its optional flash-save step. Do not save here: the
        // generic slider can emit frequent updates and writing flash on every drag would be unsafe.
        delay(100)
        val readback = MoondropSpvProtocol.read(
            context.transport, endpoint.target, MoondropSpvProtocol.COMMAND_PRE_GAIN,
        ).getOrNull()?.let(MoondropSpvProtocol::q8_8)
            ?: return DeviceControlWriteResult.Failed("spv_pre_gain_readback_failed")
        val expected = MoondropSpvProtocol.q8_8Bytes(requested).toQ8_8Double()
        if (abs(readback - expected) > SPV_Q8_8_EPSILON) {
            return DeviceControlWriteResult.Failed("spv_pre_gain_readback_mismatch")
        }
        return DeviceControlWriteResult.Applied(
            current.updateNumericValues(mapOf("usb:moondrop:spv:pre_gain" to readback)),
        )
    }

    private suspend fun writeSpvPeqControl(
        context: UsbVendorAdapterContext,
        endpoint: MoondropEndpointPair,
        controlId: DeviceControlId,
        requested: Double,
        current: DeviceControlSnapshot,
    ): DeviceControlWriteResult {
        val match = SPV_PEQ_CONTROL_REGEX.matchEntire(controlId.value)
            ?: return DeviceControlWriteResult.Rejected("spv_peq_control_id_invalid")
        val bandIndex = match.groupValues[1].toIntOrNull()
            ?: return DeviceControlWriteResult.Rejected("spv_peq_band_invalid")
        val field = match.groupValues[2]
        val peq = current.capabilities.filterIsInstance<DeviceControlCapability.ParametricEq>()
            .firstOrNull { it.id.value == "usb:moondrop:spv:peq" }
            ?: return DeviceControlWriteResult.Rejected("spv_peq_not_in_current_snapshot")
        if (bandIndex !in peq.bands.indices) return DeviceControlWriteResult.Rejected("spv_peq_band_out_of_range")
        val control = when (field) {
            "frequency" -> peq.bands[bandIndex].frequency
            "q" -> peq.bands[bandIndex].q
            "gain" -> peq.bands[bandIndex].gain
            else -> null
        } ?: return DeviceControlWriteResult.Rejected("spv_peq_control_missing")
        if (control.access != DeviceControlAccess.READ_WRITE) {
            return DeviceControlWriteResult.Rejected("spv_peq_control_not_write_verified")
        }
        val range = control.ranges.firstOrNull()
            ?: return DeviceControlWriteResult.Rejected("spv_peq_write_range_missing")
        if (requested < range.min - 1e-9 || requested > range.max + 1e-9) {
            return DeviceControlWriteResult.Rejected("spv_peq_value_out_of_range")
        }

        // Re-read the one physical band immediately before every SET. This preserves filter type
        // and untouched parameters and re-validates the official coefficient block against the
        // exact attached device rather than trusting a stale UI snapshot.
        val live = MoondropSpvProtocol.readPeqPoint(
            context.transport, endpoint.target, bandIndex,
        ).getOrNull() ?: return DeviceControlWriteResult.Failed("spv_peq_live_read_failed")
        if (!live.hasOfficialPeakingCoefficients()) {
            return DeviceControlWriteResult.Rejected("spv_peq_coefficients_not_verified")
        }
        val updated = when (field) {
            "frequency" -> live.copy(frequencyHz = requested.roundToInt().toDouble(), coefficientWords = null)
            "q" -> live.copy(q = requested, coefficientWords = null)
            "gain" -> live.copy(gainDb = requested, coefficientWords = null)
            else -> live
        }
        val setPacket = runCatching { MoondropSpvProtocol.peqFullWriteRequest(bandIndex, updated) }
            .getOrElse { return DeviceControlWriteResult.Rejected("spv_peq_value_not_encodable") }
        val set = MoondropSpvProtocol.writePacket(context.transport, endpoint.target, setPacket)
        if (set.isFailure) return DeviceControlWriteResult.Failed("spv_peq_set_failed", set.exceptionOrNull())

        // Official Lyz4.w updates the current EQ coefficients in DSP registers. Save-to-flash is
        // deliberately NOT sent here because the settings slider has no on-release commit signal.
        val updateRegs = MoondropSpvProtocol.writePacket(
            context.transport, endpoint.target, MoondropSpvProtocol.updateEqToRegsRequest(peq.bands.size),
        )
        if (updateRegs.isFailure) {
            return DeviceControlWriteResult.Failed("spv_peq_update_regs_failed", updateRegs.exceptionOrNull())
        }
        delay(30)
        val readback = MoondropSpvProtocol.readPeqPoint(
            context.transport, endpoint.target, bandIndex,
        ).getOrNull() ?: return DeviceControlWriteResult.Failed("spv_peq_readback_failed")
        val expectedValue = when (field) {
            "frequency" -> updated.frequencyHz
            "q" -> MoondropSpvProtocol.q8_8Bytes(updated.q).toQ8_8Double()
            "gain" -> MoondropSpvProtocol.q8_8Bytes(updated.gainDb).toQ8_8Double()
            else -> requested
        }
        val actualValue = when (field) {
            "frequency" -> readback.frequencyHz
            "q" -> readback.q
            "gain" -> readback.gainDb
            else -> requested
        }
        val epsilon = if (field == "frequency") 0.5 else SPV_Q8_8_EPSILON
        if (abs(actualValue - expectedValue) > epsilon) {
            return DeviceControlWriteResult.Failed("spv_peq_readback_mismatch_$field")
        }

        val prefix = "usb:moondrop:spv:peq:band:$bandIndex"
        return DeviceControlWriteResult.Applied(
            current.updateNumericValues(
                mapOf(
                    "$prefix:frequency" to readback.frequencyHz,
                    "$prefix:q" to readback.q,
                    "$prefix:gain" to readback.gainDb,
                ),
            ),
        )
    }

    private suspend fun probeSpvCapabilities(
        context: UsbVendorAdapterContext,
        endpoint: MoondropEndpointPair,
        diagnostics: MutableList<String>,
    ): List<DeviceControlCapability> = buildList {
        val peqPoints = buildList {
            for (bandIndex in 0 until 32) {
                val point = MoondropSpvProtocol.readPeqPoint(
                    context.transport,
                    endpoint.target,
                    bandIndex,
                ).getOrNull() ?: break
                add(point)
                diagnostics += "usb.moondrop.spv peq_band=$bandIndex freq=${point.frequencyHz} q=${point.q} gain=${point.gainDb} filter=${point.filterType}"
            }
        }
        diagnostics += "usb.moondrop.spv peq_bands_detected=${peqPoints.size}"
        val verifiedPeakingBands = peqPoints.count { it.hasOfficialPeakingCoefficients() }
        diagnostics += "usb.moondrop.spv peq_full_coeff_verified=$verifiedPeakingBands/${peqPoints.size}"
        if (peqPoints.size >= 8) {
            add(
                DeviceControlCapability.ParametricEq(
                    id = DeviceControlId("usb:moondrop:spv:peq"),
                    label = "参数均衡器",
                    bands = peqPoints.mapIndexed { index, point ->
                        val prefix = "usb:moondrop:spv:peq:band:$index"
                        val writable = point.hasOfficialPeakingCoefficients()
                        if (!writable) {
                            diagnostics += "usb.moondrop.spv peq_band=$index write=false reason=full_coeff_not_verified_or_filter_${point.filterType}"
                        }
                        ParametricEqBand(
                            id = DeviceControlId(prefix),
                            label = "第 ${index + 1} 段",
                            frequency = nestedNumber(
                                "$prefix:frequency", "频率", point.frequencyHz, "Hz", "spv.peq.$index.frequency",
                                writable, listOf(DeviceNumericRange(20.0, 20_000.0, 1.0)),
                            ),
                            q = nestedNumber(
                                "$prefix:q", "Q", point.q, null, "spv.peq.$index.q",
                                writable, listOf(DeviceNumericRange(0.1, 10.0, 0.01)),
                            ),
                            gain = nestedNumber(
                                "$prefix:gain", "增益", point.gainDb, "dB", "spv.peq.$index.gain",
                                writable, listOf(DeviceNumericRange(-12.0, 12.0, 0.1)),
                            ),
                        )
                    },
                ),
            )
        }

        suspend fun read(command: Int, key: String): SpvFrame? {
            val frame = MoondropSpvProtocol.read(context.transport, endpoint.target, command).getOrNull()
            diagnostics += "usb.moondrop.spv feature=$key supported=${frame != null}" +
                if (frame != null) " payloadBytes=${frame.payload.size}" else ""
            return frame
        }

        read(MoondropSpvProtocol.COMMAND_ADC_VOLUME, "adc_volume")?.let { frame ->
            MoondropSpvProtocol.q8_8(frame)?.finiteReasonableDb()?.let { value ->
                add(readOnlyNumber("adc_volume", "ADC 音量", value, "dB"))
            }
        }
        read(MoondropSpvProtocol.COMMAND_DAC_GAIN, "dac_gain")?.let { frame ->
            MoondropSpvProtocol.q8_8(frame)?.finiteReasonableDb()?.let { value ->
                add(readOnlyNumber("dac_gain", "DAC 增益", value, "dB"))
            }
        }
        read(MoondropSpvProtocol.COMMAND_SELECTED_EQ, "selected_eq")?.let { frame ->
            MoondropSpvProtocol.firstUnsignedByte(frame)?.let { value ->
                add(readOnlyNumber("selected_eq", "当前均衡器预设", value.toDouble(), null))
            }
        }
        read(MoondropSpvProtocol.COMMAND_CS43131_FILTER, "cs43131_filter")?.let { frame ->
            MoondropSpvProtocol.firstUnsignedByte(frame)?.let { value ->
                add(readOnlyNumber("cs43131_filter", "CS43131 数字滤波器", value.toDouble(), null))
            }
        }
        read(MoondropSpvProtocol.COMMAND_CS43131_WORKING_MODE, "cs43131_working_mode")?.let { frame ->
            MoondropSpvProtocol.firstUnsignedByte(frame)?.let { value ->
                add(readOnlyNumber("cs43131_working_mode", "CS43131 工作模式", value.toDouble(), null))
            }
        }
        read(MoondropSpvProtocol.COMMAND_PRE_GAIN, "pre_gain")?.let { frame ->
            MoondropSpvProtocol.q8_8(frame)?.finiteReasonableDb()?.let { value ->
                val writable = value in SPV_SAFE_PRE_GAIN_MIN_DB..SPV_SAFE_PRE_GAIN_MAX_DB
                diagnostics += "usb.moondrop.spv pre_gain_write=$writable range=$SPV_SAFE_PRE_GAIN_MIN_DB..$SPV_SAFE_PRE_GAIN_MAX_DB volatile=true"
                add(
                    numberCapability(
                        "pre_gain", "前级增益", value, "dB", writable,
                        listOf(DeviceNumericRange(SPV_SAFE_PRE_GAIN_MIN_DB, SPV_SAFE_PRE_GAIN_MAX_DB, 0.1)),
                    ),
                )
            }
        }
        read(MoondropSpvProtocol.COMMAND_ANC, "anc")?.let { frame ->
            MoondropSpvProtocol.firstUnsignedByte(frame)?.let { value ->
                add(readOnlyNumber("anc", "主动降噪模式", value.toDouble(), null))
            }
        }
        read(MoondropSpvProtocol.COMMAND_LED, "led")?.let { frame ->
            MoondropSpvProtocol.firstUnsignedByte(frame)?.takeIf { it == 0 || it == 1 }?.let { value ->
                add(readOnlyToggle("led", "指示灯", value != 0))
            }
        }
        read(MoondropSpvProtocol.COMMAND_SENSOR, "sensor")?.let { frame ->
            MoondropSpvProtocol.firstUnsignedByte(frame)?.takeIf { it == 0 || it == 1 }?.let { value ->
                add(readOnlyToggle("sensor", "传感器", value != 0))
            }
        }
        // Spatial/prompt/input-source replies are intentionally recorded as confirmed protocol
        // support first. Their multi-field enums differ between SPV generations, so do not render
        // a misleading toggle/choice until the exact payload variant is identified from readback.
        read(MoondropSpvProtocol.COMMAND_SPATIAL_AUDIO, "spatial_audio")?.let { frame ->
            diagnostics += "usb.moondrop.spv spatial_payload=${frame.payload.toHex(16)}"
        }
        read(MoondropSpvProtocol.COMMAND_PROMPT_TONE, "prompt_tone")?.let { frame ->
            diagnostics += "usb.moondrop.spv prompt_payload=${frame.payload.toHex(16)}"
        }
        read(MoondropSpvProtocol.COMMAND_INPUT_SOURCE, "input_source")?.let { frame ->
            diagnostics += "usb.moondrop.spv input_source_payload=${frame.payload.toHex(16)}"
        }
    }

    private fun readOnlyNumber(
        key: String,
        label: String,
        value: Double,
        unit: String?,
    ): DeviceControlCapability.Range = numberCapability(key, label, value, unit, writable = false, ranges = emptyList())

    private fun numberCapability(
        key: String,
        label: String,
        value: Double,
        unit: String?,
        writable: Boolean,
        ranges: List<DeviceNumericRange>,
    ): DeviceControlCapability.Range {
        val id = DeviceControlId("usb:moondrop:spv:$key")
        return DeviceControlCapability.Range(
            id = id,
            label = label,
            control = DeviceNumericControl(
                id = id,
                label = label,
                unit = unit,
                access = if (writable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
                current = value,
                ranges = if (writable) ranges else emptyList(),
                address = DeviceControlBackendAddress.UsbVendor(ADAPTER_ID, "spv.$key"),
            ),
        )
    }

    private fun nestedNumber(
        idValue: String,
        label: String,
        value: Double,
        unit: String?,
        endpointKey: String,
        writable: Boolean,
        ranges: List<DeviceNumericRange>,
    ): DeviceNumericControl = DeviceNumericControl(
        id = DeviceControlId(idValue),
        label = label,
        unit = unit,
        access = if (writable) DeviceControlAccess.READ_WRITE else DeviceControlAccess.READ_ONLY,
        current = value,
        ranges = if (writable) ranges else emptyList(),
        address = DeviceControlBackendAddress.UsbVendor(ADAPTER_ID, endpointKey),
    )

    private fun readOnlyToggle(key: String, label: String, enabled: Boolean): DeviceControlCapability.Toggle {
        val id = DeviceControlId("usb:moondrop:spv:$key")
        return DeviceControlCapability.Toggle(
            id = id,
            label = label,
            control = DeviceToggleControl(
                id = id,
                label = label,
                access = DeviceControlAccess.READ_ONLY,
                current = enabled,
                address = DeviceControlBackendAddress.UsbVendor(ADAPTER_ID, "spv.$key"),
            ),
        )
    }

    private fun resolveSpvEndpoint(context: UsbVendorAdapterContext): MoondropEndpointPair? {
        val fingerprint = MoondropSpvHidIdentity.fingerprint(context.inventory) ?: return null
        return MoondropEndpointSelector.candidates(context.inventory)
            .singleOrNull { it.target.interfaceNumber == fingerprint.interfaceNumber }
    }

    private fun DeviceControlSnapshot.updateNumericValues(values: Map<String, Double>): DeviceControlSnapshot = copy(
        capabilities = capabilities.map { capability ->
            when (capability) {
                is DeviceControlCapability.Range -> capability.copy(
                    control = capability.control.withCurrent(values),
                )
                is DeviceControlCapability.ParametricEq -> capability.copy(
                    bands = capability.bands.map { band ->
                        band.copy(
                            frequency = band.frequency?.withCurrent(values),
                            q = band.q?.withCurrent(values),
                            gain = band.gain?.withCurrent(values),
                        )
                    },
                )
                else -> capability
            }
        },
    )

    private fun DeviceNumericControl.withCurrent(values: Map<String, Double>): DeviceNumericControl =
        values[id.value]?.let { copy(current = it) } ?: this

    private fun ByteArray.toQ8_8Double(): Double {
        require(size >= 2)
        val raw = ((this[1].toInt() and 0xff) shl 8) or (this[0].toInt() and 0xff)
        return raw.toShort().toInt() / 256.0
    }

    private fun Double.finiteReasonableDb(): Double? = takeIf { isFinite() && this in -256.0..255.996 }

    private fun ByteArray.toHex(limit: Int): String = take(limit).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val ADAPTER_ID = "moondrop.usb.dynamic.v1"
        private val SPV_PEQ_CONTROL_REGEX = Regex("usb:moondrop:spv:peq:band:(\\d+):(frequency|q|gain)")
        private const val SPV_SAFE_PRE_GAIN_MIN_DB = -12.0
        private const val SPV_SAFE_PRE_GAIN_MAX_DB = 0.0
        private const val SPV_Q8_8_EPSILON = (1.0 / 256.0) + 1e-6
    }
}

object MoondropUsbVendorAdapters {
    fun production(): UsbVendorAdapterRegistry = UsbVendorAdapterRegistry(
        listOf(MoondropUsbVendorAdapter()),
    )
}
