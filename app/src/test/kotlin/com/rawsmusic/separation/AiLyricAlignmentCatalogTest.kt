package com.rawsmusic.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLyricAlignmentCatalogTest {
    @Test
    fun legacyCatalogWithoutLyricModelsRemainsCompatible() {
        val json = """
            {
              "schemaVersion": 1,
              "repositoryId": "test-repository",
              "models": []
            }
        """.trimIndent()

        assertTrue(AiSeparationJson.parseLyricAlignmentCatalog(json, "test-repository").isEmpty())
    }

    @Test
    fun lyricOnlyCatalogDoesNotRequireSeparationModels() {
        val json = """
            {
              "schemaVersion": 1,
              "repositoryId": "test-repository",
              "lyricAlignmentModels": []
            }
        """.trimIndent()

        assertTrue(AiSeparationJson.parseCatalog(json, "test-repository").isEmpty())
        assertTrue(AiSeparationJson.parseLyricAlignmentCatalog(json, "test-repository").isEmpty())
    }

    @Test
    fun parsesSignedCtcCatalogEntry() {
        val json = """
            {
              "schemaVersion": 1,
              "repositoryId": "test-repository",
              "models": [],
              "lyricAlignmentModels": [{
                "schemaVersion": 1,
                "id": "rawsmusic.lyric-ctc-lite",
                "name": "Lightweight CTC lyric aligner",
                "version": "1",
                "description": "Small quantized CTC aligner",
                "architecture": "ctc",
                "modelFormat": "onnx",
                "modelFile": "model.onnx",
                "modelSizeBytes": 1024,
                "modelSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "vocabularyFile": "vocab.txt",
                "vocabularySizeBytes": 64,
                "vocabularySha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "sampleRate": 16000,
                "estimatedMemoryMb": 96,
                "minimumAppVersion": "0.9.80",
                "modelDownloadUrls": ["https://github.com/example/rawsmusic/releases/download/v1/model.onnx"],
                "vocabularyDownloadUrls": ["https://github.com/example/rawsmusic/releases/download/v1/vocab.txt"],
                "contract": {
                  "id": "rawsmusic.lyric-ctc-lite",
                  "version": "1",
                  "modelFormat": "onnx",
                  "inputKind": "vocal_mono_pcm_f32",
                  "outputKind": "ctc_frame_log_probs_v1",
                  "sampleRate": 16000,
                  "inputChannels": 1,
                  "requiresTokenIds": false,
                  "maximumTextUnits": 512,
                  "nativeAbi": 1,
                  "alignmentType": "ctc_frame_log_probs",
                  "blankTokenId": 0,
                  "frameStrideMs": 20,
                  "vocabularyFile": "vocab.txt",
                  "quantization": "int8"
                }
              }]
            }
        """.trimIndent()

        val entry = AiSeparationJson.parseLyricAlignmentCatalog(json, "test-repository").single()

        assertEquals("rawsmusic.lyric-ctc-lite", entry.id)
        assertEquals("model.onnx", entry.modelFile)
        assertEquals("vocab.txt", entry.vocabularyFile)
        assertEquals(16_000, entry.contract.sampleRate)
        assertEquals(
            AiLyricForcedAlignmentContract.ALIGNMENT_CTC_FRAME_LOGITS,
            entry.contract.alignmentType,
        )
        assertEquals(
            AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_FRAMES_MELS,
            entry.contract.inputLayout,
        )
        assertEquals(80, entry.contract.melBins)
        assertEquals(512, entry.contract.fftSize)
        assertEquals(160, entry.contract.hopLength)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsModelMirror() {
        val json = validCatalogJson().replace(
            "https://github.com/example/rawsmusic/releases/download/v1/model.onnx",
            "http://example.invalid/model.onnx",
        )

        AiSeparationJson.parseLyricAlignmentCatalog(json, "test-repository")
    }

    private fun validCatalogJson(): String = """
        {
          "schemaVersion": 1,
          "repositoryId": "test-repository",
          "models": [],
          "lyricAlignmentModels": [{
            "schemaVersion": 1,
            "id": "rawsmusic.lyric-ctc-lite",
            "name": "Lightweight CTC lyric aligner",
            "version": "1",
            "description": "Small quantized CTC aligner",
            "architecture": "ctc",
            "modelFormat": "onnx",
            "modelFile": "model.onnx",
            "modelSizeBytes": 1024,
            "modelSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "vocabularyFile": "vocab.txt",
            "vocabularySizeBytes": 64,
            "vocabularySha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "sampleRate": 16000,
            "estimatedMemoryMb": 96,
            "minimumAppVersion": "0.9.80",
            "modelDownloadUrls": ["https://github.com/example/rawsmusic/releases/download/v1/model.onnx"],
            "vocabularyDownloadUrls": ["https://github.com/example/rawsmusic/releases/download/v1/vocab.txt"],
            "contract": {
              "id": "rawsmusic.lyric-ctc-lite",
              "version": "1",
              "modelFormat": "onnx",
              "inputKind": "vocal_mono_pcm_f32",
              "outputKind": "ctc_frame_log_probs_v1",
              "sampleRate": 16000,
              "inputChannels": 1,
              "requiresTokenIds": false,
              "maximumTextUnits": 512,
              "nativeAbi": 1,
              "alignmentType": "ctc_frame_log_probs",
              "blankTokenId": 0,
              "frameStrideMs": 20,
              "vocabularyFile": "vocab.txt",
              "quantization": "int8"
            }
          }]
        }
    """.trimIndent()
}
