package com.rawsmusic.ai.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiInstrumentPackJsonTest {
    @Test
    fun explicitCodecMaterializesTypedSamplesAndPreservesSchema2Loop() {
        val json = """
            {
              "schemaVersion": 2,
              "id": "test.piano",
              "version": "1",
              "displayName": "Test Piano",
              "instrument": "piano",
              "sampleRate": 44100,
              "channels": 2,
              "licenseFile": "license.txt",
              "samples": [
                {
                  "path": "samples/C4.wav",
                  "rootMidiNote": 60,
                  "velocityMin": 81,
                  "velocityMax": 127,
                  "gainDb": -1.5,
                  "loopStartFrame": 1000,
                  "loopEndFrameExclusive": 3000,
                  "sha256": "${"a".repeat(64)}"
                }
              ]
            }
        """.trimIndent()
        val manifest = AiInstrumentPackJson.parse(json)
        assertEquals(1, manifest.samples.size)
        val sample: AiInstrumentSampleManifest = manifest.samples.single()
        assertEquals("samples/C4.wav", sample.path)
        assertEquals(60, sample.rootMidiNote)
        assertEquals(81, sample.velocityMin)
        assertEquals(127, sample.velocityMax)
        assertEquals(1000L, sample.loopStartFrame)
        assertEquals(3000L, sample.loopEndFrameExclusive)

        val reparsed = AiInstrumentPackJson.parse(AiInstrumentPackJson.stringify(manifest))
        assertEquals(manifest, reparsed)
        assertTrue(reparsed.samples.single() is AiInstrumentSampleManifest)
    }

    @Test
    fun schema1DefaultsRemainCompatible() {
        val json = """
            {
              "schemaVersion": 1,
              "id": "test.legacy",
              "version": "1",
              "displayName": "Legacy",
              "instrument": "piano",
              "sampleRate": 48000,
              "channels": 1,
              "licenseFile": "license.txt",
              "samples": [
                {
                  "path": "samples/A4.wav",
                  "rootMidiNote": 69,
                  "sha256": "${"b".repeat(64)}"
                }
              ]
            }
        """.trimIndent()
        val sample = AiInstrumentPackJson.parse(json).samples.single()
        assertEquals(1, sample.velocityMin)
        assertEquals(127, sample.velocityMax)
        assertEquals(0f, sample.gainDb)
        assertEquals(null, sample.loopStartFrame)
        assertEquals(null, sample.loopEndFrameExclusive)
    }
    @Test
    fun canonicalDirectoryIdentityIsCheckedOnlyAfterAtomicInstall() {
        val manifest = AiInstrumentPackManifest(
            schemaVersion = 2,
            id = "test.piano",
            version = "1.0",
            displayName = "Test Piano",
            instrument = "piano",
            sampleRate = 44100,
            channels = 2,
            licenseFile = "license.txt",
            samples = listOf(
                AiInstrumentSampleManifest(
                    path = "samples/C4.wav",
                    rootMidiNote = 60,
                    sha256 = "${"c".repeat(64)}",
                )
            ),
        )
        val root = File("build/test-pack-root")
        val canonical = File(File(root, manifest.id), manifest.version)
        val staging = File(root, ".${manifest.id}.${manifest.version}.123.tmp")
        assertTrue(isCanonicalInstrumentPackDirectory(canonical, manifest))
        assertTrue(!isCanonicalInstrumentPackDirectory(staging, manifest))
    }

}
