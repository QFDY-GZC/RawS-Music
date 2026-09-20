package com.rawsmusic.module.player.devicecontrol.usb

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import org.json.JSONArray
import org.json.JSONObject

data class UsbVendorControlInventory(
    val device: DeviceControlDevice,
    val extensionUnits: List<ExtensionUnit>,
    val hidInterfaces: List<Interface>,
    val vendorInterfaces: List<Interface>,
    val diagnostics: List<String> = emptyList(),
) {
    data class ExtensionUnit(
        val interfaceNumber: Int,
        val entityId: Int,
        val extensionCode: Int,
        val sourceIds: List<Int>,
        val controlBytes: List<Int>,
    )

    data class Interface(
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val interfaceClass: Int,
        val interfaceSubClass: Int,
        val interfaceProtocol: Int,
        val hidReportDescriptorLength: Int,
        val hidReportDescriptorHex: String,
        val hidUsagePages: List<Long>,
        val hidReports: List<HidReport>,
        val endpoints: List<Endpoint>,
    )


    enum class HidSemantic {
        CONSUMER_CONTROL,
        VENDOR_DEFINED,
        GENERIC_HID,
        UNKNOWN,
    }

    data class HidReport(
        val reportId: Int,
        val inputBits: Long,
        val outputBits: Long,
        val featureBits: Long,
        val inputBytes: Long,
        val outputBytes: Long,
        val featureBytes: Long,
    )

    data class Endpoint(
        val address: Int,
        val attributes: Int,
        val maxPacketSize: Int,
        val interval: Int,
    ) {
        val isIn: Boolean get() = address and 0x80 != 0
        val transferType: Int get() = attributes and 0x03
    }
}

internal val UsbVendorControlInventory.Interface.hidSemantic: UsbVendorControlInventory.HidSemantic
    get() = when {
        interfaceClass != HID_INTERFACE_CLASS -> UsbVendorControlInventory.HidSemantic.UNKNOWN
        hidUsagePages.any { it >= VENDOR_USAGE_PAGE_MIN } -> UsbVendorControlInventory.HidSemantic.VENDOR_DEFINED
        hidUsagePages.isNotEmpty() && hidUsagePages.all { it == CONSUMER_USAGE_PAGE } ->
            UsbVendorControlInventory.HidSemantic.CONSUMER_CONTROL
        hidUsagePages.isNotEmpty() -> UsbVendorControlInventory.HidSemantic.GENERIC_HID
        else -> UsbVendorControlInventory.HidSemantic.UNKNOWN
    }

internal val UsbVendorControlInventory.Interface.isLikelyDspHidCandidate: Boolean
    get() = hidSemantic == UsbVendorControlInventory.HidSemantic.VENDOR_DEFINED

private const val HID_INTERFACE_CLASS = 0x03
private const val CONSUMER_USAGE_PAGE = 0x0cL
private const val VENDOR_USAGE_PAGE_MIN = 0xff00L

object UsbVendorControlInventoryParser {
    fun parse(json: String?): UsbVendorControlInventory? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val deviceJson = root.optJSONObject("device") ?: JSONObject()
            val vendorId = deviceJson.optInt("vendorId", 0).takeIf { it != 0 }
            val productId = deviceJson.optInt("productId", 0).takeIf { it != 0 }
            val displayName = deviceJson.optString("name", "").trim().ifEmpty { "USB Audio Device" }
            val device = DeviceControlDevice(
                stableId = buildString {
                    append("usb:")
                    append(vendorId?.toString(16)?.padStart(4, '0') ?: "unknown")
                    append(':')
                    append(productId?.toString(16)?.padStart(4, '0') ?: "unknown")
                },
                displayName = displayName,
                connectionKind = DeviceConnectionKind.USB,
                vendorId = vendorId,
                productId = productId,
            )
            UsbVendorControlInventory(
                device = device,
                extensionUnits = root.optJSONArray("extensionUnits").parseObjects { item ->
                    UsbVendorControlInventory.ExtensionUnit(
                        interfaceNumber = item.optInt("interface", -1),
                        entityId = item.optInt("entityId", -1),
                        extensionCode = item.optInt("extensionCode", 0),
                        sourceIds = item.optJSONArray("sourceIds").intList(),
                        controlBytes = item.optJSONArray("controlBytes").intList(),
                    )
                }.filter { it.interfaceNumber >= 0 && it.entityId > 0 },
                hidInterfaces = root.optJSONArray("hidInterfaces").parseObjects(::parseInterface),
                vendorInterfaces = root.optJSONArray("vendorInterfaces").parseObjects(::parseInterface),
                diagnostics = root.optJSONArray("diagnostics").stringList(),
            )
        }.getOrNull()
    }

    private fun parseInterface(item: JSONObject): UsbVendorControlInventory.Interface =
        UsbVendorControlInventory.Interface(
            interfaceNumber = item.optInt("interface", -1),
            alternateSetting = item.optInt("alternateSetting", 0),
            interfaceClass = item.optInt("class", 0),
            interfaceSubClass = item.optInt("subClass", 0),
            interfaceProtocol = item.optInt("protocol", 0),
            hidReportDescriptorLength = item.optInt("hidReportDescriptorLength", 0),
            hidReportDescriptorHex = item.optString("hidReportDescriptorHex", "").trim(),
            hidUsagePages = item.optJSONArray("hidUsagePages").longList(),
            hidReports = item.optJSONArray("hidReports").parseObjects { report ->
                UsbVendorControlInventory.HidReport(
                    reportId = report.optInt("reportId", 0),
                    inputBits = report.optLong("inputBits", 0L),
                    outputBits = report.optLong("outputBits", 0L),
                    featureBits = report.optLong("featureBits", 0L),
                    inputBytes = report.optLong("inputBytes", 0L),
                    outputBytes = report.optLong("outputBytes", 0L),
                    featureBytes = report.optLong("featureBytes", 0L),
                )
            },
            endpoints = item.optJSONArray("endpoints").parseObjects { ep ->
                UsbVendorControlInventory.Endpoint(
                    address = ep.optInt("address", 0),
                    attributes = ep.optInt("attributes", 0),
                    maxPacketSize = ep.optInt("maxPacketSize", 0),
                    interval = ep.optInt("interval", 0),
                )
            },
        )
}

private inline fun <T> JSONArray?.parseObjects(block: (JSONObject) -> T): List<T> = buildList {
    val array = this@parseObjects ?: return@buildList
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(block(item))
    }
}

private fun JSONArray?.intList(): List<Int> = buildList {
    val array = this@intList ?: return@buildList
    for (index in 0 until array.length()) add(array.optInt(index))
}

private fun JSONArray?.stringList(): List<String> = buildList {
    val array = this@stringList ?: return@buildList
    for (index in 0 until array.length()) {
        val value = array.optString(index, "")
        if (value.isNotBlank()) add(value)
    }
}

private fun JSONArray?.longList(): List<Long> = buildList {
    val array = this@longList ?: return@buildList
    for (index in 0 until array.length()) add(array.optLong(index))
}
