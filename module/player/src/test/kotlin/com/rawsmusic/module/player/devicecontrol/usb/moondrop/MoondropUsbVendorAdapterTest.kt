package com.rawsmusic.module.player.devicecontrol.usb.moondrop

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlId
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteValue
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.usb.BulkTarget
import com.rawsmusic.module.player.devicecontrol.usb.EndpointPairTarget
import com.rawsmusic.module.player.devicecontrol.usb.ExtensionUnitTarget
import com.rawsmusic.module.player.devicecontrol.usb.HidReportTarget
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorAdapterContext
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorAdapterProbeResult
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorControlInventory
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorTransport
import com.rawsmusic.module.player.devicecontrol.usb.VendorControlTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoondropUsbVendorAdapterTest {
    @Test
    fun `SPV response and Q8 8 decoder match official framing`() {
        val raw = frame(0x23, byteArrayOf(0x80.toByte(), 0xfd.toByte()))
        val parsed = assertNotNull(MoondropSpvProtocol.parseResponse(0x23, raw))
        assertEquals(-2.5, MoondropSpvProtocol.q8_8(parsed))
        assertNull(MoondropSpvProtocol.parseResponse(0x25, raw))
    }


    @Test
    fun `SPV response parser mirrors official xg4 n zero-length semantics`() {
        val raw = ByteArray(64).also { frame ->
            frame[0] = 0x4b
            frame[1] = 0x80.toByte()
            frame[2] = 0x0c
            frame[3] = 0x00 // Official xg4.n: zero means consume the remaining report.
            "1.2.3".encodeToByteArray().copyInto(frame, destinationOffset = 4)
        }
        val parsed = assertNotNull(MoondropSpvProtocol.parseResponse(0x0c, raw))
        assertEquals(60, parsed.payload.size)
        assertEquals("1.2.3", MoondropSpvProtocol.printableAsciiOrHex(parsed.payload))
    }

    @Test
    fun `SPV response parser validates command not report prefix`() {
        val officialShape = byteArrayOf(0x55, 0x66, 0x23, 0x02, 0x80.toByte(), 0xfd.toByte())
        val parsed = assertNotNull(MoondropSpvProtocol.parseResponse(0x23, officialShape))
        assertEquals(-2.5, MoondropSpvProtocol.q8_8(parsed))

        val stripped = byteArrayOf(0x80.toByte(), 0x0c, 0x03, '1'.code.toByte(), '.'.code.toByte(), '0'.code.toByte())
        val strippedParsed = assertNotNull(MoondropSpvProtocol.parseResponse(0x0c, stripped))
        assertEquals("1.0", MoondropSpvProtocol.printableAsciiOrHex(strippedParsed.payload))
        assertNull(MoondropSpvProtocol.parseResponse(0x25, officialShape))
    }

    @Test
    fun `PEQ decoder accepts one official point layout and rejects ambiguity`() {
        val legacy = peqResponse(freq = 1000, q = 1.25, gain = -2.5, filter = 0, offset = 8)
        val point = assertNotNull(MoondropSpvProtocol.parsePeqResponse(legacy))
        assertEquals(1000.0, point.frequencyHz)
        assertEquals(1.25, point.q)
        assertEquals(-2.5, point.gainDb)
        assertEquals(0, point.filterType)

        val ambiguous = ByteArray(40).also { raw ->
            raw[0] = 0x4b
            raw[1] = 0x80.toByte()
            raw[2] = 0x09
            raw[3] = (raw.size - 4).toByte()
        }
        writePoint(ambiguous, 8, 1000, 1.0, 0.0, 0)
        writePoint(ambiguous, 28, 2000, 1.0, 0.0, 0)
        assertNull(MoondropSpvProtocol.parsePeqResponse(ambiguous))
    }

    @Test
    fun `only official Moondrop identity can activate adapter`() {
        val inventory = inventory("Generic USB DAC")
        val adapter = MoondropUsbVendorAdapter()
        assertEquals(0, adapter.match(inventory.device, inventory))
        val moon = inventory("MOONDROP Test DSP")
        assertTrue(adapter.match(moon.device, moon) > 0)
    }

    @Test
    fun `real SPV HID descriptor activates adapter even with generic USB product name`() {
        val inventory = spvInventoryFromRealDescriptor()
        val adapter = MoondropUsbVendorAdapter()
        val fingerprint = assertNotNull(MoondropSpvHidIdentity.fingerprint(inventory))
        assertEquals(3, fingerprint.interfaceNumber)
        assertEquals(64, fingerprint.commandReportBytes)
        assertEquals(64, fingerprint.otaReportBytes)
        assertTrue(adapter.match(inventory.device, inventory) >= 700)
    }

    @Test
    fun `consumer control only HID never activates Moondrop adapter`() {
        val inventory = consumerOnlyInventory()
        val adapter = MoondropUsbVendorAdapter()
        assertNull(MoondropSpvHidIdentity.fingerprint(inventory))
        assertEquals(0, adapter.match(inventory.device, inventory))
    }

    @Test
    fun `SPV requests use one complete 64 byte HID report`() {
        val request = MoondropSpvProtocol.readRequest(MoondropSpvProtocol.COMMAND_FIRMWARE)
        assertEquals(64, request.size)
        assertEquals(0x4b, request[0].toInt() and 0xff)
        assertEquals(0x80, request[1].toInt() and 0xff)
        assertEquals(0x0c, request[2].toInt() and 0xff)
        assertEquals(0, request[3].toInt() and 0xff)
        assertTrue(request.drop(4).all { it == 0.toByte() })

        val peq = MoondropSpvProtocol.peqReadRequest(7, fullHeader = true)
        assertEquals(64, peq.size)
        assertEquals(listOf(0x4b, 0x80, 0x09, 0x04, 0x00, 0x07, 0x00, 0x00),
            peq.take(8).map { it.toInt() and 0xff })
    }

    @Test
    fun `SPV official write packets preserve wire layout without flash commit`() {
        val pre = MoondropSpvProtocol.scalarQ8_8WriteRequest(MoondropSpvProtocol.COMMAND_PRE_GAIN, -1.5)
        assertEquals(64, pre.size)
        assertEquals(listOf(0x4b, 0x01, 0x23, 0x02, 0x80, 0xfe), pre.take(6).map { it.toInt() and 0xff })

        val update = MoondropSpvProtocol.updateEqToRegsRequest(8)
        assertEquals(listOf(0x4b, 0x01, 0x0a, 0x04, 0x00, 0x00, 0xff, 0xff),
            update.take(8).map { it.toInt() and 0xff })

        val point = SpvPeqPoint(1000.0, 0.70703125, -2.5, MoondropSpvProtocol.FILTER_PEAKING)
        val peq = MoondropSpvProtocol.peqFullWriteRequest(3, point)
        assertEquals(64, peq.size)
        assertEquals(0x4b, peq[0].toInt() and 0xff)
        assertEquals(0x01, peq[1].toInt() and 0xff)
        assertEquals(0x09, peq[2].toInt() and 0xff)
        assertEquals(33, peq[3].toInt() and 0xff)
        assertEquals(0, peq[4].toInt() and 0xff)
        assertEquals(3, peq[5].toInt() and 0xff)
        assertEquals(1000, u16le(peq, 28))
        assertEquals(0.70703125, q88At(peq, 30))
        assertEquals(-2.5, q88At(peq, 32))
        assertEquals(MoondropSpvProtocol.FILTER_PEAKING, peq[34].toInt() and 0xff)
        assertEquals(7, peq[36].toInt() and 0xff)
    }

    @Test
    fun `full SPV coefficient readback authorizes PEQ and volatile pre gain writes`() = runBlocking {
        val inventory = spvInventoryFromRealDescriptor()
        val transport = StatefulSpvTransport()
        val context = UsbVendorAdapterContext(inventory.device, 9L, inventory, transport)
        val adapter = MoondropUsbVendorAdapter()
        val ready = assertIs<UsbVendorAdapterProbeResult.Ready>(adapter.probe(context))
        val peq = ready.capabilities.filterIsInstance<DeviceControlCapability.ParametricEq>().single()
        assertEquals(8, peq.bands.size)
        assertTrue(peq.bands.all { it.gain?.access == DeviceControlAccess.READ_WRITE })
        assertTrue(ready.diagnostics.any { it == "usb.moondrop.spv peq_full_coeff_verified=8/8" })
        val pre = ready.capabilities.filterIsInstance<DeviceControlCapability.Range>()
            .single { it.id.value == "usb:moondrop:spv:pre_gain" }
        assertEquals(DeviceControlAccess.READ_WRITE, pre.control.access)

        var snapshot = DeviceControlSnapshot(
            generation = 9L,
            device = inventory.device,
            capabilities = ready.capabilities,
            probeState = DeviceControlSnapshot.ProbeState.READY,
        )
        val preResult = assertIs<DeviceControlWriteResult.Applied>(
            adapter.write(
                context,
                DeviceControlWriteRequest(
                    DeviceControlId("usb:moondrop:spv:pre_gain"),
                    DeviceControlWriteValue.Number(-2.5),
                ),
                snapshot,
            ),
        )
        snapshot = preResult.snapshot
        assertEquals(-2.5, transport.preGain)
        assertTrue(transport.writes.any { (it[2].toInt() and 0xff) == MoondropSpvProtocol.COMMAND_PRE_GAIN })
        assertFalse(transport.writes.any { (it[2].toInt() and 0xff) == MoondropSpvProtocol.COMMAND_SAVE_DAC_ADC })

        val gainId = DeviceControlId("usb:moondrop:spv:peq:band:0:gain")
        val peqResult = assertIs<DeviceControlWriteResult.Applied>(
            adapter.write(context, DeviceControlWriteRequest(gainId, DeviceControlWriteValue.Number(-1.5)), snapshot),
        )
        assertEquals(-1.5, transport.bands[0].gainDb)
        assertTrue(transport.writes.any { (it[2].toInt() and 0xff) == MoondropSpvProtocol.COMMAND_PEQ })
        assertTrue(transport.writes.any { (it[2].toInt() and 0xff) == MoondropSpvProtocol.COMMAND_UPDATE_EQ_TO_REG })
        assertFalse(transport.writes.any { (it[2].toInt() and 0xff) == MoondropSpvProtocol.COMMAND_SAVE_PEQ })
        val newPeq = peqResult.snapshot.capabilities.filterIsInstance<DeviceControlCapability.ParametricEq>().single()
        assertEquals(-1.5, newPeq.bands[0].gain?.current)
    }

    @Test
    fun `runtime readback produces only capabilities actually returned by device`() = runBlocking {
        val inventory = inventory("MOONDROP SPV Fixture")
        val transport = FakeTransport { request ->
            val command = request.getOrNull(2)?.toInt()?.and(0xff) ?: return@FakeTransport null
            when (command) {
                0x0c -> frame(0x0c, "1.2.3".encodeToByteArray())
                0x09 -> {
                    val band = request.getOrNull(5)?.toInt()?.and(0xff) ?: return@FakeTransport null
                    if (band !in 0..7) null else peqResponse(
                        freq = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)[band],
                        q = 1.0,
                        gain = band - 3.0,
                        filter = 0,
                        offset = 8,
                    )
                }
                0x02 -> frame(0x02, q88(1.5))
                0x03 -> frame(0x03, q88(-2.0))
                0x0f -> frame(0x0f, byteArrayOf(3))
                0x11 -> frame(0x11, byteArrayOf(2))
                0x1d -> null
                0x23 -> frame(0x23, q88(-1.25))
                0x25 -> null
                0x28 -> frame(0x28, byteArrayOf(1))
                0x29 -> null
                0x2a -> frame(0x2a, byteArrayOf(1, 0))
                0x2b -> null
                0x2d -> null
                else -> null
            }
        }
        val result = MoondropUsbVendorAdapter().probe(
            UsbVendorAdapterContext(inventory.device, 1L, inventory, transport),
        )
        val ready = assertIs<UsbVendorAdapterProbeResult.Ready>(result)
        val peq = ready.capabilities.filterIsInstance<DeviceControlCapability.ParametricEq>().single()
        assertEquals(8, peq.bands.size)
        assertTrue(ready.capabilities.any { it.label == "ADC 音量" })
        assertTrue(ready.capabilities.any { it.label == "DAC 增益" })
        assertTrue(ready.capabilities.any { it.label == "前级增益" })
        assertTrue(ready.capabilities.any { it.label == "指示灯" })
        assertFalse(ready.capabilities.any { it.label == "传感器" })
        assertTrue(ready.diagnostics.any { it == "usb.moondrop.spv peq_bands_detected=8" })
        assertTrue(ready.diagnostics.any { it.contains("feature=spatial_audio supported=true") })
    }

    @Test
    fun `dynamic SPV PEQ does not truncate a 32 band device`() = runBlocking {
        val inventory = inventory("MOONDROP SPV 32Band")
        val transport = FakeTransport { request ->
            when (request.getOrNull(2)?.toInt()?.and(0xff)) {
                0x0c -> frame(0x0c, byteArrayOf(1))
                0x09 -> {
                    val band = request.getOrNull(5)?.toInt()?.and(0xff) ?: return@FakeTransport null
                    if (band !in 0..31) null else peqResponse(
                        freq = 20 + band * 600,
                        q = 1.0,
                        gain = 0.0,
                        filter = band % 8,
                        offset = 8,
                    )
                }
                else -> null
            }
        }
        val ready = assertIs<UsbVendorAdapterProbeResult.Ready>(
            MoondropUsbVendorAdapter().probe(UsbVendorAdapterContext(inventory.device, 3L, inventory, transport)),
        )
        val peq = ready.capabilities.filterIsInstance<DeviceControlCapability.ParametricEq>().single()
        assertEquals(32, peq.bands.size)
    }

    @Test
    fun `different SPV readback yields a different visible capability surface`() = runBlocking {
        val inventory = inventory("MOONDROP SPV Minimal")
        val transport = FakeTransport { request ->
            when (request.getOrNull(2)?.toInt()?.and(0xff)) {
                0x0c -> frame(0x0c, byteArrayOf(1))
                0x28 -> frame(0x28, byteArrayOf(0))
                0x29 -> frame(0x29, byteArrayOf(1))
                else -> null
            }
        }
        val ready = assertIs<UsbVendorAdapterProbeResult.Ready>(
            MoondropUsbVendorAdapter().probe(UsbVendorAdapterContext(inventory.device, 2L, inventory, transport)),
        )
        assertEquals(setOf("指示灯", "传感器"), ready.capabilities.map { it.label }.toSet())
        assertTrue(ready.capabilities.none { it is DeviceControlCapability.ParametricEq })
    }

    private fun inventory(name: String): UsbVendorControlInventory {
        val device = DeviceControlDevice(
            stableId = "usb:1234:5678",
            displayName = name,
            connectionKind = DeviceConnectionKind.USB,
            vendorId = 0x1234,
            productId = 0x5678,
        )
        return UsbVendorControlInventory(
            device = device,
            extensionUnits = emptyList(),
            hidInterfaces = listOf(
                UsbVendorControlInventory.Interface(
                    interfaceNumber = 4,
                    alternateSetting = 0,
                    interfaceClass = 0x03,
                    interfaceSubClass = 0,
                    interfaceProtocol = 0,
                    hidReportDescriptorLength = 0,
                    hidReportDescriptorHex = "",
                    hidUsagePages = emptyList(),
                    hidReports = emptyList(),
                    endpoints = listOf(
                        UsbVendorControlInventory.Endpoint(0x04, 0x03, 64, 1),
                        UsbVendorControlInventory.Endpoint(0x84, 0x03, 64, 1),
                    ),
                ),
            ),
            vendorInterfaces = emptyList(),
        )
    }

    private fun spvInventoryFromRealDescriptor(): UsbVendorControlInventory {
        val device = DeviceControlDevice(
            stableId = "usb:35d8:011c",
            displayName = "USB Audio Device",
            connectionKind = DeviceConnectionKind.USB,
            vendorId = 0x35d8,
            productId = 0x011c,
        )
        return UsbVendorControlInventory(
            device = device,
            extensionUnits = emptyList(),
            hidInterfaces = listOf(
                UsbVendorControlInventory.Interface(
                    interfaceNumber = 3,
                    alternateSetting = 0,
                    interfaceClass = 0x03,
                    interfaceSubClass = 0,
                    interfaceProtocol = 0,
                    hidReportDescriptorLength = 74,
                    hidReportDescriptorHex = "050c0901a1018503150025037501950209e909ea81027501950209cd09cf81027501950481010601ff854b7508953f09018103953f0902910285547508953f09038103953f09049102c0",
                    hidUsagePages = listOf(0x0c, 0xff01),
                    hidReports = listOf(
                        UsbVendorControlInventory.HidReport(0x03, 2, 0, 0, 1, 0, 0),
                        UsbVendorControlInventory.HidReport(0x4b, 504, 504, 0, 63, 63, 0),
                        UsbVendorControlInventory.HidReport(0x54, 504, 504, 0, 63, 63, 0),
                    ),
                    endpoints = listOf(
                        UsbVendorControlInventory.Endpoint(0x86, 0x03, 64, 1),
                        UsbVendorControlInventory.Endpoint(0x05, 0x03, 64, 1),
                    ),
                ),
            ),
            vendorInterfaces = emptyList(),
        )
    }

    private fun consumerOnlyInventory(): UsbVendorControlInventory {
        val device = DeviceControlDevice(
            stableId = "usb:2fc6:f06a",
            displayName = "USB Audio Device",
            connectionKind = DeviceConnectionKind.USB,
            vendorId = 0x2fc6,
            productId = 0xf06a,
        )
        return UsbVendorControlInventory(
            device = device,
            extensionUnits = emptyList(),
            hidInterfaces = listOf(
                UsbVendorControlInventory.Interface(
                    interfaceNumber = 2,
                    alternateSetting = 0,
                    interfaceClass = 0x03,
                    interfaceSubClass = 0,
                    interfaceProtocol = 0,
                    hidReportDescriptorLength = 43,
                    hidReportDescriptorHex = "050c0901a101150025017501950709e909ea09e209b709cd09b509b68102953981037508950709009102c0",
                    hidUsagePages = listOf(0x0c),
                    hidReports = listOf(
                        UsbVendorControlInventory.HidReport(0x00, 64, 56, 0, 8, 7, 0),
                    ),
                    endpoints = listOf(
                        UsbVendorControlInventory.Endpoint(0x05, 0x03, 8, 1),
                        UsbVendorControlInventory.Endpoint(0x86, 0x03, 8, 1),
                    ),
                ),
            ),
            vendorInterfaces = emptyList(),
        )
    }

    private class StatefulSpvTransport : UsbVendorTransport {
        var preGain = 0.0
        val writes = mutableListOf<ByteArray>()
        val bands = mutableListOf(
            SpvPeqPoint(20.0, 0.6015625, -3.3984375, 2),
            SpvPeqPoint(60.0, 0.6015625, -3.0, 2),
            SpvPeqPoint(200.0, 0.70703125, 0.0, 2),
            SpvPeqPoint(600.0, 0.70703125, 1.6015625, 2),
            SpvPeqPoint(2500.0, 0.80078125, 0.3984375, 2),
            SpvPeqPoint(7500.0, 1.0, 3.30078125, 2),
            SpvPeqPoint(12000.0, 1.0, 3.19921875, 2),
            SpvPeqPoint(16000.0, 0.5, 3.0, 2),
        )

        override suspend fun endpointExchange(target: EndpointPairTarget, request: ByteArray, responseLength: Int): Result<ByteArray> {
            val command = request.getOrNull(2)?.toInt()?.and(0xff)
                ?: return Result.failure(IllegalStateException("short_request"))
            val response = when (command) {
                MoondropSpvProtocol.COMMAND_FIRMWARE -> frame(command, "0.1".encodeToByteArray())
                MoondropSpvProtocol.COMMAND_PEQ -> {
                    val band = request.getOrNull(5)?.toInt()?.and(0xff) ?: 255
                    bands.getOrNull(band)?.let(::fullPeqResponse)
                }
                MoondropSpvProtocol.COMMAND_PRE_GAIN -> frame(command, q88(preGain))
                else -> null
            }
            return response?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("unsupported"))
        }

        override suspend fun endpointWrite(target: EndpointPairTarget, request: ByteArray): Result<Int> {
            writes += request.copyOf()
            when (request.getOrNull(2)?.toInt()?.and(0xff)) {
                MoondropSpvProtocol.COMMAND_PRE_GAIN -> preGain = q88At(request, 4)
                MoondropSpvProtocol.COMMAND_PEQ -> {
                    val band = request[5].toInt() and 0xff
                    val current = bands.getOrNull(band) ?: return Result.failure(IllegalStateException("bad_band"))
                    bands[band] = current.copy(
                        frequencyHz = u16le(request, 28).toDouble(),
                        q = q88At(request, 30),
                        gainDb = q88At(request, 32),
                        filterType = request[34].toInt() and 0xff,
                        coefficientWords = null,
                    )
                }
            }
            return Result.success(request.size)
        }

        override suspend fun extensionUnitRead(target: ExtensionUnitTarget, length: Int) = unsupportedBytes()
        override suspend fun extensionUnitWrite(target: ExtensionUnitTarget, data: ByteArray) = unsupportedInt()
        override suspend fun hidGetReport(target: HidReportTarget, length: Int) = unsupportedBytes()
        override suspend fun hidSetReport(target: HidReportTarget, data: ByteArray) = unsupportedInt()
        override suspend fun vendorControlIn(target: VendorControlTarget, length: Int) = unsupportedBytes()
        override suspend fun vendorControlOut(target: VendorControlTarget, data: ByteArray) = unsupportedInt()
        override suspend fun bulkIn(target: BulkTarget, length: Int) = unsupportedBytes()
        override suspend fun bulkOut(target: BulkTarget, data: ByteArray) = unsupportedInt()
        private fun unsupportedBytes(): Result<ByteArray> = Result.failure(IllegalStateException("unsupported"))
        private fun unsupportedInt(): Result<Int> = Result.failure(IllegalStateException("unsupported"))
    }

    private class FakeTransport(
        val exchange: (ByteArray) -> ByteArray?,
    ) : UsbVendorTransport {
        override suspend fun endpointExchange(target: EndpointPairTarget, request: ByteArray, responseLength: Int) =
            exchange(request)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("unsupported"))
        override suspend fun endpointWrite(target: EndpointPairTarget, request: ByteArray) = unsupportedInt()
        override suspend fun extensionUnitRead(target: ExtensionUnitTarget, length: Int) = unsupportedBytes()
        override suspend fun extensionUnitWrite(target: ExtensionUnitTarget, data: ByteArray) = unsupportedInt()
        override suspend fun hidGetReport(target: HidReportTarget, length: Int) = unsupportedBytes()
        override suspend fun hidSetReport(target: HidReportTarget, data: ByteArray) = unsupportedInt()
        override suspend fun vendorControlIn(target: VendorControlTarget, length: Int) = unsupportedBytes()
        override suspend fun vendorControlOut(target: VendorControlTarget, data: ByteArray) = unsupportedInt()
        override suspend fun bulkIn(target: BulkTarget, length: Int) = unsupportedBytes()
        override suspend fun bulkOut(target: BulkTarget, data: ByteArray) = unsupportedInt()
        private fun unsupportedBytes(): Result<ByteArray> = Result.failure(IllegalStateException("unsupported"))
        private fun unsupportedInt(): Result<Int> = Result.failure(IllegalStateException("unsupported"))
    }

    companion object {
        private fun frame(command: Int, payload: ByteArray): ByteArray =
            byteArrayOf(0x4b, 0x80.toByte(), command.toByte(), payload.size.toByte()) + payload

        private fun q88(value: Double): ByteArray {
            val raw = (value * 256.0).toInt().toShort().toInt() and 0xffff
            return byteArrayOf((raw and 0xff).toByte(), ((raw ushr 8) and 0xff).toByte())
        }

        private fun u16le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun q88At(bytes: ByteArray, offset: Int): Double {
            val raw = u16le(bytes, offset).toShort().toInt()
            return raw / 256.0
        }

        private fun fullPeqResponse(point: SpvPeqPoint): ByteArray = ByteArray(64).also { raw ->
            raw[0] = 0x4b
            raw[1] = 0x80.toByte()
            raw[2] = 0x09
            raw[3] = 0 // Official zero-length fixed report semantics.
            MoondropSpvProtocol.peakingCoefficientWords(point.frequencyHz, point.gainDb, point.q)
                .forEachIndexed { index, word -> writeInt32(raw, 8 + index * 4, word) }
            writePoint(raw, 28, point.frequencyHz.toInt(), point.q, point.gainDb, point.filterType)
        }

        private fun writeInt32(raw: ByteArray, offset: Int, value: Int) {
            raw[offset] = (value and 0xff).toByte()
            raw[offset + 1] = ((value ushr 8) and 0xff).toByte()
            raw[offset + 2] = ((value ushr 16) and 0xff).toByte()
            raw[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }

        private fun peqResponse(freq: Int, q: Double, gain: Double, filter: Int, offset: Int): ByteArray =
            ByteArray(offset + 7).also { raw ->
                raw[0] = 0x4b
                raw[1] = 0x80.toByte()
                raw[2] = 0x09
                raw[3] = (raw.size - 4).toByte()
                writePoint(raw, offset, freq, q, gain, filter)
            }

        private fun writePoint(raw: ByteArray, offset: Int, freq: Int, q: Double, gain: Double, filter: Int) {
            raw[offset] = (freq and 0xff).toByte()
            raw[offset + 1] = ((freq ushr 8) and 0xff).toByte()
            val qRaw = (q * 256.0).toInt().coerceIn(1, 0x7fff)
            raw[offset + 2] = (qRaw and 0xff).toByte()
            raw[offset + 3] = ((qRaw ushr 8) and 0xff).toByte()
            val gainRaw = (gain * 256.0).toInt().toShort().toInt() and 0xffff
            raw[offset + 4] = (gainRaw and 0xff).toByte()
            raw[offset + 5] = ((gainRaw ushr 8) and 0xff).toByte()
            raw[offset + 6] = filter.toByte()
        }
    }
}
