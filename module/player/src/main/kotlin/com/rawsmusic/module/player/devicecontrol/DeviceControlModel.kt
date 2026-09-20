package com.rawsmusic.module.player.devicecontrol

/** Physical control transport. Audio transport remains owned by the playback engine. */
enum class DeviceConnectionKind {
    USB,
    BLUETOOTH,
}

enum class DeviceControlTransport {
    USB_AUDIO_CLASS,
    USB_VENDOR,
    BLUETOOTH_STANDARD,
    BLUETOOTH_GATT,
    BLUETOOTH_RFCOMM,
}

enum class DeviceControlAccess {
    ABSENT,
    READ_ONLY,
    READ_WRITE;

    val isPresent: Boolean get() = this != ABSENT
    val isWritable: Boolean get() = this == READ_WRITE

    companion object {
        /** Decode the 2-bit access pair used by UAC2 controls. 0b10 is reserved. */
        fun fromUac2Pair(pair: Int): DeviceControlAccess = when (pair and 0x3) {
            0x0 -> ABSENT
            0x1 -> READ_ONLY
            0x3 -> READ_WRITE
            else -> ABSENT
        }
    }
}

@JvmInline
value class DeviceControlId(val value: String)

/** Opaque to UI renderers; consumed only by the backend that owns the capability. */
sealed interface DeviceControlBackendAddress {
    val transport: DeviceControlTransport

    data class UsbAudioClass(
        val interfaceNumber: Int,
        val entityId: Int,
        val selector: Int,
        val channel: Int,
        /** Optional element inside a compound UAC control, e.g. Graphic EQ band number. */
        val elementIndex: Int? = null,
    ) : DeviceControlBackendAddress {
        override val transport = DeviceControlTransport.USB_AUDIO_CLASS
    }

    data class UsbVendor(
        val adapterId: String,
        val endpointKey: String,
    ) : DeviceControlBackendAddress {
        override val transport = DeviceControlTransport.USB_VENDOR
    }

    data class BluetoothGatt(
        val serviceUuid: String,
        val characteristicUuid: String,
        val descriptorUuid: String? = null,
        /** Zero-based instance among services with the same UUID. */
        val serviceInstanceIndex: Int = 0,
        /** Non-null only for a matched vendor protocol adapter. */
        val adapterId: String? = null,
    ) : DeviceControlBackendAddress {
        override val transport = DeviceControlTransport.BLUETOOTH_GATT
    }

    data class BluetoothStandard(
        val serviceUuid: String,
        val characteristicUuid: String,
        /** Zero-based instance among services with the same UUID. */
        val serviceInstanceIndex: Int = 0,
    ) : DeviceControlBackendAddress {
        override val transport = DeviceControlTransport.BLUETOOTH_STANDARD
    }

    data class BluetoothRfcomm(
        val serviceUuid: String,
        val commandKey: String,
    ) : DeviceControlBackendAddress {
        override val transport = DeviceControlTransport.BLUETOOTH_RFCOMM
    }
}

data class DeviceNumericRange(
    val min: Double,
    val max: Double,
    val step: Double? = null,
) {
    init {
        require(max >= min) { "max must be >= min" }
        require(step == null || step > 0.0) { "step must be positive" }
    }
}

data class DeviceNumericControl(
    val id: DeviceControlId,
    val label: String,
    val unit: String? = null,
    val access: DeviceControlAccess,
    val current: Double? = null,
    val ranges: List<DeviceNumericRange> = emptyList(),
    val address: DeviceControlBackendAddress,
)

data class DeviceToggleControl(
    val id: DeviceControlId,
    val label: String,
    val access: DeviceControlAccess,
    val current: Boolean? = null,
    val address: DeviceControlBackendAddress,
)

data class DeviceChoiceOption(
    val value: String,
    val label: String,
)

data class DeviceChoiceControl(
    val id: DeviceControlId,
    val label: String,
    val access: DeviceControlAccess,
    val currentValue: String? = null,
    val options: List<DeviceChoiceOption>,
    val address: DeviceControlBackendAddress,
)

data class GraphicEqBand(
    val id: DeviceControlId,
    val frequencyHz: Double? = null,
    val label: String? = null,
    val gain: DeviceNumericControl,
)

data class ParametricEqBand(
    val id: DeviceControlId,
    val label: String? = null,
    val enabled: DeviceToggleControl? = null,
    val frequency: DeviceNumericControl? = null,
    val q: DeviceNumericControl? = null,
    val gain: DeviceNumericControl? = null,
)

sealed interface DeviceControlCapability {
    val id: DeviceControlId
    val label: String

    data class GraphicEq(
        override val id: DeviceControlId,
        override val label: String,
        val bands: List<GraphicEqBand>,
        val enabled: DeviceToggleControl? = null,
    ) : DeviceControlCapability

    data class ParametricEq(
        override val id: DeviceControlId,
        override val label: String,
        val bands: List<ParametricEqBand>,
    ) : DeviceControlCapability

    data class Volume(
        override val id: DeviceControlId,
        override val label: String,
        val channels: List<DeviceNumericControl>,
        /** Mute may exist on master and/or individual logical channels. */
        val mutes: List<DeviceToggleControl> = emptyList(),
    ) : DeviceControlCapability

    data class Range(
        override val id: DeviceControlId,
        override val label: String,
        val control: DeviceNumericControl,
    ) : DeviceControlCapability

    data class Toggle(
        override val id: DeviceControlId,
        override val label: String,
        val control: DeviceToggleControl,
    ) : DeviceControlCapability

    data class Choice(
        override val id: DeviceControlId,
        override val label: String,
        val control: DeviceChoiceControl,
    ) : DeviceControlCapability

    data class Dynamics(
        override val id: DeviceControlId,
        override val label: String,
        val enabled: DeviceToggleControl? = null,
        val ratio: DeviceNumericControl? = null,
        val maxAmplitude: DeviceNumericControl? = null,
        val threshold: DeviceNumericControl? = null,
        val attack: DeviceNumericControl? = null,
        val release: DeviceNumericControl? = null,
    ) : DeviceControlCapability

    /** Known vendor adapter decoded the meaning; unknown writable endpoints never become controls. */
    data class Vendor(
        override val id: DeviceControlId,
        override val label: String,
        val adapterId: String,
        val controls: List<VendorControl>,
    ) : DeviceControlCapability
}

sealed interface VendorControl {
    val id: DeviceControlId
    val label: String

    data class Numeric(
        override val id: DeviceControlId,
        override val label: String,
        val control: DeviceNumericControl,
    ) : VendorControl

    data class Toggle(
        override val id: DeviceControlId,
        override val label: String,
        val control: DeviceToggleControl,
    ) : VendorControl

    data class Choice(
        override val id: DeviceControlId,
        override val label: String,
        val control: DeviceChoiceControl,
    ) : VendorControl
}

data class DeviceControlDevice(
    val stableId: String,
    val displayName: String,
    val connectionKind: DeviceConnectionKind,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val model: String? = null,
    val firmware: String? = null,
)

data class DeviceControlSnapshot(
    val generation: Long,
    val device: DeviceControlDevice?,
    val capabilities: List<DeviceControlCapability> = emptyList(),
    val probeState: ProbeState = ProbeState.IDLE,
    val message: String? = null,
    /** Passive protocol/descriptor facts useful for adding vendor adapters. Never contains secrets. */
    val diagnostics: List<String> = emptyList(),
) {
    enum class ProbeState {
        IDLE,
        PROBING,
        RECONNECTING,
        READY,
        PERMISSION_REQUIRED,
        DISCONNECTED,
        UNSUPPORTED,
        ERROR,
    }
}
