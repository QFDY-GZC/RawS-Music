package com.rawsmusic.separation

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.model.CtcFrameScores
import com.rawsmusic.core.common.model.CtcForcedAligner
import com.rawsmusic.core.common.model.CtcForcedAlignmentPolicy
import com.rawsmusic.core.common.model.CtcLyricTokenizer
import com.rawsmusic.core.common.model.CtcVocabulary
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricTimingCorrection
import com.rawsmusic.core.common.model.previewPhoneWordRetiming
import com.rawsmusic.core.common.model.previewCtcWordRetiming
import java.io.File

/**
 * Describes the contract a real lyric forced-alignment model must satisfy.
 *
 * The separation models currently installed by RawS Music do not satisfy this contract: they
 * accept a complex STFT tensor and return a vocal mask. A forced-aligner must consume the vocal
 * signal together with lyric tokens and return word timestamps, so it is deliberately kept as a
 * separate contract instead of being inferred from [AiSeparationModelContract].
 */
data class AiLyricForcedAlignmentContract(
    val id: String,
    val version: String,
    val modelFormat: String,
    val inputKind: String,
    val outputKind: String,
    val sampleRate: Int,
    val language: String = "",
    val textTokenizer: String = TEXT_TOKENIZER_GRAPHEME,
    val inputChannels: Int = 1,
    val requiresTokenIds: Boolean = true,
    val maximumTextUnits: Int = 1024,
    val nativeAbi: Int = 1,
    val alignmentType: String = ALIGNMENT_WORD_TIMESTAMPS,
    val blankTokenId: Int = 0,
    val frameStrideMs: Int = 20,
    val vocabularyFile: String = "",
    val quantization: String = "fp16",
    val inputLayout: String = INPUT_LAYOUT_BATCH_FRAMES_MELS,
    val outputLayout: String = OUTPUT_LAYOUT_BATCH_FRAMES_TOKENS,
    val inputName: String = "*",
    val outputName: String = "*",
    val featureType: String = FEATURE_LOG_MEL,
    val melBins: Int = 80,
    val fftSize: Int = 512,
    val hopLength: Int = 160,
    val windowSize: Int = 400,
    val inputFrames: Int = 0,
    val outputTokenCount: Int = 0,
) {
    init {
        require(id.matches(SAFE_ID)) { "歌词对齐模型 ID 无效" }
        require(version.matches(SAFE_VERSION)) { "歌词对齐模型版本无效" }
        require(modelFormat in SUPPORTED_FORMATS) { "歌词对齐模型格式不受支持" }
        require(inputKind in SUPPORTED_INPUT_KINDS) { "歌词对齐模型输入必须是单声道人声 PCM" }
        require(outputKind in SUPPORTED_OUTPUTS) { "歌词对齐模型输出契约不受支持" }
        require(sampleRate in 8_000..192_000) { "歌词对齐模型采样率无效" }
        require(inputChannels == 1) { "歌词对齐模型必须使用单声道人声输入" }
        require(maximumTextUnits in 1..16_384) { "歌词对齐模型文本上限无效" }
        require(nativeAbi > 0) { "歌词对齐模型 native ABI 无效" }
        require(alignmentType in SUPPORTED_ALIGNMENT_TYPES) { "歌词对齐模型对齐类型不受支持" }
        require(blankTokenId >= 0) { "歌词对齐模型 blank token id 无效" }
        require(frameStrideMs in 1..1_000) { "歌词对齐模型帧步长无效" }
        require(vocabularyFile.length <= 256) { "歌词对齐模型词表路径过长" }
        require(quantization in SUPPORTED_QUANTIZATIONS) { "歌词对齐模型量化类型不受支持" }
        require(inputLayout in SUPPORTED_INPUT_LAYOUTS) { "歌词对齐模型输入布局不受支持" }
        require(outputLayout in SUPPORTED_OUTPUT_LAYOUTS) { "歌词对齐模型输出布局不受支持" }
        require(inputName == "*" || inputName.matches(SAFE_TENSOR_NAME)) { "歌词对齐模型输入张量名无效" }
        require(outputName == "*" || outputName.matches(SAFE_TENSOR_NAME)) { "歌词对齐模型输出张量名无效" }
        require(featureType in SUPPORTED_FEATURE_TYPES) { "歌词对齐模型特征类型不受支持" }
        require(language.length <= 16) { "歌词对齐模型语言标识过长" }
        require(textTokenizer in SUPPORTED_TEXT_TOKENIZERS) { "歌词对齐模型文本分词器不受支持" }
        if (featureType == FEATURE_WAVEFORM) {
            require(inputLayout == INPUT_LAYOUT_BATCH_SAMPLES) { "波形输入必须使用 BS 布局" }
            require(melBins == 0 && fftSize == 0 && hopLength == 0 && windowSize == 0) {
                "波形输入不应声明梅尔参数"
            }
        } else {
            require(melBins in 8..256) { "歌词对齐模型梅尔频段数无效" }
            require(fftSize in 128..32_768 && fftSize % 2 == 0) { "歌词对齐模型 FFT 大小无效" }
            require(hopLength in 1..fftSize) { "歌词对齐模型 hop 长度无效" }
            require(windowSize in 1..fftSize) { "歌词对齐模型窗长度无效" }
        }
        // Feature models express this value in spectrogram frames, while waveform models
        // express it in PCM samples. Charsiu's 30-second 16 kHz window is 480,000 samples.
        val maxInputFrames = if (featureType == FEATURE_WAVEFORM) {
            MAX_WAVEFORM_INPUT_SAMPLES
        } else {
            MAX_FEATURE_INPUT_FRAMES
        }
        require(inputFrames in 0..maxInputFrames) { "歌词对齐模型输入帧数无效" }
        require(outputTokenCount in 0..16_384) { "歌词对齐模型输出词表大小无效" }
    }

    companion object {
        const val INPUT_VOCAL_PCM = "vocal_mono_pcm_f32"
        const val INPUT_NORMALIZED_WAVEFORM = "normalized_mono_waveform_f32"
        const val INPUT_RAW_WAVEFORM = "raw_mono_waveform_f32"
        const val OUTPUT_WORD_TIMESTAMPS = "word_timestamps_v1"
        const val OUTPUT_CTC_FRAME_LOGITS = "ctc_frame_log_probs_v1"
        const val OUTPUT_PHONE_FRAME_LOGITS = "phone_frame_logits_v1"
        const val OUTPUT_PHONE_CTC_LOGITS = "phone_ctc_logits_v1"
        const val ALIGNMENT_WORD_TIMESTAMPS = "word_timestamps"
        const val ALIGNMENT_CTC_FRAME_LOGITS = "ctc_frame_log_probs"
        const val ALIGNMENT_PHONE_FRAME_LOGITS = "phone_frame_logits"
        const val ALIGNMENT_PHONE_CTC_LOGITS = "phone_ctc_logits"
        const val INPUT_LAYOUT_BATCH_FRAMES_MELS = "BTF"
        const val INPUT_LAYOUT_BATCH_MELS_FRAMES = "BFT"
        const val INPUT_LAYOUT_BATCH_CHANNEL_MELS_FRAMES = "B1FT"
        const val INPUT_LAYOUT_BATCH_SAMPLES = "BS"
        const val OUTPUT_LAYOUT_BATCH_FRAMES_TOKENS = "BFT"
        const val OUTPUT_LAYOUT_BATCH_TOKENS_FRAMES = "BTF"
        const val FEATURE_LOG_MEL = "log_mel_f32"
        const val FEATURE_WAVEFORM = "waveform_f32"
        const val TEXT_TOKENIZER_GRAPHEME = "grapheme"
        const val TEXT_TOKENIZER_CHARSIIU_PHONE = "charsiu_phone"

        fun lightweightCtc(
            id: String = "rawsmusic.lyric-ctc-lite",
            version: String = "1",
        ): AiLyricForcedAlignmentContract = AiLyricForcedAlignmentContract(
            id = id,
            version = version,
            modelFormat = "onnx",
            inputKind = INPUT_VOCAL_PCM,
            outputKind = OUTPUT_CTC_FRAME_LOGITS,
            sampleRate = 16_000,
            requiresTokenIds = false,
            maximumTextUnits = 512,
            alignmentType = ALIGNMENT_CTC_FRAME_LOGITS,
            blankTokenId = 0,
            frameStrideMs = 20,
            vocabularyFile = "vocab.txt",
            quantization = "int8",
        )

        fun default(id: String = "uninstalled"): AiLyricForcedAlignmentContract =
            AiLyricForcedAlignmentContract(
                id = id,
                version = "0",
                modelFormat = "onnx",
                inputKind = INPUT_VOCAL_PCM,
                outputKind = OUTPUT_WORD_TIMESTAMPS,
                sampleRate = 44_100,
            )

        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,96}")
        private val SAFE_VERSION = Regex("[A-Za-z0-9._-]{1,32}")
        private val SUPPORTED_FORMATS = setOf("onnx", "ort")
        private val SUPPORTED_ALIGNMENT_TYPES = setOf(
            ALIGNMENT_WORD_TIMESTAMPS,
            ALIGNMENT_CTC_FRAME_LOGITS,
            ALIGNMENT_PHONE_FRAME_LOGITS,
            ALIGNMENT_PHONE_CTC_LOGITS,
        )
        private val SUPPORTED_OUTPUTS = setOf(
            OUTPUT_WORD_TIMESTAMPS,
            OUTPUT_CTC_FRAME_LOGITS,
            OUTPUT_PHONE_FRAME_LOGITS,
            OUTPUT_PHONE_CTC_LOGITS,
        )
        private val SUPPORTED_QUANTIZATIONS = setOf("fp32", "fp16", "int8")
        private val SUPPORTED_INPUT_LAYOUTS = setOf(
            INPUT_LAYOUT_BATCH_FRAMES_MELS,
            INPUT_LAYOUT_BATCH_MELS_FRAMES,
            INPUT_LAYOUT_BATCH_CHANNEL_MELS_FRAMES,
            INPUT_LAYOUT_BATCH_SAMPLES,
        )
        private val SUPPORTED_OUTPUT_LAYOUTS = setOf(
            OUTPUT_LAYOUT_BATCH_FRAMES_TOKENS,
            OUTPUT_LAYOUT_BATCH_TOKENS_FRAMES,
        )
        private val SUPPORTED_FEATURE_TYPES = setOf(FEATURE_LOG_MEL, FEATURE_WAVEFORM)
        private val SUPPORTED_INPUT_KINDS = setOf(
            INPUT_VOCAL_PCM,
            INPUT_NORMALIZED_WAVEFORM,
            INPUT_RAW_WAVEFORM,
        )
        private val SUPPORTED_TEXT_TOKENIZERS = setOf(
            TEXT_TOKENIZER_GRAPHEME,
            TEXT_TOKENIZER_CHARSIIU_PHONE,
        )
        private val SAFE_TENSOR_NAME = Regex("[^\\u0000\\r\\n]{1,256}")
        private const val MAX_FEATURE_INPUT_FRAMES = 65_536
        private const val MAX_WAVEFORM_INPUT_SAMPLES = 4_000_000
    }
}

/** A model-specific inference boundary; the CTC algorithm remains model/runtime agnostic. */
fun interface AiLyricCtcPosteriorRunner {
    suspend fun infer(
        context: Context,
        vocalStem: File,
        target: List<com.rawsmusic.core.common.model.CtcTargetToken>,
        contract: AiLyricForcedAlignmentContract,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<CtcFrameScores>
}

/**
 * Adapter for a lightweight CTC model once its ONNX input/feature extractor is installed.
 *
 * Keeping the runner injected prevents an unverified model shape from being silently used. The
 * app can expose this provider only after the model manifest and vocabulary have been validated.
 */
class CtcLyricForcedAlignmentProvider(
    private val vocabulary: CtcVocabulary,
    private val posteriorRunner: AiLyricCtcPosteriorRunner,
) : AiLyricForcedAlignmentProvider {
    override val providerId: String = "ctc-lite"

    override fun availability(
        context: Context,
        contract: AiLyricForcedAlignmentContract,
    ): AiLyricAlignmentAvailability = when {
        contract.alignmentType !in setOf(
            AiLyricForcedAlignmentContract.ALIGNMENT_CTC_FRAME_LOGITS,
            AiLyricForcedAlignmentContract.ALIGNMENT_PHONE_FRAME_LOGITS,
            AiLyricForcedAlignmentContract.ALIGNMENT_PHONE_CTC_LOGITS,
        ) ->
            AiLyricAlignmentAvailability.CONTRACT_UNSUPPORTED
        vocabulary.tokenIds.isEmpty() -> AiLyricAlignmentAvailability.MODEL_NOT_INSTALLED
        else -> AiLyricAlignmentAvailability.AVAILABLE
    }

    override suspend fun preview(
        context: Context,
        result: AiSeparationResult,
        vocalStem: File,
        lyrics: LyricData,
        originalLyricHash: String,
        contract: AiLyricForcedAlignmentContract,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<LyricTimingCorrection>? {
        if (contract.alignmentType !in setOf(
                AiLyricForcedAlignmentContract.ALIGNMENT_CTC_FRAME_LOGITS,
                AiLyricForcedAlignmentContract.ALIGNMENT_PHONE_FRAME_LOGITS,
                AiLyricForcedAlignmentContract.ALIGNMENT_PHONE_CTC_LOGITS,
            )
        ) {
            return null
        }
        return runCatching {
            onProgress(0.20f)
            check(!isCancelled()) { "CTC analysis cancelled" }
            val phoneTarget = if (
                contract.textTokenizer == AiLyricForcedAlignmentContract.TEXT_TOKENIZER_CHARSIIU_PHONE
            ) {
                AiLyricPhoneTokenizer.tokenize(
                    lyrics = lyrics,
                    vocabulary = vocabulary,
                    language = contract.language,
                    maximumTextUnits = contract.maximumTextUnits,
                ).getOrThrow()
            } else {
                null
            }
            val target = phoneTarget?.tokens ?: CtcLyricTokenizer.tokenize(
                lyrics = lyrics,
                vocabulary = vocabulary,
                maximumTextUnits = contract.maximumTextUnits,
            ).getOrThrow()
            onProgress(0.24f)
            val scores = posteriorRunner.infer(
                context = context,
                vocalStem = vocalStem,
                target = target,
                contract = contract,
                onProgress = onProgress,
                isCancelled = isCancelled,
            ).getOrThrow()
            check(!isCancelled()) { "CTC analysis cancelled" }
            onProgress(0.92f)
            val analysisDurationMs = result.sourceDurationMs.takeIf { it > 0L }
                ?: (result.totalFrames * 1_000L / result.sampleRate.coerceAtLeast(1))
            val effectiveFrameDurationMs = CtcForcedAligner.effectiveFrameDurationMs(
                scores = scores,
                durationMs = analysisDurationMs,
                fallbackMs = contract.frameStrideMs.toLong(),
            )
            Log.i(
                "AiLyricCtc",
                "AI_LYRIC_CTC_TIMING frames=${scores.frameCount} durationMs=$analysisDurationMs " +
                    "declaredStrideMs=${contract.frameStrideMs} effectiveStrideMs=$effectiveFrameDurationMs",
            )
            val alignmentPolicy = CtcForcedAlignmentPolicy(
                blankTokenId = contract.blankTokenId,
                frameDurationMs = effectiveFrameDurationMs,
            )
            // Keep one continuous Viterbi path for the whole song. Per-line hard windows force
            // the first/last token to a window edge when a vocal phrase crosses a lyric boundary.
            // That produces the exact symptoms this preview must avoid: late line starts and
            // prematurely ended tails.
            val aligned = CtcForcedAligner.align(
                scores = scores,
                target = target,
                policy = alignmentPolicy,
            )
            if (phoneTarget != null) {
                lyrics.previewPhoneWordRetiming(
                    alignedTokens = aligned,
                    wordTexts = phoneTarget.wordTexts,
                    sourceIdentity = result.id,
                    originalLyricHash = originalLyricHash,
                    correctionId = "phone:${result.id}:$originalLyricHash",
                    durationMs = analysisDurationMs,
                    analyzerVersion = "${contract.id}:${contract.version}",
                )
            } else {
                lyrics.previewCtcWordRetiming(
                    alignedTokens = aligned,
                    sourceIdentity = result.id,
                    originalLyricHash = originalLyricHash,
                    correctionId = "ctc:${result.id}:$originalLyricHash",
                    durationMs = analysisDurationMs,
                    analyzerVersion = "${contract.id}:${contract.version}",
                )
            }
                .also { onProgress(1f) }
        }
    }
}

enum class AiLyricAlignmentAvailability {
    AVAILABLE,
    MODEL_NOT_INSTALLED,
    RUNTIME_UNAVAILABLE,
    CONTRACT_UNSUPPORTED,
}

/**
 * Optional provider for a true text-aware forced aligner.
 *
 * Returning null from [preview] means that the provider is not applicable. The caller reports
 * that word alignment is unavailable instead of falling back to activity-energy interpolation.
 * Returning a failed Result means a compatible provider was selected but failed, which must
 * remain visible to the caller rather than silently changing the algorithm.
 */
interface AiLyricForcedAlignmentProvider {
    val providerId: String

    fun availability(
        context: Context,
        contract: AiLyricForcedAlignmentContract,
    ): AiLyricAlignmentAvailability

    suspend fun preview(
        context: Context,
        result: AiSeparationResult,
        vocalStem: File,
        lyrics: LyricData,
        originalLyricHash: String,
        contract: AiLyricForcedAlignmentContract,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<LyricTimingCorrection>?
}

/** No forced-alignment model is bundled or assumed. */
object UnavailableAiLyricForcedAlignmentProvider : AiLyricForcedAlignmentProvider {
    override val providerId: String = "none"

    override fun availability(
        context: Context,
        contract: AiLyricForcedAlignmentContract,
    ): AiLyricAlignmentAvailability = AiLyricAlignmentAvailability.MODEL_NOT_INSTALLED

    override suspend fun preview(
        context: Context,
        result: AiSeparationResult,
        vocalStem: File,
        lyrics: LyricData,
        originalLyricHash: String,
        contract: AiLyricForcedAlignmentContract,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<LyricTimingCorrection>? = null
}
