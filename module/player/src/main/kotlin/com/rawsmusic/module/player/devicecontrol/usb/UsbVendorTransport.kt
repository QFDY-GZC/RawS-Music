package com.rawsmusic.module.player.devicecontrol.usb

import com.rawsmusic.module.player.usb.UsbAudioEngine

/**
 * Bounded raw transport only exposed after a concrete vendor adapter has matched the device.
 * Every call is validated again by native code against the live descriptor inventory.
 */
interface UsbVendorTransport {
    suspend fun extensionUnitRead(target: ExtensionUnitTarget, length: Int): Result<ByteArray>
    suspend fun extensionUnitWrite(target: ExtensionUnitTarget, data: ByteArray): Result<Int>
    suspend fun hidGetReport(target: HidReportTarget, length: Int): Result<ByteArray>
    suspend fun hidSetReport(target: HidReportTarget, data: ByteArray): Result<Int>
    suspend fun vendorControlIn(target: VendorControlTarget, length: Int): Result<ByteArray>
    suspend fun vendorControlOut(target: VendorControlTarget, data: ByteArray): Result<Int>
    suspend fun bulkIn(target: BulkTarget, length: Int): Result<ByteArray>
    suspend fun bulkOut(target: BulkTarget, data: ByteArray): Result<Int>

    /**
     * One bounded request/response exchange on a non-audio HID/vendor interface. Native code
     * validates both endpoints against the same live interface, claims it once, sends the OUT
     * request, reads the IN response, and releases exactly that temporary claim. This is needed
     * by devices such as MOONDROP SPV whose command channel uses interrupt/bulk endpoints rather
     * than HID SET_REPORT/GET_REPORT control transfers.
     */
    suspend fun endpointExchange(
        target: EndpointPairTarget,
        request: ByteArray,
        responseLength: Int,
    ): Result<ByteArray>

    /** Bounded OUT-only companion for vendor protocols whose SET path has no response frame. */
    suspend fun endpointWrite(
        target: EndpointPairTarget,
        request: ByteArray,
    ): Result<Int>
}

data class ExtensionUnitTarget(
    val interfaceNumber: Int,
    val entityId: Int,
    val selector: Int,
    val channel: Int = 0,
    val timeoutMs: Int = 350,
)

enum class HidReportType(val wireValue: Int) {
    OUTPUT(2),
    FEATURE(3),
}

data class HidReportTarget(
    val interfaceNumber: Int,
    val reportType: HidReportType,
    val reportId: Int = 0,
    val timeoutMs: Int = 500,
)

data class VendorControlTarget(
    val interfaceNumber: Int,
    val request: Int,
    val value: Int = 0,
    val index: Int = interfaceNumber,
    val deviceRecipient: Boolean = false,
    val timeoutMs: Int = 500,
)

data class BulkTarget(
    val interfaceNumber: Int,
    val endpointAddress: Int,
    val timeoutMs: Int = 750,
)

data class EndpointPairTarget(
    val interfaceNumber: Int,
    val outEndpointAddress: Int,
    val inEndpointAddress: Int,
    /** Optional device-specific OUT->IN turn-around delay. Keep generic transport default at zero. */
    val turnaroundDelayMs: Int = 0,
    val timeoutMs: Int = 500,
)

internal class UsbAudioEngineVendorTransport(
    private val engine: UsbAudioEngine,
) : UsbVendorTransport {
    override suspend fun extensionUnitRead(target: ExtensionUnitTarget, length: Int): Result<ByteArray> =
        engine.vendorExtensionUnitRead(target, length)

    override suspend fun extensionUnitWrite(target: ExtensionUnitTarget, data: ByteArray): Result<Int> =
        engine.vendorExtensionUnitWrite(target, data)

    override suspend fun hidGetReport(target: HidReportTarget, length: Int): Result<ByteArray> =
        engine.vendorHidGetReport(target, length)

    override suspend fun hidSetReport(target: HidReportTarget, data: ByteArray): Result<Int> =
        engine.vendorHidSetReport(target, data)

    override suspend fun vendorControlIn(target: VendorControlTarget, length: Int): Result<ByteArray> =
        engine.vendorControlIn(target, length)

    override suspend fun vendorControlOut(target: VendorControlTarget, data: ByteArray): Result<Int> =
        engine.vendorControlOut(target, data)

    override suspend fun bulkIn(target: BulkTarget, length: Int): Result<ByteArray> =
        engine.vendorBulkIn(target, length)

    override suspend fun bulkOut(target: BulkTarget, data: ByteArray): Result<Int> =
        engine.vendorBulkOut(target, data)

    override suspend fun endpointExchange(
        target: EndpointPairTarget,
        request: ByteArray,
        responseLength: Int,
    ): Result<ByteArray> = engine.vendorEndpointExchange(target, request, responseLength)

    override suspend fun endpointWrite(
        target: EndpointPairTarget,
        request: ByteArray,
    ): Result<Int> = engine.vendorEndpointWrite(target, request)
}
