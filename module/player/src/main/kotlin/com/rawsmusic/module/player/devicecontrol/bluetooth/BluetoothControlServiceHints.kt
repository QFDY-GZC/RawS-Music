package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * Filters cached remote service UUIDs down to non-standard audio/profile UUIDs that are useful as
 * bounded BLE control-companion scan hints. This does not assign protocol semantics to the UUID;
 * a concrete vendor adapter still has to match the discovered GATT inventory before any I/O.
 */
internal fun filterBluetoothVendorServiceHints(uuids: Iterable<UUID>): List<UUID> =
    uuids.filterNot { it in KNOWN_CLASSIC_AUDIO_SERVICE_UUIDS }.distinct()

internal val OPO_BLE_CONTROL_SERVICE_UUID: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID

/** OPO/BBK control service is known to require companion/name discovery on some dual-mode buds. */
internal fun prefersBluetoothControlCompanionLookup(uuids: Iterable<UUID>): Boolean =
    uuids.any { it in BluetoothOpoRfcommProtocol.KNOWN_SERVICE_UUIDS }


/** Cached SIG LE Audio control services are only a hint; absence normally does not prove unsupported. */
internal fun BluetoothDevice.hasCachedStandardLeAudioControlHint(): Boolean =
    runCatching { uuids.orEmpty().map { it.uuid } }.getOrDefault(emptyList()).any { uuid ->
        uuid == BluetoothLeAudioStandard.VCS_SERVICE ||
            uuid == BluetoothLeAudioStandard.VOCS_SERVICE ||
            uuid == BluetoothLeAudioStandard.AICS_SERVICE
    }

/**
 * Known OPO/BBK dual-mode devices can expose the proprietary 079A/1107 control plane while direct GATT
 * on the media-route identity stalls. Avoid opening a second, speculative SIG VCS/VOCS/AICS GATT
 * client first unless the cached UUIDs actually hint that one of those standard services exists.
 */
internal fun BluetoothDevice.shouldDeferStandardGattProbeToKnownVendorControl(): Boolean =
    prefersBluetoothControlCompanionLookup(cachedVendorServiceUuids()) &&
        !hasCachedStandardLeAudioControlHint()

private val KNOWN_CLASSIC_AUDIO_SERVICE_UUIDS = setOf(
    "00001101-0000-1000-8000-00805f9b34fb", // SPP
    "00001108-0000-1000-8000-00805f9b34fb", // Headset
    "0000110a-0000-1000-8000-00805f9b34fb", // Audio Source
    "0000110b-0000-1000-8000-00805f9b34fb", // Audio Sink
    "0000110c-0000-1000-8000-00805f9b34fb", // AVRCP Target
    "0000110e-0000-1000-8000-00805f9b34fb", // AVRCP Controller
    "0000111e-0000-1000-8000-00805f9b34fb", // Handsfree
).mapTo(linkedSetOf()) { UUID.fromString(it) }
