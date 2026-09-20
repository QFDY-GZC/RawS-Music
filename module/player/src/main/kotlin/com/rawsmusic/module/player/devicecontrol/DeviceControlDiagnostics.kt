package com.rawsmusic.module.player.devicecontrol

/** Deterministic, text-only report suitable for logcat or bug reports. */
object DeviceControlDiagnostics {
    fun render(snapshot: DeviceControlSnapshot): String = buildString {
        appendLine("RawSMusic Hardware Device Control")
        appendLine("generation=${snapshot.generation}")
        appendLine("state=${snapshot.probeState}")
        snapshot.device?.let { device ->
            append("device=").append(device.displayName)
                .append(" stableId=").append(redactStableId(device.stableId))
                .append(" kind=").append(device.connectionKind)
            device.vendorId?.let { append(" vid=0x").append(it.toString(16).padStart(4, '0')) }
            device.productId?.let { append(" pid=0x").append(it.toString(16).padStart(4, '0')) }
            appendLine()
        }
        snapshot.message?.takeIf { it.isNotBlank() }?.let { appendLine("message=$it") }
        appendLine("capabilities=${snapshot.capabilities.size}")
        snapshot.capabilities.forEach { capability ->
            appendLine("capability id=${capability.id.value} type=${capabilityType(capability)} label=${capability.label}")
            when (capability) {
                is DeviceControlCapability.GraphicEq -> {
                    appendLine("  bands=${capability.bands.size}")
                    capability.bands.forEachIndexed { index, band ->
                        append("  band[").append(index).append("] id=").append(band.id.value)
                        band.frequencyHz?.let { append(" frequencyHz=").append(it) }
                        band.label?.let { append(" label=").append(it) }
                        append(" access=").append(band.gain.access)
                        append(" current=").append(band.gain.current)
                        appendLine(" ranges=${band.gain.ranges}")
                    }
                }
                is DeviceControlCapability.ParametricEq -> appendLine("  bands=${capability.bands.size}")
                is DeviceControlCapability.Volume -> appendLine("  channels=${capability.channels.size} mutes=${capability.mutes.size}")
                is DeviceControlCapability.Range -> appendLine("  access=${capability.control.access} current=${capability.control.current} ranges=${capability.control.ranges}")
                is DeviceControlCapability.Toggle -> appendLine("  access=${capability.control.access} current=${capability.control.current}")
                is DeviceControlCapability.Choice -> appendLine("  access=${capability.control.access} current=${capability.control.currentValue} options=${capability.control.options.map { it.value }}")
                is DeviceControlCapability.Dynamics -> Unit
                is DeviceControlCapability.Vendor -> appendLine("  adapter=${capability.adapterId} controls=${capability.controls.size}")
            }
        }
        snapshot.diagnostics.forEach { appendLine("diag=$it") }
    }.trimEnd()

    private fun capabilityType(capability: DeviceControlCapability): String = when (capability) {
        is DeviceControlCapability.GraphicEq -> "GraphicEq"
        is DeviceControlCapability.ParametricEq -> "ParametricEq"
        is DeviceControlCapability.Volume -> "Volume"
        is DeviceControlCapability.Range -> "Range"
        is DeviceControlCapability.Toggle -> "Toggle"
        is DeviceControlCapability.Choice -> "Choice"
        is DeviceControlCapability.Dynamics -> "Dynamics"
        is DeviceControlCapability.Vendor -> "Vendor"
    }

    private fun redactStableId(stableId: String): String = when {
        stableId.startsWith("bluetooth:") -> "bluetooth:<redacted>"
        else -> stableId
    }
}
