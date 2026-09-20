package com.rawsmusic.module.player.devicecontrol.usb.moondrop

import com.rawsmusic.module.player.devicecontrol.usb.EndpointPairTarget
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorControlInventory
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorTransport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

internal enum class MoondropUsbFamily {
    SPV,
    SYNAPTICS_CXAUDIO,
    JIU,
    COMTURE,
    BLUETRUM,
    JIELI,
    UNKNOWN,
}

internal data class MoondropEndpointPair(
    val target: EndpointPairTarget,
    val interfaceClass: Int,
    val transferTypes: Set<Int>,
    val appCommandEp4: Boolean,
)


internal data class MoondropSpvHidFingerprint(
    val interfaceNumber: Int,
    val commandReportBytes: Int,
    val otaReportBytes: Int?,
)

/**
 * Strong descriptor signature derived from the vendor transport and confirmed on a real
 * MOONDROP-class USB device. Report ID 0x4B is the command channel: the HID descriptor declares
 * 63 data bytes plus the report ID itself, i.e. one 64-byte interrupt report. 0x54 is the sibling
 * bidirectional report used by the same vendor-defined collection on current devices.
 *
 * This deliberately does not use VID/PID or the product string: Android commonly exposes these
 * dongles as the generic "USB Audio Device". A positive result is therefore a protocol-level
 * match, not a brand-name guess.
 */
internal object MoondropSpvHidIdentity {
    fun fingerprint(inventory: UsbVendorControlInventory): MoondropSpvHidFingerprint? {
        val matches = inventory.hidInterfaces.mapNotNull { iface ->
            if (iface.hidUsagePages.none { it == SPV_VENDOR_USAGE_PAGE }) return@mapNotNull null
            val command = iface.hidReports.singleOrNull { report ->
                report.reportId == MoondropSpvProtocol.MAGIC &&
                    report.inputBytes == SPV_REPORT_PAYLOAD_BYTES &&
                    report.outputBytes == SPV_REPORT_PAYLOAD_BYTES
            } ?: return@mapNotNull null
            val ota = iface.hidReports.singleOrNull { report ->
                report.reportId == SPV_OTA_REPORT_ID &&
                    report.inputBytes == SPV_REPORT_PAYLOAD_BYTES &&
                    report.outputBytes == SPV_REPORT_PAYLOAD_BYTES
            }
            val hasInterruptOut = iface.endpoints.any { ep ->
                !ep.isIn && ep.transferType == USB_TRANSFER_INTERRUPT && ep.maxPacketSize >= SPV_REPORT_BYTES
            }
            val hasInterruptIn = iface.endpoints.any { ep ->
                ep.isIn && ep.transferType == USB_TRANSFER_INTERRUPT && ep.maxPacketSize >= SPV_REPORT_BYTES
            }
            if (!hasInterruptOut || !hasInterruptIn) return@mapNotNull null
            MoondropSpvHidFingerprint(
                interfaceNumber = iface.interfaceNumber,
                commandReportBytes = command.outputBytes.toInt() + 1,
                otaReportBytes = ota?.outputBytes?.toInt()?.plus(1),
            )
        }
        return matches.singleOrNull()
    }

    private const val SPV_VENDOR_USAGE_PAGE = 0xff01L
    private const val SPV_OTA_REPORT_ID = 0x54
    private const val SPV_REPORT_PAYLOAD_BYTES = 63L
    private const val SPV_REPORT_BYTES = 64
    private const val USB_TRANSFER_INTERRUPT = 0x03
}

internal object MoondropUsbIdentity {
    private val exactOfficialAppNames = setOf(
        "MOONDROP BOLERO",
        "MOONDROP EVO 2",
        "MOONDROP MM3A",
        "MOONDROP MARIGOLD",
        "MOONDROP PILL",
        "MOONDROP RAYS",
        "MOONDROP X",
        "MOONDROP U.C.T.S.",
    )

    fun isOfficialNameCandidate(name: String): Boolean {
        val normalized = name.trim().uppercase()
        if (normalized.isBlank()) return false
        return normalized.contains("MOONDROP") || normalized.contains("水月雨") ||
            exactOfficialAppNames.contains(normalized)
    }
}

/**
 * Descriptor-only candidate selection. The official MOONDROP SPV stack searches a non-audio USB
 * command interface and contains a specific "app-command EP4" branch. Prefer that shape, but never
 * guess between multiple equally plausible interfaces.
 */
internal object MoondropEndpointSelector {
    fun candidates(inventory: UsbVendorControlInventory): List<MoondropEndpointPair> =
        (inventory.hidInterfaces + inventory.vendorInterfaces)
            .distinctBy { it.interfaceNumber to it.alternateSetting }
            .mapNotNull(::toPair)
            .sortedWith(
                compareByDescending<MoondropEndpointPair> { it.appCommandEp4 }
                    .thenByDescending { it.interfaceClass == USB_CLASS_HID }
                    .thenBy { it.target.interfaceNumber },
            )

    fun selectUnambiguous(inventory: UsbVendorControlInventory): MoondropEndpointPair? {
        val all = candidates(inventory)
        if (all.isEmpty()) return null
        val ep4 = all.filter { it.appCommandEp4 }
        if (ep4.size == 1) return ep4.single()
        if (ep4.size > 1) return null
        val hid = all.filter { it.interfaceClass == USB_CLASS_HID }
        if (hid.size == 1) return hid.single()
        if (hid.size > 1) return null
        return all.singleOrNull()
    }

    private fun toPair(iface: UsbVendorControlInventory.Interface): MoondropEndpointPair? {
        if (iface.interfaceClass != USB_CLASS_HID && iface.interfaceClass != USB_CLASS_VENDOR) return null
        val supported = iface.endpoints.filter { it.transferType == USB_TRANSFER_BULK || it.transferType == USB_TRANSFER_INTERRUPT }
        val outs = supported.filterNot { it.isIn }
        val ins = supported.filter { it.isIn }
        if (outs.size != 1 || ins.size != 1) return null
        val out = outs.single()
        val input = ins.single()
        return MoondropEndpointPair(
            target = EndpointPairTarget(
                interfaceNumber = iface.interfaceNumber,
                outEndpointAddress = out.address,
                inEndpointAddress = input.address,
                turnaroundDelayMs = 10,
                timeoutMs = 500,
            ),
            interfaceClass = iface.interfaceClass,
            transferTypes = setOf(out.transferType, input.transferType),
            appCommandEp4 = endpointNumber(out.address) == 4 && endpointNumber(input.address) == 4,
        )
    }

    private fun endpointNumber(address: Int): Int = address and 0x0f

    private const val USB_CLASS_HID = 0x03
    private const val USB_CLASS_VENDOR = 0xff
    private const val USB_TRANSFER_BULK = 0x02
    private const val USB_TRANSFER_INTERRUPT = 0x03
}

internal data class SpvFrame(
    val command: Int,
    val payload: ByteArray,
    val raw: ByteArray,
)

internal data class SpvPeqPoint(
    val frequencyHz: Double,
    val q: Double,
    val gainDb: Double,
    val filterType: Int,
    /** Full SPV replies carry five little-endian Q30 words: b0,b1,b2,-a1,-a2. */
    val coefficientWords: IntArray? = null,
) {
    fun hasOfficialPeakingCoefficients(): Boolean {
        if (filterType != MoondropSpvProtocol.FILTER_PEAKING) return false
        val actual = coefficientWords ?: return false
        val expected = MoondropSpvProtocol.peakingCoefficientWords(frequencyHz, gainDb, q)
        // The official Android native calculator and our Kotlin port both quantize to signed Q30.
        // Allow one LSB per coefficient for harmless libm/ISA rounding differences while still
        // requiring the exact five-word layout and PEAKING filter identity.
        return actual.size == expected.size && actual.indices.all { index ->
            abs(actual[index].toLong() - expected[index].toLong()) <= 1L
        }
    }
}

/** Exact read frame derived from the vendor app's Lxg4 command builder/parser. */
internal object MoondropSpvProtocol {
    const val MAGIC = 0x4b
    const val REPORT_BYTES = 64
    const val READ_SUBCOMMAND = 0x80
    const val WRITE_SUBCOMMAND = 0x01

    const val COMMAND_SAVE_PEQ = 0x01
    const val COMMAND_ADC_VOLUME = 0x02
    const val COMMAND_DAC_GAIN = 0x03
    const val COMMAND_SAVE_DAC_ADC = 0x04
    const val COMMAND_PEQ = 0x09
    const val COMMAND_UPDATE_EQ_TO_REG = 0x0a
    const val COMMAND_FIRMWARE = 0x0c
    const val COMMAND_SELECTED_EQ = 0x0f
    const val COMMAND_CS43131_FILTER = 0x11
    const val COMMAND_CS43131_WORKING_MODE = 0x1d
    const val COMMAND_PRE_GAIN = 0x23
    const val COMMAND_ANC = 0x25
    const val COMMAND_LED = 0x28
    const val COMMAND_SENSOR = 0x29
    const val COMMAND_SPATIAL_AUDIO = 0x2a
    const val COMMAND_PROMPT_TONE = 0x2b
    const val COMMAND_SKU_SN = 0x2c
    const val COMMAND_INPUT_SOURCE = 0x2d
    const val COMMAND_POWER_TIMEOUT = 0x2e

    const val FILTER_DISABLED = 0
    const val FILTER_LOW_SHELF = 1
    const val FILTER_PEAKING = 2
    const val FILTER_HIGH_SHELF = 3
    const val FILTER_LOW_PASS = 4
    const val FILTER_HIGH_PASS = 5

    private const val DSP_SAMPLE_RATE_HZ = 96_000.0
    private const val Q30_SCALE = 1_073_741_824.0

    fun readRequest(command: Int): ByteArray = wireReport(byteArrayOf(
        MAGIC.toByte(),
        READ_SUBCOMMAND.toByte(),
        command.toByte(),
        0x00,
    ))

    /** Official xg4.d(1, command) + xg4.b(buffer). */
    fun writeRequest(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= REPORT_BYTES - 4)
        val commandBytes = ByteArray(4 + payload.size)
        commandBytes[0] = MAGIC.toByte()
        commandBytes[1] = WRITE_SUBCOMMAND.toByte()
        commandBytes[2] = command.toByte()
        commandBytes[3] = payload.size.toByte()
        payload.copyInto(commandBytes, destinationOffset = 4)
        return wireReport(commandBytes)
    }

    /** Official xg4.r: Math.round(value * 256), then the low signed-16 bits little-endian. */
    fun q8_8Bytes(value: Double): ByteArray {
        require(value.isFinite() && value in -128.0..127.99609375)
        val raw = (value * 256.0).roundToLong().toInt().toShort().toInt() and 0xffff
        return byteArrayOf((raw and 0xff).toByte(), ((raw ushr 8) and 0xff).toByte())
    }

    fun scalarQ8_8WriteRequest(command: Int, value: Double): ByteArray =
        writeRequest(command, q8_8Bytes(value))

    fun selectedEqWriteRequest(index: Int): ByteArray {
        require(index in 0..255)
        return writeRequest(COMMAND_SELECTED_EQ, byteArrayOf(index.toByte()))
    }

    fun updateEqToRegsRequest(bandCount: Int): ByteArray {
        val bounded = bandCount.takeIf { it > 0 }?.coerceAtMost(32) ?: 8
        val maskBands = bounded.coerceAtMost(8)
        val mask = if (maskBands >= 8) 0xff else ((1 shl maskBands) - 1)
        return writeRequest(COMMAND_UPDATE_EQ_TO_REG, byteArrayOf(0x00, 0x00, mask.toByte(), mask.toByte()))
    }

    fun savePeqRequest(): ByteArray = writeRequest(COMMAND_SAVE_PEQ)
    fun saveDacAdcRequest(): ByteArray = writeRequest(COMMAND_SAVE_DAC_ADC)

    /**
     * Official Lyz4.s full payload used when omitCoeff=false and webPayload=false. Current
     * 35d8:011c readback uses PEAKING (2), so RawSMusic enables this writer only after the
     * coefficients read from the device match this exact official 96 kHz Q30 calculator.
     */
    fun peqFullWriteRequest(bandIndex: Int, point: SpvPeqPoint): ByteArray {
        require(bandIndex in 0..31)
        require(point.frequencyHz.toInt() in 20..20_000)
        require(point.q.isFinite() && point.q > 0.0 && point.q <= 127.99609375)
        require(point.gainDb.isFinite() && point.gainDb in -128.0..127.99609375)
        require(point.filterType == FILTER_PEAKING)
        val payload = ByteArray(33)
        payload[0] = 0x00 // absolute byte 4: eqIndex
        payload[1] = bandIndex.toByte() // absolute byte 5
        val coeffs = peakingCoefficientWords(point.frequencyHz, point.gainDb, point.q)
        coeffs.forEachIndexed { index, word -> writeInt32Le(payload, 4 + index * 4, word) } // abs 8..27
        val freq = point.frequencyHz.toInt()
        payload[24] = (freq and 0xff).toByte() // abs 28
        payload[25] = ((freq ushr 8) and 0xff).toByte()
        q8_8Bytes(point.q).copyInto(payload, destinationOffset = 26) // abs 30
        q8_8Bytes(point.gainDb).copyInto(payload, destinationOffset = 28) // abs 32
        payload[30] = point.filterType.toByte() // abs 34
        payload[32] = 0x07 // abs 36, official native-coefficient path marker
        return writeRequest(COMMAND_PEQ, payload)
    }

    /** Official native calculator output order: b0,b1,b2,-a1,-a2, signed Q30 LE. */
    fun peakingCoefficientWords(frequencyHz: Double, gainDb: Double, q: Double): IntArray {
        require(frequencyHz.isFinite() && frequencyHz in 20.0..20_000.0)
        require(gainDb.isFinite())
        require(q.isFinite() && q > 0.0)
        val omega = 2.0 * PI * frequencyHz / DSP_SAMPLE_RATE_HZ
        val cosine = cos(omega)
        val alpha = sin(omega) / (2.0 * q)
        val a = sqrt(10.0.pow(gainDb / 20.0))
        val a0 = 1.0 + alpha / a
        val a1 = (-2.0 * cosine) / a0
        val a2 = (1.0 - alpha / a) / a0
        val b0 = (1.0 + alpha * a) / a0
        val b1 = (-2.0 * cosine) / a0
        val b2 = (1.0 - alpha * a) / a0
        return intArrayOf(
            q30(b0), q30(b1), q30(b2), q30(-a1), q30(-a2),
        )
    }

    private fun q30(value: Double): Int {
        val scaled = value * Q30_SCALE
        // The official ARM64 path uses FRINTA/FCVTAS semantics: nearest integer with exact
        // half-way cases rounded away from zero. Kotlin roundToLong() is not identical for
        // negative half ties, so keep the narrowing deterministic and byte-for-byte compatible.
        val rounded = if (scaled >= 0.0) floor(scaled + 0.5) else ceil(scaled - 0.5)
        return rounded.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun writeInt32Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xff).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xff).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xff).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun readInt32Le(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xff) or
            ((src[offset + 1].toInt() and 0xff) shl 8) or
            ((src[offset + 2].toInt() and 0xff) shl 16) or
            (src[offset + 3].toInt() shl 24)

    suspend fun writePacket(
        transport: UsbVendorTransport,
        target: EndpointPairTarget,
        request: ByteArray,
        retries: Int = 3,
    ): Result<Int> {
        require(request.size == REPORT_BYTES)
        var lastFailure: Throwable? = null
        repeat(retries.coerceAtLeast(1)) { attempt ->
            val result = transport.endpointWrite(target, request)
            if (result.isSuccess && result.getOrNull() == request.size) return result
            lastFailure = result.exceptionOrNull() ?: IllegalStateException(
                "spv_short_write_${result.getOrNull() ?: -1}",
            )
            if (attempt + 1 < retries) delay(50)
        }
        return Result.failure(lastFailure ?: IllegalStateException("spv_endpoint_write_failed"))
    }

    fun wireReport(command: ByteArray): ByteArray {
        require(command.isNotEmpty() && command.size <= REPORT_BYTES)
        require(command[0].toInt() and 0xff == MAGIC)
        return command.copyOf(REPORT_BYTES)
    }

    suspend fun read(
        transport: UsbVendorTransport,
        target: EndpointPairTarget,
        command: Int,
        responseLength: Int = REPORT_BYTES,
    ): Result<SpvFrame> = transport.endpointExchange(target, readRequest(command), responseLength)
        .mapCatching { raw ->
            parseResponse(command, raw) ?: error(invalidResponseMessage(command, raw))
        }

    /**
     * Mirrors the official `Lxg4.n()` response parser instruction-for-instruction:
     *  - require at least four bytes;
     *  - validate only byte[2] against the requested command;
     *  - byte[3] is an optional payload length;
     *  - when byte[3] is zero, use every received byte after offset four.
     *
     * The official parser deliberately does not validate bytes 0/1. That matters on real HID
     * devices because the Android/USB layer may preserve, replace, or strip report framing while
     * still returning the command at byte 2. A one-byte-stripped [80|01,cmd,len,...] shape is also
     * accepted here because RawSMusic talks directly to libusb rather than through MOONDROP's
     * Android UsbMessageTransmitter.
     */
    fun parseResponse(command: Int, raw: ByteArray): SpvFrame? {
        val framed = responseForCommand(command, raw) ?: return null
        val declaredLength = framed[3].toInt() and 0xff
        val endExclusive = if (declaredLength > 0) {
            minOf(framed.size, 4 + declaredLength)
        } else {
            framed.size
        }
        val payload = if (endExclusive > 4) {
            framed.copyOfRange(4, endExclusive)
        } else {
            ByteArray(0)
        }
        return SpvFrame(command, payload, framed)
    }

    internal fun responseForCommand(command: Int, raw: ByteArray): ByteArray? {
        if (raw.size >= 4 && (raw[2].toInt() and 0xff) == command) return raw
        if (raw.size >= 3) {
            val first = raw[0].toInt() and 0xff
            if ((first == READ_SUBCOMMAND || first == WRITE_SUBCOMMAND) &&
                (raw[1].toInt() and 0xff) == command
            ) {
                val framed = byteArrayOf(MAGIC.toByte()) + raw
                if (framed.size >= 4 && (framed[2].toInt() and 0xff) == command) return framed
            }
        }
        return null
    }

    private fun invalidResponseMessage(command: Int, raw: ByteArray): String = buildString {
        append("spv_invalid_response_0x")
        append(command.toString(16))
        append("_len=")
        append(raw.size)
        append("_head=")
        append(raw.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    fun firstUnsignedByte(frame: SpvFrame): Int? = frame.payload.firstOrNull()?.toInt()?.and(0xff)

    /** Official xg4.i: signed little-endian 16-bit value divided by 256.0. */
    fun q8_8(frame: SpvFrame): Double? {
        if (frame.payload.size < 2) return null
        val raw = ((frame.payload[1].toInt() and 0xff) shl 8) or (frame.payload[0].toInt() and 0xff)
        return raw.toShort().toInt() / 256.0
    }

    fun peqReadRequest(bandIndex: Int, fullHeader: Boolean): ByteArray {
        require(bandIndex in 0..31)
        val command = if (fullHeader) {
            // Official xg4.c(index, true): [4B,80,09,04,00,index,00,00]
            byteArrayOf(MAGIC.toByte(), READ_SUBCOMMAND.toByte(), COMMAND_PEQ.toByte(), 0x04,
                0x00, bandIndex.toByte(), 0x00, 0x00)
        } else {
            // Official xg4.c(index, false): [4B,80,09,02,00,index]
            byteArrayOf(MAGIC.toByte(), READ_SUBCOMMAND.toByte(), COMMAND_PEQ.toByte(), 0x02,
                0x00, bandIndex.toByte())
        }
        return wireReport(command)
    }

    suspend fun readPeqPoint(
        transport: UsbVendorTransport,
        target: EndpointPairTarget,
        bandIndex: Int,
    ): Result<SpvPeqPoint> = runCatching {
        require(bandIndex in 0..31)
        // Prefer the official full form so the live coefficient block can validate the writer.
        // Older SPV generations may only answer the legacy short form, which remains supported.
        val full = transport.endpointExchange(target, peqReadRequest(bandIndex, true), REPORT_BYTES).getOrNull()
        val fullPoint = full?.let(::parsePeqResponse)
        if (fullPoint != null) return@runCatching fullPoint
        val legacy = transport.endpointExchange(target, peqReadRequest(bandIndex, false), REPORT_BYTES).getOrThrow()
        parsePeqResponse(legacy) ?: error("spv_invalid_peq_response_band_$bandIndex")
    }

    /**
     * Official xg4.o tries the point decoder at offsets 28 and 8 and rejects ambiguous data.
     * xg4.v decodes seven bytes as LE uint16 frequency, unsigned Q8.8 Q, signed Q8.8 gain,
     * and an 8-bit filter type, then validates the frequency/filter domain.
     */
    fun parsePeqResponse(raw: ByteArray): SpvPeqPoint? {
        val framed = responseForCommand(COMMAND_PEQ, raw) ?: return null
        val candidates = listOf(28, 8).mapNotNull { offset ->
            parsePeqPointAt(framed, offset)?.let { point ->
                if (offset == 28 && framed.size >= 28) {
                    point.copy(coefficientWords = IntArray(5) { index -> readInt32Le(framed, 8 + index * 4) })
                } else {
                    point
                }
            }
        }
        return candidates.singleOrNull()
    }

    private fun parsePeqPointAt(raw: ByteArray, offset: Int): SpvPeqPoint? {
        if (offset < 0 || offset + 7 > raw.size) return null
        val frequency = (raw[offset].toInt() and 0xff) or ((raw[offset + 1].toInt() and 0xff) shl 8)
        if (frequency !in 20..20_000) return null
        val q = (raw[offset + 3].toInt() and 0xff) + ((raw[offset + 2].toInt() and 0xff) / 256.0)
        if (!q.isFinite() || q <= 0.0 || q > 255.9961) return null
        val gain = raw[offset + 5].toInt() + ((raw[offset + 4].toInt() and 0xff) / 256.0)
        if (!gain.isFinite() || gain !in -128.0..127.9961) return null
        val filter = raw[offset + 6].toInt() and 0xff
        if (filter !in 0..7) return null
        return SpvPeqPoint(frequency.toDouble(), q, gain, filter)
    }

    fun printableAsciiOrHex(payload: ByteArray, maxBytes: Int = 32): String {
        val bytes = payload.take(maxBytes)
        val ascii = bytes.takeWhile { b ->
            val v = b.toInt() and 0xff
            v == 0 || v in 0x20..0x7e
        }.takeWhile { it.toInt() != 0 }.map { it.toInt().toChar() }.joinToString("")
        if (ascii.length >= 2) return ascii
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
