package com.rawsmusic.module.player.devicecontrol.usb

import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class UsbStandardControlProbeParserTest {
    @Test
    fun fiveBandGraphicEqRemainsFiveBands() {
        val json = """
            {
              "schema":1,
              "protocol":"uac2",
              "terminalLink":1,
              "device":{"vendorId":4660,"productId":43981,"name":"Probe DAC"},
              "graphicEq":[{
                "interface":0,"entityId":5,"channel":0,"selector":6,"access":"read_write",
                "bands":[
                  {"bandNumber":18,"frequencyHz":63,"currentDb":-3,"ranges":[{"min":-12,"max":12,"step":0.5}]},
                  {"bandNumber":24,"frequencyHz":250,"currentDb":-1,"ranges":[{"min":-12,"max":12,"step":0.5}]},
                  {"bandNumber":30,"frequencyHz":1000,"currentDb":0,"ranges":[{"min":-12,"max":12,"step":0.5}]},
                  {"bandNumber":36,"frequencyHz":4000,"currentDb":2,"ranges":[{"min":-12,"max":12,"step":0.5}]},
                  {"bandNumber":42,"frequencyHz":16000,"currentDb":4,"ranges":[{"min":-12,"max":12,"step":0.5}]}
                ]
              }],
              "parametricEq":[],"dynamics":[],"diagnostics":[]
            }
        """.trimIndent()

        val snapshot = assertNotNull(UsbStandardControlProbeParser.parse(json, generation = 7))
        assertEquals(DeviceControlSnapshot.ProbeState.READY, snapshot.probeState)
        assertEquals("usb:1234:abcd", snapshot.device?.stableId)
        val eq = assertIs<DeviceControlCapability.GraphicEq>(snapshot.capabilities.single())
        assertEquals(5, eq.bands.size)
        assertEquals(listOf(63.0, 250.0, 1000.0, 4000.0, 16000.0), eq.bands.map { it.frequencyHz })
        val address = assertIs<DeviceControlBackendAddress.UsbAudioClass>(eq.bands.first().gain.address)
        assertEquals(18, address.elementIndex)
        assertEquals(0.5, eq.bands.first().gain.ranges.single().step)
    }
}
