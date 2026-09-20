package com.rawsmusic.module.player.devicecontrol.bluetooth

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BluetoothOpoRfcommReadOnlyAdapterTest {
    @Test
    fun exactFree4ProfilePlusRuntimeBitsExposeWritableSixBandCreateDraft() {
        val observation = observation(
            queryPayloads = mapOf(
                "capability" to capabilityPayload(7, 8, 10, 34, 47, 57), // 0x0403, 0x0404, 0x0406, 0x0418, 0x0422, 0x0427
                "product_id" to byteArrayOf(0x00, 0x10, 0x8c.toByte(), 0x06),
                "version" to (byteArrayOf(0x00, 0x04) + "1,2,126,2,2,126,3,1,01,3,2,105".encodeToByteArray()),
                "anc" to byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x01), // real Free4: current leaf protocolIndex 8
                "anc_support" to byteArrayOf(0x00, 0x02, 0x01, 0x06, 0x00), // real Free4: support parent groups 1/2
                "eq_current" to byteArrayOf(0x00, 0x01),
                "feature_switches" to byteArrayOf(0x00, 0x05, 0x04, 0x01, 0x06, 0x00, 0x11, 0x01, 0x18, 0x01, 0x1c, 0x00),
                "headset_spatial" to byteArrayOf(0x00, 0x01),
                "prompt_volume" to byteArrayOf(0x00, 0x06),
                "eq_all" to byteArrayOf(0x00, 0x00),
            ),
        )
        val decoded = BluetoothOpoRfcommReadOnlyAdapter.decode(
            DeviceControlDevice(
                stableId = "bluetooth:test",
                displayName = "OPPO Enco Free4",
                connectionKind = DeviceConnectionKind.BLUETOOTH,
            ),
            observation,
        )

        assertEquals("OPPO Enco Free4", decoded.device.model)
        assertEquals("068C10", decoded.profile?.productIdHex)

        val anc = decoded.capabilities.filterIsInstance<DeviceControlCapability.Choice>()
            .first { it.id.value == "bluetooth:opo:anc:mode" }
        assertEquals(DeviceControlAccess.READ_WRITE, anc.control.access)
        assertEquals("8", anc.control.currentValue)
        assertEquals(listOf("4", "5", "6", "7", "11", "8", "3"), anc.control.options.map { it.value })
        assertTrue(decoded.diagnostics.any { it.contains("runtimeGateOnly=[]") })
        assertEquals(listOf("强降噪", "中度降噪", "弱降噪", "智能降噪", "自适应", "通透", "关闭"), anc.control.options.map { it.label })

        val eq = decoded.capabilities.filterIsInstance<DeviceControlCapability.Choice>()
            .first { it.id.value == "bluetooth:opo:eq:preset" }
        assertEquals(DeviceControlAccess.READ_WRITE, eq.control.access)
        assertEquals("1", eq.control.currentValue)
        assertEquals(listOf("0", "1", "2", "7"), eq.control.options.map { it.value })
        assertEquals(listOf("至臻原音", "纯享人声", "澎湃低音", "活力动感"), eq.control.options.map { it.label })

        val graphic = decoded.capabilities.filterIsInstance<DeviceControlCapability.GraphicEq>()
            .first { it.id.value == "bluetooth:opo:eq:custom" }
        assertEquals(6, graphic.bands.size)
        assertEquals(listOf(62.0, 250.0, 1000.0, 4000.0, 8000.0, 16000.0), graphic.bands.map { it.frequencyHz })
        assertTrue(graphic.bands.all { it.gain.access == DeviceControlAccess.READ_WRITE })
        assertTrue(graphic.bands.all { it.gain.current == 0.0 })
        assertEquals(listOf(-6.0, 6.0), listOf(graphic.bands.first().gain.ranges.single().min, graphic.bands.first().gain.ranges.single().max))
        assertTrue(decoded.diagnostics.any { it.contains("customEqBands=6") })
        assertTrue(decoded.diagnostics.any { it.contains("source=heymelody_profile_new") })

        val wear = decoded.capabilities.filterIsInstance<DeviceControlCapability.Toggle>()
            .first { it.id.value == "bluetooth:opo:feature:4" }
        assertEquals(DeviceControlAccess.READ_WRITE, wear.control.access)
        assertTrue(wear.control.current == true)

        val spatial = decoded.capabilities.filterIsInstance<DeviceControlCapability.Choice>()
            .first { it.id.value == "bluetooth:opo:spatial:type" }
        assertEquals(DeviceControlAccess.READ_WRITE, spatial.control.access)
        assertEquals("1", spatial.control.currentValue)
        assertEquals(listOf("0", "1"), spatial.control.options.map { it.value })

        val prompt = decoded.capabilities.filterIsInstance<DeviceControlCapability.Range>()
            .first { it.id.value == "bluetooth:opo:prompt_volume" }
        assertEquals(DeviceControlAccess.READ_WRITE, prompt.control.access)
        assertEquals(6.0, prompt.control.current)
        assertEquals(1.0, prompt.control.ranges.single().min)
        assertEquals(10.0, prompt.control.ranges.single().max)
    }

    @Test
    fun unknownProductNeverUsesFree4ProfileForWrites() {
        val observation = observation(
            queryPayloads = mapOf(
                "capability" to capabilityPayload(8, 10, 34),
                "product_id" to byteArrayOf(0x00, 0x56, 0x34, 0x12),
                "anc" to byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x01), // real Free4: current leaf protocolIndex 8
                "anc_support" to byteArrayOf(0x00, 0x02, 0x01, 0x06, 0x00), // real Free4: support parent groups 1/2
                "eq_current" to byteArrayOf(0x00, 0x01),
                "eq_all" to byteArrayOf(0x00, 0x00),
            ),
        )
        val decoded = BluetoothOpoRfcommReadOnlyAdapter.decode(
            DeviceControlDevice("bluetooth:test", "Unknown OPO", DeviceConnectionKind.BLUETOOTH),
            observation,
        )

        assertEquals(null, decoded.profile)
        val choices = decoded.capabilities.filterIsInstance<DeviceControlCapability.Choice>()
        assertNotNull(choices.firstOrNull { it.control.access == DeviceControlAccess.READ_ONLY })
        assertFalse(choices.any { it.control.access == DeviceControlAccess.READ_WRITE })
    }

    private fun observation(queryPayloads: Map<String, ByteArray>): BluetoothOpoRfcommProtocol.StreamObservation {
        val decoder = BluetoothOpoRfcommProtocol.FrameDecoder()
        val observations = BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES.mapNotNull { query ->
            val payload = queryPayloads[query.name] ?: return@mapNotNull null
            val frame = decoder.feed(
                BluetoothOpoRfcommProtocol.buildPacket(query.expectedResponseCommand, query.sequence, payload),
            ).single()
            BluetoothOpoRfcommProtocol.QueryObservation(
                name = query.name,
                command = query.command,
                expectedResponseCommand = query.expectedResponseCommand,
                sequence = query.sequence,
                frames = listOf(frame),
                matchedResponses = 1,
            )
        }
        return BluetoothOpoRfcommProtocol.StreamObservation(
            passiveChunks = emptyList(),
            postHelloChunks = emptyList(),
            queryObservations = observations,
        )
    }

    private fun capabilityPayload(vararg setBits: Int): ByteArray {
        val maxBit = setBits.maxOrNull() ?: 0
        val payload = ByteArray(2 + maxBit / 8)
        payload[0] = 0x00
        setBits.forEach { bit ->
            payload[1 + bit / 8] = (payload[1 + bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        return payload
    }
}
