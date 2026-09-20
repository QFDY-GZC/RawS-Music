package com.rawsmusic.separation

import org.junit.Assert.assertEquals
import org.junit.Test

class AiLyricForcedAlignmentContractTest {
    @Test
    fun defaultContractUsesExplicitWordTimestampBoundary() {
        val contract = AiLyricForcedAlignmentContract.default()

        assertEquals(AiLyricForcedAlignmentContract.INPUT_VOCAL_PCM, contract.inputKind)
        assertEquals(AiLyricForcedAlignmentContract.OUTPUT_WORD_TIMESTAMPS, contract.outputKind)
        assertEquals(1, contract.inputChannels)
        assertEquals(true, contract.requiresTokenIds)
    }

    @Test
    fun lightweightContractUsesQuantizedCtcFrameScores() {
        val contract = AiLyricForcedAlignmentContract.lightweightCtc()

        assertEquals(
            AiLyricForcedAlignmentContract.ALIGNMENT_CTC_FRAME_LOGITS,
            contract.alignmentType,
        )
        assertEquals(AiLyricForcedAlignmentContract.OUTPUT_CTC_FRAME_LOGITS, contract.outputKind)
        assertEquals("int8", contract.quantization)
        assertEquals(16_000, contract.sampleRate)
    }

    @Test
    fun fixedThirtySecondWaveformWindowIsAccepted() {
        val contract = AiLyricForcedAlignmentContract(
            id = "charsiu.en.v1",
            version = "1.0.0",
            modelFormat = "onnx",
            inputKind = AiLyricForcedAlignmentContract.INPUT_NORMALIZED_WAVEFORM,
            outputKind = AiLyricForcedAlignmentContract.OUTPUT_PHONE_FRAME_LOGITS,
            sampleRate = 16_000,
            language = "en",
            textTokenizer = AiLyricForcedAlignmentContract.TEXT_TOKENIZER_CHARSIIU_PHONE,
            requiresTokenIds = false,
            maximumTextUnits = 4_096,
            alignmentType = AiLyricForcedAlignmentContract.ALIGNMENT_PHONE_FRAME_LOGITS,
            frameStrideMs = 10,
            vocabularyFile = "en_vocab.json",
            quantization = "int8",
            inputLayout = AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_SAMPLES,
            outputLayout = AiLyricForcedAlignmentContract.OUTPUT_LAYOUT_BATCH_TOKENS_FRAMES,
            inputName = "input_values",
            outputName = "logits",
            featureType = AiLyricForcedAlignmentContract.FEATURE_WAVEFORM,
            melBins = 0,
            fftSize = 0,
            hopLength = 0,
            windowSize = 0,
            inputFrames = 480_000,
            outputTokenCount = 42,
        )

        assertEquals(480_000, contract.inputFrames)
    }

    @Test(expected = IllegalArgumentException::class)
    fun separationSpectrogramInputCannotPretendToBeForcedAlignment() {
        AiLyricForcedAlignmentContract(
            id = "separator-model",
            version = "1",
            modelFormat = "onnx",
            inputKind = "complex_stft",
            outputKind = AiLyricForcedAlignmentContract.OUTPUT_WORD_TIMESTAMPS,
            sampleRate = 44_100,
        )
    }
}
