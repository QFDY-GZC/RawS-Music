package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * OPPO/OPlus OPO RFCOMM protocol helpers for the HeyMelody 079A/legacy 1107 control services.
 *
 * Real Enco Free4 evidence plus the current device protocol model
 * confirms that bytes 4/5 are a little-endian 16-bit command, not category/sub-command fields:
 *   command = frame[4] | (frame[5] << 8)
 * and bytes 7/8 are the little-endian payload length. Normal frames are:
 *   artwork | len | 00 00 | cmdLo cmdHi | seq | payloadLenLo payloadLenHi | payload...
 * where total frame bytes = len + 2.
 *
 * Safety boundary:
 * - HELLO plus explicit QUERY commands remain non-setting operations;
 * - there is deliberately no REGISTER/token flow; setter builders are used only behind exact signed
 *   product-profile, service-UUID, runtime capability, ACK and authoritative readback gates;
 * - decoded EQ capabilities use the device/profile band topology and never impose a 5/10-band cap.
 */
internal object BluetoothOpoRfcommProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5")
    val SERVICE_UUID_1107: UUID = UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5")
    val KNOWN_SERVICE_UUIDS: Set<UUID> = linkedSetOf(SERVICE_UUID, SERVICE_UUID_1107)

    /** Legacy System HELLO physically confirmed on Enco Free4. Keep the exact proven 10 bytes. */
    val HELLO: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x07, 0x00, 0x00, 0x00, 0x01, 0x23, 0x00, 0x00, 0x12,
    )

    // OPO query/response command numbers (wire bytes 4/5 are low/high).
    const val CMD_QUERY_CAPABILITY = 0x0100
    const val CMD_CAPABILITY_RESP = 0x8100
    const val CMD_QUERY_PRODUCT_ID = 0x0103
    const val CMD_PRODUCT_ID_RESP = 0x8103
    const val CMD_QUERY_VERSION = 0x0105
    const val CMD_VERSION_RESP = 0x8105
    const val CMD_QUERY_BATTERY = 0x0106
    const val CMD_BATTERY_RESP = 0x8106
    const val CMD_QUERY_ANC = 0x010C
    const val CMD_ANC_RESP = 0x810C
    const val CMD_QUERY_FEATURE_SWITCH = 0x010D
    const val CMD_FEATURE_SWITCH_RESP = 0x810D
    const val CMD_QUERY_EQ_CURRENT = 0x010F
    const val CMD_EQ_CURRENT_RESP = 0x810F
    const val CMD_QUERY_EQ_ALL = 0x0122
    const val CMD_EQ_ALL_RESP = 0x8122
    const val CMD_QUERY_HEADSET_SPATIAL = 0x012A
    const val CMD_HEADSET_SPATIAL_RESP = 0x812A
    const val CMD_QUERY_PROMPT_VOLUME = 0x0130
    const val CMD_PROMPT_VOLUME_RESP = 0x8130
    const val CMD_ACTIVE_REPORT = 0x0204

    // Runtime capability facts used to gate the setter commands declared below.
    const val CMD_SET_FEATURE_SWITCH = 0x0403
    const val CMD_SET_ANC = 0x0404
    const val CMD_SET_EQ_PRESET = 0x0406
    const val CMD_SET_EQ_DETAIL = 0x0418
    const val CMD_SET_HEADSET_SPATIAL = 0x0422
    const val CMD_SET_PROMPT_VOLUME = 0x0427

    data class ReadOnlyQuery(
        val name: String,
        val command: Int,
        val expectedResponseCommand: Int,
        val sequence: Int,
        val payload: ByteArray = byteArrayOf(),
        /** Bounded wait for this command. Large compound payloads such as 0x8122 need longer. */
        val responseTimeoutMs: Long = 1_800L,
    ) {
        val packet: ByteArray = buildPacket(command, sequence, payload)

        fun matches(frame: Frame): Boolean =
            frame.command == expectedResponseCommand && frame.sequence == sequence
    }

    /**
     * Read-only sequence. Capability is deliberately first so diagnostics can explain unsupported
     * later queries, but all requests in this list are safe GET/query commands even if ignored.
     */
    val READ_ONLY_QUERIES: List<ReadOnlyQuery> = listOf(
        ReadOnlyQuery("capability", CMD_QUERY_CAPABILITY, CMD_CAPABILITY_RESP, 0x30),
        ReadOnlyQuery("product_id", CMD_QUERY_PRODUCT_ID, CMD_PRODUCT_ID_RESP, 0x31),
        ReadOnlyQuery("version", CMD_QUERY_VERSION, CMD_VERSION_RESP, 0x32),
        ReadOnlyQuery("battery", CMD_QUERY_BATTERY, CMD_BATTERY_RESP, 0x33),
        ReadOnlyQuery(
            "anc",
            CMD_QUERY_ANC,
            CMD_ANC_RESP,
            0x34,
            byteArrayOf(0x01, 0x01),
        ),
        // HeyMelody 16.9.1 PollCommandManager uses action=2,type=1 to retrieve the
        // runtime-supported ANC mode bitmap for the same 0x010C command family.
        ReadOnlyQuery(
            "anc_support",
            CMD_QUERY_ANC,
            CMD_ANC_RESP,
            0x37,
            byteArrayOf(0x02, 0x01),
        ),
        ReadOnlyQuery("eq_current", CMD_QUERY_EQ_CURRENT, CMD_EQ_CURRENT_RESP, 0x35),
        ReadOnlyQuery(
            "feature_switches",
            CMD_QUERY_FEATURE_SWITCH,
            CMD_FEATURE_SWITCH_RESP,
            0x38,
            buildFeatureSwitchQueryPayload(BluetoothOpoHeyMelodyFeatureMap.queryIds),
        ),
        ReadOnlyQuery("headset_spatial", CMD_QUERY_HEADSET_SPATIAL, CMD_HEADSET_SPATIAL_RESP, 0x39),
        ReadOnlyQuery("prompt_volume", CMD_QUERY_PROMPT_VOLUME, CMD_PROMPT_VOLUME_RESP, 0x3A),
        ReadOnlyQuery(
            "eq_all",
            CMD_QUERY_EQ_ALL,
            CMD_EQ_ALL_RESP,
            0x36,
            byteArrayOf(0x01, 0x05),
            responseTimeoutMs = 4_500L,
        ),
    )

    data class Fingerprint(
        val bytes: Int,
        val startsWithOpoSof: Boolean,
        val command: Int?,
        val sequence: Int?,
        val sha256Prefix: String,
    )

    data class HeaderCandidate(
        val offset: Int,
        val declaredLength: Int?,
        val command: Int?,
        val sequence: Int?,
    )

    data class ChunkFingerprint(
        val bytes: Int,
        val sha256Prefix: String,
        val candidates: List<HeaderCandidate>,
    ) {
        val hasOpoCandidate: Boolean get() = candidates.isNotEmpty()
    }

    /** One complete inbound OPO frame reassembled from the RFCOMM byte stream. */
    data class Frame(
        val bytes: Int,
        val declaredLength: Int,
        val command: Int?,
        val sequence: Int?,
        val declaredPayloadLength: Int?,
        val payload: ByteArray,
        val payloadTruncated: Boolean,
        /** Bounded raw state only for known non-identity controls. */
        val controlDataHex: String?,
        val sha256Prefix: String,
    ) {
        val dataBytes: Int get() = payload.size
        val commandLow: Int? get() = command?.and(0xff)
        val commandHigh: Int? get() = command?.ushr(8)?.and(0xff)

        // Compatibility aliases for older tests/diagnostic helpers. New code should use command.
        val category: Int? get() = commandLow
        val subCommand: Int? get() = commandHigh
    }

    data class QueryObservation(
        val name: String,
        val command: Int,
        val expectedResponseCommand: Int,
        val sequence: Int,
        val frames: List<Frame>,
        val matchedResponses: Int,
    ) {
        val matchedFrames: List<Frame>
            get() = frames.filter { it.command == expectedResponseCommand && it.sequence == sequence }
    }

    data class StreamObservation(
        val passiveChunks: List<ChunkFingerprint>,
        val postHelloChunks: List<ChunkFingerprint>,
        val passiveFrames: List<Frame> = emptyList(),
        val postHelloFrames: List<Frame> = emptyList(),
        val queryObservations: List<QueryObservation> = emptyList(),
    ) {
        val passiveHasOpo: Boolean
            get() = passiveFrames.isNotEmpty() || passiveChunks.any { it.hasOpoCandidate }
        val postHelloHasOpo: Boolean
            get() = postHelloFrames.isNotEmpty() || postHelloChunks.any { it.hasOpoCandidate }
        val hasOpoCandidate: Boolean
            get() = passiveHasOpo || postHelloHasOpo || queryObservations.any { it.frames.isNotEmpty() }
        val helloAckConfirmed: Boolean
            get() = postHelloFrames.any(::isHelloAck)
        val matchedReadOnlyResponses: Int
            get() = queryObservations.sumOf { it.matchedResponses }

        fun matchedFrame(queryName: String): Frame? =
            queryObservations.firstOrNull { it.name == queryName }?.matchedFrames?.firstOrNull()
    }

    data class EqInfoEntry(
        val isSelected: Boolean,
        val minGainDb: Int,
        val maxGainDb: Int,
        val eqId: Int,
        val name: String,
        val frequenciesHz: List<Int>,
        val gainsDb: List<Int>,
    )

    data class EqAllDecode(
        val entries: List<EqInfoEntry>,
        val error: String? = null,
    )

    data class DecodedReadOnlyState(
        val supportedCommands: Set<Int> = emptySet(),
        val capabilityBitmapHex: String? = null,
        val productId: String? = null,
        val firmwareVersion: String? = null,
        val batteryPayload: ByteArray? = null,
        val ancMode: String? = null,
        val currentAncProtocolIndex: Int? = null,
        val supportedAncProtocolIndices: Set<Int> = emptySet(),
        val currentEqId: Int? = null,
        val featureSwitchStatuses: Map<Int, Int> = emptyMap(),
        val headsetSpatialType: Int? = null,
        val promptVolumeValue: Int? = null,
        val eqEntries: List<EqInfoEntry> = emptyList(),
        val eqDecodeError: String? = null,
    )

    /**
     * Incremental RFCOMM frame decoder. Real Enco Free4 evidence shows total bytes == LEN + 2.
     */
    class FrameDecoder {
        private var pending = ByteArray(0)

        fun feed(chunk: ByteArray): List<Frame> {
            if (chunk.isNotEmpty()) pending += chunk
            val result = mutableListOf<Frame>()
            while (true) {
                val sof = pending.indexOfFirst { (it.toInt() and 0xff) == OPO_SOF }
                if (sof < 0) {
                    pending = ByteArray(0)
                    break
                }
                if (sof > 0) pending = pending.copyOfRange(sof, pending.size)
                if (pending.size < 2) break

                val declared = pending[1].toInt() and 0xff
                val total = declared + INBOUND_LENGTH_OVERHEAD
                if (total < MIN_INBOUND_FRAME_BYTES || total > MAX_INBOUND_FRAME_BYTES) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                if (pending.size < total) break

                val raw = pending.copyOfRange(0, total)
                pending = pending.copyOfRange(total, pending.size)
                result += frame(raw, declared)
            }
            return result
        }

        fun bufferedBytes(): Int = pending.size
    }

    fun buildPacket(command: Int, sequence: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(command in 0..0xffff)
        require(sequence in 0..0xff)
        require(payload.size <= MAX_OUTBOUND_PAYLOAD_BYTES)
        val declared = 7 + payload.size
        val packet = ByteArray(2 + declared)
        packet[0] = OPO_SOF.toByte()
        packet[1] = declared.toByte()
        packet[2] = 0
        packet[3] = 0
        packet[4] = (command and 0xff).toByte()
        packet[5] = ((command ushr 8) and 0xff).toByte()
        packet[6] = sequence.toByte()
        packet[7] = (payload.size and 0xff).toByte()
        packet[8] = ((payload.size ushr 8) and 0xff).toByte()
        payload.copyInto(packet, 9)
        return packet
    }

    fun isHelloAck(frame: Frame): Boolean =
        frame.command == CMD_CAPABILITY_RESP && frame.sequence == HELLO_SEQUENCE

    fun fingerprint(value: ByteArray): Fingerprint {
        val startsWithOpoSof = value.firstOrNull()?.toInt()?.and(0xff) == OPO_SOF
        return Fingerprint(
            bytes = value.size,
            startsWithOpoSof = startsWithOpoSof,
            command = commandAt(value, 4),
            sequence = value.getOrNull(6)?.toInt()?.and(0xff),
            sha256Prefix = sha256Prefix(value),
        )
    }

    fun chunkFingerprint(value: ByteArray): ChunkFingerprint = ChunkFingerprint(
        bytes = value.size,
        sha256Prefix = sha256Prefix(value),
        candidates = headerCandidates(value),
    )

    fun headerCandidates(value: ByteArray): List<HeaderCandidate> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<HeaderCandidate>()
        for (offset in value.indices) {
            if ((value[offset].toInt() and 0xff) != OPO_SOF) continue
            result += HeaderCandidate(
                offset = offset,
                declaredLength = value.getOrNull(offset + 1)?.toInt()?.and(0xff),
                command = commandAt(value, offset + 4),
                sequence = value.getOrNull(offset + 6)?.toInt()?.and(0xff),
            )
        }
        return result
    }

    fun decodeReadOnlyState(observation: StreamObservation): DecodedReadOnlyState {
        val capabilityPayload = observation.matchedFrame("capability")?.payload
        val productPayload = observation.matchedFrame("product_id")?.payload
        val versionPayload = observation.matchedFrame("version")?.payload
        val batteryPayload = observation.matchedFrame("battery")?.payload
        val ancPayload = observation.matchedFrame("anc")?.payload
        val ancSupportPayload = observation.matchedFrame("anc_support")?.payload
        val eqCurrentPayload = observation.matchedFrame("eq_current")?.payload
        val featureSwitchPayload = observation.matchedFrame("feature_switches")?.payload
        val headsetSpatialPayload = observation.matchedFrame("headset_spatial")?.payload
        val promptVolumePayload = observation.matchedFrame("prompt_volume")?.payload
        val eqAllPayload = observation.matchedFrame("eq_all")?.payload
        val eqAll = eqAllPayload?.let(::parseEqAllPayload) ?: EqAllDecode(emptyList())
        return DecodedReadOnlyState(
            supportedCommands = capabilityPayload?.let(::parseSupportedCommands).orEmpty(),
            capabilityBitmapHex = capabilityPayload?.let(::capabilityBitmapHex),
            productId = productPayload?.let(::parseProductId),
            firmwareVersion = versionPayload?.let(::parseVersion),
            batteryPayload = batteryPayload,
            ancMode = ancPayload?.let(::parseAncMode),
            currentAncProtocolIndex = ancPayload?.let(::parseCurrentAncProtocolIndex),
            supportedAncProtocolIndices = ancSupportPayload?.let(::parseSupportedAncProtocolIndices).orEmpty(),
            currentEqId = eqCurrentPayload?.let(::parseCurrentEqId),
            featureSwitchStatuses = featureSwitchPayload?.let(::parseFeatureSwitchStatuses).orEmpty(),
            headsetSpatialType = headsetSpatialPayload?.let(::parseHeadsetSpatialType),
            promptVolumeValue = promptVolumePayload?.let(::parsePromptVolumeValue),
            eqEntries = eqAll.entries,
            eqDecodeError = eqAll.error,
        )
    }

    fun parseProductId(payload: ByteArray): String? {
        if (payload.size < 4 || payload[0].u8() != 0) return null
        val id = payload[1].u8() or (payload[2].u8() shl 8) or (payload[3].u8() shl 16)
        return id.toString(16).uppercase().padStart(6, '0')
    }

    fun parseVersion(payload: ByteArray): String? {
        if (payload.size < 3 || payload[0].u8() != 0) return null
        return runCatching {
            String(payload, 2, payload.size - 2, StandardCharsets.UTF_8)
                .trimEnd('\u0000')
                .trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun parseCurrentEqId(payload: ByteArray): Int? =
        if (payload.size >= 2 && payload[0].u8() == 0) payload[1].u8() else null

    /** Return the active protocolIndex from action=1,type=1 CurrentNoiseModeInfo. */
    fun parseCurrentAncProtocolIndex(payload: ByteArray): Int? {
        val value = parseNoiseReductionBitmap(payload, expectedAction = 0x01, expectedType = 0x01) ?: return null
        return firstSetBit(value)
    }

    /**
     * HeyMelody 16.9.1 action=2,type=1 response for getSupportNoiseReductionMode.
     * Payload is status + NoiseReductionInfo(action,type,valueLE), and every set value bit is a
     * protocolIndex accepted by the current firmware.
     */
    fun parseSupportedAncProtocolIndices(payload: ByteArray): Set<Int> {
        val value = parseNoiseReductionBitmap(payload, expectedAction = 0x02, expectedType = 0x01)
            ?: return emptySet()
        return buildSet {
            for (index in 0 until 32) if ((value and (1L shl index)) != 0L) add(index)
        }
    }


    /** HeyMelody GET 0x010D payload: count followed by one-byte feature IDs. */
    fun buildFeatureSwitchQueryPayload(featureIds: List<Int>): ByteArray {
        require(featureIds.size <= 0xff)
        require(featureIds.all { it in 0..0xff })
        return ByteArray(featureIds.size + 1).also { out ->
            out[0] = featureIds.size.toByte()
            featureIds.forEachIndexed { index, id -> out[index + 1] = id.toByte() }
        }
    }

    /** HeyMelody 0x810D: status, count, then repeated featureId/status byte pairs. */
    fun parseFeatureSwitchStatuses(payload: ByteArray): Map<Int, Int> {
        if (payload.size < 2 || payload[0].u8() != 0) return emptyMap()
        val count = payload[1].u8()
        if (payload.size < 2 + count * 2) return emptyMap()
        return buildMap {
            var pos = 2
            repeat(count) {
                put(payload[pos].u8(), payload[pos + 1].u8())
                pos += 2
            }
        }
    }

    fun parseHeadsetSpatialType(payload: ByteArray): Int? =
        if (payload.size >= 2 && payload[0].u8() == 0) payload[1].u8() else null

    /** HeyMelody 0x8130 VolumeValueInfo: status followed by the current one-byte prompt volume. */
    fun parsePromptVolumeValue(payload: ByteArray): Int? =
        if (payload.size >= 2 && payload[0].u8() == 0) payload[1].u8() else null

    fun buildFeatureSwitchPayload(featureId: Int, enabled: Boolean): ByteArray {
        require(featureId in 0..0xff)
        return byteArrayOf(featureId.toByte(), if (enabled) 1 else 0)
    }

    fun buildHeadsetSpatialPayload(type: Int): ByteArray {
        require(type in 0..0xff)
        return byteArrayOf(type.toByte())
    }

    fun buildPromptVolumePayload(value: Int): ByteArray {
        require(value in 0..0xff)
        return byteArrayOf(value.toByte())
    }

    fun buildAncModePayload(protocolIndex: Int): ByteArray {
        require(protocolIndex in 0 until 32)
        val bytes = protocolIndex / 8 + 1
        return ByteArray(2 + bytes).also { payload ->
            payload[0] = 0x01
            payload[1] = 0x01
            payload[2 + protocolIndex / 8] = (1 shl (protocolIndex % 8)).toByte()
        }
    }

    fun buildEqPresetPayload(eqId: Int): ByteArray {
        require(eqId in 0..0xff)
        return byteArrayOf(eqId.toByte())
    }

    /** Exact HeyMelody 16.9.1 setEqInfo (0x0418) payload encoder. */
    fun buildEqDetailPayload(
        action: Int,
        minGainDb: Int,
        maxGainDb: Int,
        eqId: Int,
        name: String,
        frequenciesHz: List<Int>,
        gainsDb: List<Int>,
    ): ByteArray {
        require(action in 1..3)
        require(eqId in 0..0xff)
        require(minGainDb in -128..127 && maxGainDb in -128..127 && maxGainDb >= minGainDb)
        require(frequenciesHz.size == gainsDb.size && frequenciesHz.isNotEmpty())
        require(frequenciesHz.size <= 255)
        require(frequenciesHz.all { it in 0..0xffff })
        require(gainsDb.all { it in minGainDb..maxGainDb })
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size <= 255)
        val out = ByteArray(6 + nameBytes.size + frequenciesHz.size * 3)
        out[0] = action.toByte()
        out[1] = minGainDb.toByte()
        out[2] = maxGainDb.toByte()
        out[3] = eqId.toByte()
        out[4] = nameBytes.size.toByte()
        nameBytes.copyInto(out, 5)
        var pos = 5 + nameBytes.size
        out[pos++] = frequenciesHz.size.toByte()
        frequenciesHz.indices.forEach { index ->
            val frequency = frequenciesHz[index]
            out[pos++] = (frequency and 0xff).toByte()
            out[pos++] = ((frequency ushr 8) and 0xff).toByte()
            out[pos++] = gainsDb[index].toByte()
        }
        return out
    }

    private fun parseNoiseReductionBitmap(
        payload: ByteArray,
        expectedAction: Int,
        expectedType: Int,
    ): Long? {
        if (payload.size < 4 || payload[0].u8() != 0) return null
        if (payload[1].u8() != expectedAction || payload[2].u8() != expectedType) return null
        val count = minOf(payload.size - 3, 4)
        var value = 0L
        repeat(count) { index -> value = value or (payload[3 + index].u8().toLong() shl (index * 8)) }
        return value
    }

    private fun firstSetBit(value: Long): Int? {
        if (value == 0L) return null
        for (index in 0 until 32) if ((value and (1L shl index)) != 0L) return index
        return null
    }

    /** Conservative current-mode decoder. Supported mode list still comes from device/profile data. */
    fun parseAncMode(payload: ByteArray): String? {
        for (i in 0..(payload.size - 4).coerceAtLeast(-1)) {
            if (payload.getOrNull(i)?.u8() != 0x01 || payload.getOrNull(i + 1)?.u8() != 0x01) continue
            val v1 = payload[i + 2].u8()
            val v2 = payload[i + 3].u8()
            return ANC_VALUES[v1 to v2]
        }
        return null
    }

    /**
     * Parse getAllEqInfo (0x8122): `status,count,entries...`. Each entry carries its own
     * freqCount, so 6/10/20/30/etc bands are preserved exactly as reported by the device.
     */
    fun parseEqAllPayload(payload: ByteArray): EqAllDecode {
        if (payload.size < 2) return EqAllDecode(emptyList(), "too_short")
        if (payload[0].u8() != 0) return EqAllDecode(emptyList(), "status_${payload[0].u8()}")
        val count = payload[1].u8()
        var pos = 2
        val entries = mutableListOf<EqInfoEntry>()
        repeat(count) { entryIndex ->
            if (pos + 5 > payload.size) {
                return EqAllDecode(entries, "entry_${entryIndex}_header_truncated")
            }
            val selected = payload[pos].u8() != 0
            val minGain = payload[pos + 1].toInt()
            val maxGain = payload[pos + 2].toInt()
            val eqId = payload[pos + 3].u8()
            val nameLen = payload[pos + 4].u8()
            pos += 5
            if (pos + nameLen > payload.size) {
                return EqAllDecode(entries, "entry_${entryIndex}_name_truncated")
            }
            val name = runCatching {
                String(payload, pos, nameLen, StandardCharsets.UTF_8).trim().trimEnd('\u0000')
            }.getOrDefault("")
            pos += nameLen
            if (pos >= payload.size) {
                return EqAllDecode(entries, "entry_${entryIndex}_missing_freq_count")
            }
            val freqCount = payload[pos].u8()
            pos++
            val required = freqCount * 3
            if (pos + required > payload.size) {
                return EqAllDecode(entries, "entry_${entryIndex}_bands_truncated:${freqCount}")
            }
            val frequencies = ArrayList<Int>(freqCount)
            val gains = ArrayList<Int>(freqCount)
            repeat(freqCount) {
                frequencies += payload[pos].u8() or (payload[pos + 1].u8() shl 8)
                gains += payload[pos + 2].toInt()
                pos += 3
            }
            entries += EqInfoEntry(
                isSelected = selected,
                minGainDb = minGain,
                maxGainDb = maxGain,
                eqId = eqId,
                name = name,
                frequenciesHz = frequencies,
                gainsDb = gains,
            )
        }
        return EqAllDecode(entries)
    }

    fun capabilityBitmapHex(payload: ByteArray): String? {
        if (payload.size <= 1 || payload[0].u8() != 0) return null
        return payload.copyOfRange(1, payload.size)
            .joinToString("") { "%02x".format(it.u8()) }
            .takeIf { it.isNotEmpty() }
    }

    /** Port of the current HeyMelody capability-bit mapping used by OppoPodsManager. */
    fun parseSupportedCommands(payload: ByteArray): Set<Int> {
        if (payload.size <= 1 || payload[0].u8() != 0) return emptySet()
        val result = linkedSetOf<Int>()
        val bitCount = minOf((payload.size - 1) * 8, CAPABILITY_COMMANDS.size)
        for (bit in 0 until bitCount) {
            if ((payload[1 + bit / 8].u8() and (1 shl (bit % 8))) == 0) continue
            result += CAPABILITY_COMMANDS[bit]
        }
        return result
    }

    private fun frame(raw: ByteArray, declaredLength: Int): Frame {
        val command = commandAt(raw, 4)
        val declaredPayloadLength = if (raw.size >= 9) raw[7].u8() or (raw[8].u8() shl 8) else null
        val availablePayload = (raw.size - 9).coerceAtLeast(0)
        val payloadBytes = minOf(declaredPayloadLength ?: availablePayload, availablePayload)
        val payload = if (payloadBytes > 0) raw.copyOfRange(9, 9 + payloadBytes) else byteArrayOf()
        val truncated = declaredPayloadLength != null && declaredPayloadLength > availablePayload
        val exposeControl = command == CMD_ACTIVE_REPORT ||
            command == CMD_ANC_RESP ||
            command == CMD_EQ_CURRENT_RESP ||
            command == CMD_EQ_ALL_RESP
        val controlDataHex = if (exposeControl && payload.isNotEmpty()) {
            payload.take(MAX_CONTROL_DATA_DIAGNOSTIC_BYTES)
                .joinToString("") { "%02x".format(it.u8()) }
                .takeIf { it.isNotEmpty() }
        } else null
        return Frame(
            bytes = raw.size,
            declaredLength = declaredLength,
            command = command,
            sequence = raw.getOrNull(6)?.u8(),
            declaredPayloadLength = declaredPayloadLength,
            payload = payload,
            payloadTruncated = truncated,
            controlDataHex = controlDataHex,
            sha256Prefix = sha256Prefix(raw),
        )
    }

    private fun commandAt(value: ByteArray, offset: Int): Int? {
        val low = value.getOrNull(offset)?.u8() ?: return null
        val high = value.getOrNull(offset + 1)?.u8() ?: return null
        return low or (high shl 8)
    }

    private fun sha256Prefix(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .take(8)
            .joinToString("") { "%02x".format(it.u8()) }

    private fun Byte.u8(): Int = toInt() and 0xff

    private val ANC_VALUES = mapOf(
        (8 to 0) to "Off",
        (2 to 0) to "Smart",
        (0x80 to 0) to "Smart",
        (0x40 to 0) to "Light",
        (0x20 to 0) to "Medium",
        (0x10 to 0) to "Deep",
        (0 to 1) to "Transparency",
        (0 to 2) to "Transparency",
        (4 to 0) to "Transparency",
        (0 to 8) to "Adaptive",
    )

    // Decimal values ported verbatim from the current protocol-derived 67-bit capability map.
    private val CAPABILITY_COMMANDS: List<List<Int>> = listOf(
        listOf(261), listOf(262), listOf(263), listOf(264, 1025, 1046), listOf(265), listOf(1024), listOf(1026), listOf(1027), listOf(268, 1028), listOf(1029),
        listOf(1030, 271), listOf(1031), emptyList(), listOf(1032), listOf(1033), emptyList(), emptyList(), listOf(276), emptyList(), listOf(1038, 1037, 277, 278),
        listOf(1039), listOf(1040, 281), listOf(517), listOf(3840), emptyList(), listOf(280, 1041), listOf(282, 1042), listOf(284, 1043), emptyList(),
        listOf(274, 1035), listOf(286, 287, 1045), listOf(1037), emptyList(), listOf(289, 1047), listOf(290, 1048), emptyList(), listOf(285, 1044),
        listOf(291, 1050), listOf(292, 1051), listOf(293, 1052, 295, 1053, 1055), listOf(1057, 35, 36, 34, 294, 297),
        listOf(61185), listOf(61186), listOf(61187, 1054), listOf(1056), listOf(28), emptyList(), listOf(1058, 298), listOf(61188), listOf(1059, 299), emptyList(),
        listOf(1060), listOf(61190), emptyList(), emptyList(), listOf(1061, 302, 1062), listOf(303), listOf(1063, 304), listOf(305, 1064), listOf(1065, 306),
        listOf(20), listOf(1069, 307), listOf(1070), listOf(61191), listOf(61192), listOf(61193), listOf(1073, 308),
    )

    private const val OPO_SOF = 0xAA
    private const val HELLO_SEQUENCE = 0x23
    private const val INBOUND_LENGTH_OVERHEAD = 2
    private const val MIN_INBOUND_FRAME_BYTES = 9
    private const val MAX_INBOUND_FRAME_BYTES = 512
    private const val MAX_OUTBOUND_PAYLOAD_BYTES = 240
    private const val MAX_CONTROL_DATA_DIAGNOSTIC_BYTES = 64
}
