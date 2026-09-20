package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.util.UUID

/**
 * Classic vendor-service hints used only for bounded RFCOMM service probing.
 *
 * Unknown UUIDs are never sent payloads. A probe only asks SDP for the RFCOMM channel, connects,
 * and immediately closes. Fast Pair Message Stream is deliberately excluded because it is a
 * platform interoperability channel rather than the headset DSP protocol owned by RawSMusic.
 */
internal object BluetoothRfcommVendorHints {
    val OPO_GAIA_SPP_SERVICE_UUID: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID_1107

    val GOOGLE_FAST_PAIR_MESSAGE_STREAM_UUID: UUID =
        UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")

    data class Candidate(
        val uuid: UUID,
        val source: String,
    )

    fun candidates(cachedVendorUuids: Iterable<UUID>): List<Candidate> {
        val cached = cachedVendorUuids.distinct()
        val result = linkedMapOf<UUID, Candidate>()

        cached.forEach { uuid ->
            if (uuid == GOOGLE_FAST_PAIR_MESSAGE_STREAM_UUID) return@forEach
            result.putIfAbsent(uuid, Candidate(uuid, "cached_vendor_uuid"))
        }

        // OPO implementations in the wild expose a GAIA/SPP-compatible classic service alongside
        // the 079A family. Do not assume it is present: add it only as a connection-only SDP hint
        // when the physical device itself already advertises/caches the OPO 079A identity.
        if (cached.any { it == BluetoothOpoRfcommProtocol.SERVICE_UUID }) {
            result.putIfAbsent(
                OPO_GAIA_SPP_SERVICE_UUID,
                Candidate(OPO_GAIA_SPP_SERVICE_UUID, "opo_gaia_spp_hint"),
            )
        }

        return result.values.take(MAX_PROBE_CANDIDATES)
    }

    private const val MAX_PROBE_CANDIDATES = 4
}
