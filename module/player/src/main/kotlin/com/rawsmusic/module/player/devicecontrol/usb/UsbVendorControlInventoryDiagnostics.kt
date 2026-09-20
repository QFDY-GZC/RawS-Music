package com.rawsmusic.module.player.devicecontrol.usb

internal fun UsbVendorControlInventory.toDiagnosticLines(): List<String> = buildList {
    add("usb.vendor vid=${device.vendorId ?: 0} pid=${device.productId ?: 0}")
    extensionUnits.forEachIndexed { index, unit ->
        add(
            "usb.xu[$index] if=${unit.interfaceNumber} entity=${unit.entityId} code=0x${unit.extensionCode.toString(16)} sources=${unit.sourceIds.joinToString(",")} controls=${unit.controlBytes.joinToString(",")}",
        )
    }
    hidInterfaces.forEachIndexed { index, iface ->
        add(
            "usb.hid[$index] if=${iface.interfaceNumber} alt=${iface.alternateSetting} proto=${iface.interfaceProtocol} reportLen=${iface.hidReportDescriptorLength} eps=${iface.endpoints.joinToString(",") { ep -> "0x${ep.address.toString(16)}:${ep.transferType}:${ep.maxPacketSize}" }}",
        )
        add(
            "usb.hid[$index].semantic=${iface.hidSemantic.name.lowercase()} dspCandidate=${iface.isLikelyDspHidCandidate}",
        )
        if (iface.hidUsagePages.isNotEmpty()) {
            add(
                "usb.hid[$index].usagePages=${iface.hidUsagePages.joinToString(",") { page -> "0x${page.toString(16)}" }}",
            )
        }
        iface.hidReports.forEach { report ->
            add(
                "usb.hid[$index].report id=0x${report.reportId.toString(16)} in=${report.inputBytes} out=${report.outputBytes} feature=${report.featureBytes} payloadBytes",
            )
        }
        if (iface.hidReportDescriptorHex.isNotBlank()) {
            add("usb.hid[$index].reportDescriptorHex=${iface.hidReportDescriptorHex}")
        }
    }
    vendorInterfaces.forEachIndexed { index, iface ->
        add(
            "usb.iface[$index] if=${iface.interfaceNumber} alt=${iface.alternateSetting} class=0x${iface.interfaceClass.toString(16)} sub=0x${iface.interfaceSubClass.toString(16)} proto=0x${iface.interfaceProtocol.toString(16)} eps=${iface.endpoints.joinToString(",") { ep -> "0x${ep.address.toString(16)}:${ep.transferType}:${ep.maxPacketSize}" }}",
        )
    }
    diagnostics.forEach { add("usb.inventory=$it") }
}
