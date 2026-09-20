package com.rawsmusic.module.player.devicecontrol.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BluetoothOpoRfcommProtocolTest {
    @Test
    fun helloUsesLittleEndianSystemCommandAndContainsNoRegisterToken() {
        val hello = BluetoothOpoRfcommProtocol.HELLO
        assertEquals(10, hello.size)
        assertEquals(0xaa, hello[0].toInt() and 0xff)
        assertEquals(0x00, hello[4].toInt() and 0xff)
        assertEquals(0x01, hello[5].toInt() and 0xff)
        assertEquals(0x23, hello[6].toInt() and 0xff)
        assertFalse(hello.any { (it.toInt() and 0xff) == 0x85 })
    }

    @Test
    fun fingerprintExposesLittleEndianCommandAndHash() {
        val value = frame(0x8100, 0x23, byteArrayOf(0x00))
        val fp = BluetoothOpoRfcommProtocol.fingerprint(value)
        assertTrue(fp.startsWithOpoSof)
        assertEquals(0x8100, fp.command)
        assertEquals(0x23, fp.sequence)
        assertEquals(16, fp.sha256Prefix.length)
    }

    @Test
    fun frameDecoderReassemblesSplitAndCoalescedReadsAndExtractsPayload() {
        val helloAck = frame(0x8100, 0x23, byteArrayOf(0x00, 0x01, 0x02))
        val anc = frame(0x810c, 0x44, byteArrayOf(0x00, 0x01, 0x01, 0x20, 0x00))
        val decoder = BluetoothOpoRfcommProtocol.FrameDecoder()

        assertTrue(decoder.feed(helloAck.copyOfRange(0, 5)).isEmpty())
        val frames = decoder.feed(helloAck.copyOfRange(5, helloAck.size) + anc)

        assertEquals(2, frames.size)
        assertTrue(BluetoothOpoRfcommProtocol.isHelloAck(frames[0]))
        assertEquals(0x810c, frames[1].command)
        assertEquals(listOf(0, 1, 1, 0x20, 0), frames[1].payload.map { it.toInt() and 0xff })
        assertEquals("0001012000", frames[1].controlDataHex)
        assertEquals(0, decoder.bufferedBytes())
    }

    @Test
    fun readOnlyPacketsUseCorrectQueryCommandsAndNoSetCommands() {
        val queries = BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES
        assertEquals(
            listOf("capability", "product_id", "version", "battery", "anc", "anc_support", "eq_current", "feature_switches", "headset_spatial", "prompt_volume", "eq_all"),
            queries.map { it.name },
        )
        val commands = queries.map { it.command }
        assertEquals(
            listOf(0x0100, 0x0103, 0x0105, 0x0106, 0x010c, 0x010c, 0x010f, 0x010d, 0x012a, 0x0130, 0x0122),
            commands,
        )
        queries.forEach { query ->
            assertEquals(query.command and 0xff, query.packet[4].toInt() and 0xff)
            assertEquals((query.command ushr 8) and 0xff, query.packet[5].toInt() and 0xff)
            assertTrue(query.command < 0x0400)
        }
        val anc = queries.first { it.name == "anc" }
        assertEquals(listOf(0x01, 0x01), anc.packet.copyOfRange(9, anc.packet.size).map { it.toInt() and 0xff })
        val ancSupport = queries.first { it.name == "anc_support" }
        assertEquals(listOf(0x02, 0x01), ancSupport.packet.copyOfRange(9, ancSupport.packet.size).map { it.toInt() and 0xff })
        val eqAll = queries.first { it.name == "eq_all" }
        assertEquals(listOf(0x01, 0x05), eqAll.packet.copyOfRange(9, eqAll.packet.size).map { it.toInt() and 0xff })
    }


    @Test
    fun featureSpatialAndPromptVolumeCodecsMatchHeyMelodyWireLayouts() {
        assertEquals(
            listOf(3, 4, 6, 24),
            BluetoothOpoRfcommProtocol.buildFeatureSwitchQueryPayload(listOf(4, 6, 24))
                .map { it.toInt() and 0xff },
        )
        assertEquals(
            mapOf(4 to 1, 6 to 0, 24 to 1),
            BluetoothOpoRfcommProtocol.parseFeatureSwitchStatuses(
                byteArrayOf(0x00, 0x03, 0x04, 0x01, 0x06, 0x00, 0x18, 0x01),
            ),
        )
        assertEquals(listOf(24, 0), BluetoothOpoRfcommProtocol.buildFeatureSwitchPayload(24, false).map { it.toInt() and 0xff })
        assertEquals(1, BluetoothOpoRfcommProtocol.parseHeadsetSpatialType(byteArrayOf(0x00, 0x01)))
        assertEquals(listOf(1), BluetoothOpoRfcommProtocol.buildHeadsetSpatialPayload(1).map { it.toInt() and 0xff })
        assertEquals(6, BluetoothOpoRfcommProtocol.parsePromptVolumeValue(byteArrayOf(0x00, 0x06)))
        assertEquals(listOf(6), BluetoothOpoRfcommProtocol.buildPromptVolumePayload(6).map { it.toInt() and 0xff })
    }

    @Test
    fun userObservedVersionPayloadDecodesAsFirmwareNotEq() {
        val ascii = "1,2,126,2,2,126,3,1,01,3,2,105".encodeToByteArray()
        val payload = byteArrayOf(0x00, 0x04) + ascii
        assertEquals("1,2,126,2,2,126,3,1,01,3,2,105", BluetoothOpoRfcommProtocol.parseVersion(payload))
    }

    @Test
    fun productIdDecodeMatchesEncoFree4KnownWireId() {
        assertEquals("068C10", BluetoothOpoRfcommProtocol.parseProductId(byteArrayOf(0x00, 0x10, 0x8c.toByte(), 0x06)))
    }

    @Test
    fun eqAllParserPreservesArbitraryBandCountsWithoutTruncation() {
        for (bands in listOf(6, 10, 20, 30)) {
            val payload = eqAllPayload(bands)
            val decoded = BluetoothOpoRfcommProtocol.parseEqAllPayload(payload)
            assertEquals(null, decoded.error, "bands=$bands")
            val entry = decoded.entries.single()
            assertEquals(bands, entry.frequenciesHz.size)
            assertEquals(bands, entry.gainsDb.size)
            assertEquals((0 until bands).map { 50 + it * 100 }, entry.frequenciesHz)
        }
    }

    @Test
    fun streamObservationMatchesResponseByCommandAndSequence() {
        val decoder = BluetoothOpoRfcommProtocol.FrameDecoder()
        val query = BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES.first { it.name == "eq_current" }
        val response = decoder.feed(frame(query.expectedResponseCommand, query.sequence, byteArrayOf(0x00, 0x03))).single()
        val observation = BluetoothOpoRfcommProtocol.StreamObservation(
            passiveChunks = emptyList(),
            postHelloChunks = emptyList(),
            queryObservations = listOf(
                BluetoothOpoRfcommProtocol.QueryObservation(
                    name = query.name,
                    command = query.command,
                    expectedResponseCommand = query.expectedResponseCommand,
                    sequence = query.sequence,
                    frames = listOf(response),
                    matchedResponses = 1,
                ),
            ),
        )
        assertEquals(1, observation.matchedReadOnlyResponses)
        assertNotNull(observation.matchedFrame("eq_current"))
    }


    @Test
    fun free4AncSupportAndSetPayloadsUseOfficialProtocolIndices() {
        // status=0, action=2, type=1, bitmask with indices 3,4,5,6,7,8,11 set.
        val support = byteArrayOf(0x00, 0x02, 0x01, 0xf8.toByte(), 0x09)
        assertEquals(
            setOf(3, 4, 5, 6, 7, 8, 11),
            BluetoothOpoRfcommProtocol.parseSupportedAncProtocolIndices(support),
        )
        assertEquals(listOf(0x01, 0x01, 0x08), BluetoothOpoRfcommProtocol.buildAncModePayload(3).map { it.toInt() and 0xff })
        assertEquals(listOf(0x01, 0x01, 0x00, 0x01), BluetoothOpoRfcommProtocol.buildAncModePayload(8).map { it.toInt() and 0xff })
        assertEquals(listOf(0x01, 0x01, 0x00, 0x08), BluetoothOpoRfcommProtocol.buildAncModePayload(11).map { it.toInt() and 0xff })
    }

    @Test
    fun free4ProfileUsesHeyMelodySixBandTopologyAndFirmwareGatedVibrantPreset() {
        val profile = BluetoothOpoDeviceProfiles.resolve("068C10")
        assertNotNull(profile)
        assertEquals(listOf(62, 250, 1000, 4000, 8000, 16000), profile.customEqFrequenciesHz)
        assertEquals(-6, profile.customEqMinGainDb)
        assertEquals(6, profile.customEqMaxGainDb)
        assertEquals(3, profile.customEqMaxPresets)
        assertEquals(listOf(0, 1, 2), profile.eqPresetsForFirmware("1,2,117,2,2,117").map { it.protocolIndex })
        assertEquals(listOf(0, 1, 2, 7), profile.eqPresetsForFirmware("1,2,118,2,2,126").map { it.protocolIndex })
    }

    @Test
    fun setEqInfoEncoderMatchesHeyMelodyLittleEndianDynamicLayout() {
        val payload = BluetoothOpoRfcommProtocol.buildEqDetailPayload(
            action = 1,
            minGainDb = -6,
            maxGainDb = 6,
            eqId = 0,
            name = "",
            frequenciesHz = listOf(62, 250, 1000, 4000, 8000, 16000),
            gainsDb = listOf(0, 1, -1, 2, -2, 0),
        )
        assertEquals(24, payload.size)
        assertEquals(listOf(0x01, 0xfa, 0x06, 0x00, 0x00, 0x06), payload.take(6).map { it.toInt() and 0xff })
        assertEquals(listOf(0x3e, 0x00, 0x00), payload.slice(6..8).map { it.toInt() and 0xff })
        assertEquals(listOf(0x80, 0x3e, 0x00), payload.takeLast(3).map { it.toInt() and 0xff })
    }

    private fun frame(command: Int, sequence: Int, payload: ByteArray): ByteArray =
        BluetoothOpoRfcommProtocol.buildPacket(command, sequence, payload)

    private fun eqAllPayload(bands: Int): ByteArray {
        val name = "Custom".encodeToByteArray()
        val out = ArrayList<Byte>()
        out += 0x00
        out += 0x01
        out += 0x01
        out += (-6).toByte()
        out += 0x06
        out += 0x07
        out += name.size.toByte()
        name.forEach(out::add)
        out += bands.toByte()
        repeat(bands) { i ->
            val freq = 50 + i * 100
            out += (freq and 0xff).toByte()
            out += ((freq ushr 8) and 0xff).toByte()
            out += ((i % 13) - 6).toByte()
        }
        return out.toByteArray()
    }
    @Test
    fun free4RealAncReadbacksDecodeLeafCurrentAndParentSupportBitsSeparately() {
        assertEquals(
            8,
            BluetoothOpoRfcommProtocol.parseCurrentAncProtocolIndex(
                byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x01),
            ),
        )
        assertEquals(
            setOf(1, 2),
            BluetoothOpoRfcommProtocol.parseSupportedAncProtocolIndices(
                byteArrayOf(0x00, 0x02, 0x01, 0x06, 0x00),
            ),
        )
        assertEquals(
            listOf(0x01, 0x01, 0x00, 0x01),
            BluetoothOpoRfcommProtocol.buildAncModePayload(8).map { it.toInt() and 0xff },
        )
    }

}
