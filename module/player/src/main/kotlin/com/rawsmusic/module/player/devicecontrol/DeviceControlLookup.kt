package com.rawsmusic.module.player.devicecontrol

internal sealed interface ResolvedDeviceControl {
    val id: DeviceControlId
    val access: DeviceControlAccess
    val address: DeviceControlBackendAddress

    data class Numeric(val control: DeviceNumericControl) : ResolvedDeviceControl {
        override val id get() = control.id
        override val access get() = control.access
        override val address get() = control.address
    }

    data class Toggle(val control: DeviceToggleControl) : ResolvedDeviceControl {
        override val id get() = control.id
        override val access get() = control.access
        override val address get() = control.address
    }

    data class Choice(val control: DeviceChoiceControl) : ResolvedDeviceControl {
        override val id get() = control.id
        override val access get() = control.access
        override val address get() = control.address
    }
}

internal fun DeviceControlSnapshot.resolveControl(id: DeviceControlId): ResolvedDeviceControl? {
    fun numeric(control: DeviceNumericControl?): ResolvedDeviceControl? =
        control?.takeIf { it.id == id }?.let(ResolvedDeviceControl::Numeric)
    fun toggle(control: DeviceToggleControl?): ResolvedDeviceControl? =
        control?.takeIf { it.id == id }?.let(ResolvedDeviceControl::Toggle)
    fun choice(control: DeviceChoiceControl?): ResolvedDeviceControl? =
        control?.takeIf { it.id == id }?.let(ResolvedDeviceControl::Choice)

    capabilities.forEach { capability ->
        when (capability) {
            is DeviceControlCapability.GraphicEq -> {
                toggle(capability.enabled)?.let { return it }
                capability.bands.forEach { band -> numeric(band.gain)?.let { return it } }
            }
            is DeviceControlCapability.ParametricEq -> capability.bands.forEach { band ->
                toggle(band.enabled)?.let { return it }
                numeric(band.frequency)?.let { return it }
                numeric(band.q)?.let { return it }
                numeric(band.gain)?.let { return it }
            }
            is DeviceControlCapability.Volume -> {
                capability.channels.forEach { numeric(it)?.let { found -> return found } }
                capability.mutes.forEach { toggle(it)?.let { found -> return found } }
            }
            is DeviceControlCapability.Range -> numeric(capability.control)?.let { return it }
            is DeviceControlCapability.Toggle -> toggle(capability.control)?.let { return it }
            is DeviceControlCapability.Choice -> choice(capability.control)?.let { return it }
            is DeviceControlCapability.Dynamics -> {
                toggle(capability.enabled)?.let { return it }
                numeric(capability.ratio)?.let { return it }
                numeric(capability.maxAmplitude)?.let { return it }
                numeric(capability.threshold)?.let { return it }
                numeric(capability.attack)?.let { return it }
                numeric(capability.release)?.let { return it }
            }
            is DeviceControlCapability.Vendor -> capability.controls.forEach { vendor ->
                when (vendor) {
                    is VendorControl.Numeric -> numeric(vendor.control)?.let { return it }
                    is VendorControl.Toggle -> toggle(vendor.control)?.let { return it }
                    is VendorControl.Choice -> choice(vendor.control)?.let { return it }
                }
            }
        }
    }
    return null
}

internal fun DeviceNumericControl.accepts(value: Double): Boolean {
    if (!value.isFinite()) return false
    if (ranges.isEmpty()) return true
    return ranges.any { range -> value >= range.min - 1e-9 && value <= range.max + 1e-9 }
}
