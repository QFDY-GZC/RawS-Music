package com.rawsmusic.separation

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.model.CtcFrameScores
import com.rawsmusic.core.common.model.CtcTargetToken
import com.rawsmusic.core.common.model.CtcVocabulary
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Array as JavaArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Loads the small, data-only vocabulary shipped beside a signed CTC model. */
object AiLyricCtcVocabularyLoader {
    fun load(file: File): Result<CtcVocabulary> = runCatching {
        require(file.isFile && file.length() <= MAX_VOCABULARY_BYTES) { "CTC 词表不可读" }
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        require(text.isNotBlank()) { "CTC 词表为空" }
        if (text.startsWith("{")) {
            val json = JsonParser.parseString(text).asJsonObject
            return@runCatching CtcVocabulary(json.entrySet().associate { (token, value) ->
                token to value.asInt
            })
        }
        val values = linkedMapOf<String, Int>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val separator = line.indexOfFirst { it == '\t' || it == ' ' || it == ',' }
            require(separator > 0 && separator < line.lastIndex) {
                "CTC 词表第 ${index + 1} 行格式无效"
            }
            val left = line.substring(0, separator).trim()
            val right = line.substring(separator + 1).trim()
            val pair = when {
                left.toIntOrNull() != null -> right to left.toInt()
                right.toIntOrNull() != null -> left to right.toInt()
                else -> error("CTC 词表第 ${index + 1} 行缺少 token id")
            }
            require(pair.first.isNotEmpty() && pair.second >= 0) {
                "CTC 词表第 ${index + 1} 行无效"
            }
            values.putIfAbsent(pair.first, pair.second)
        }
        CtcVocabulary(values)
    }

    private const val MAX_VOCABULARY_BYTES = 8L * 1024L * 1024L
}

/**
 * ONNX adapter for an audio-only CTC acoustic model.
 *
 * The model is never bundled. The signed repository supplies both the graph and the tensor
 * contract; graph names, rank and fixed dimensions are checked before the first inference.
 */
class AiLyricOnnxPosteriorRunner(
    private val modelFile: File,
    private val outputTokenCount: Int,
) : AiLyricCtcPosteriorRunner {
    override suspend fun infer(
        context: Context,
        vocalStem: File,
        target: List<CtcTargetToken>,
        contract: AiLyricForcedAlignmentContract,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<CtcFrameScores> = withContext(Dispatchers.Default) {
        runCatching {
            check(!isCancelled()) { "CTC analysis cancelled" }
            require(modelFile.isFile) { "CTC 模型文件不存在" }
            require(target.isNotEmpty()) { "CTC 对齐目标为空" }
            require(!contract.requiresTokenIds) {
                "当前 CTC 适配器只接受音频输入，词元由应用侧 Viterbi 对齐"
            }
            val samples = WavMonoReader.readResampled(vocalStem, contract.sampleRate)
            onProgress(0.30f)
            check(!isCancelled()) { "CTC analysis cancelled" }
            val waveform = if (contract.featureType == AiLyricForcedAlignmentContract.FEATURE_WAVEFORM) {
                when (contract.inputKind) {
                    AiLyricForcedAlignmentContract.INPUT_NORMALIZED_WAVEFORM ->
                        normalizeWaveform(samples)
                    else -> samples
                }
            } else {
                FloatArray(0)
            }
            val features = if (waveform.isEmpty()) {
                LogMelFeatureExtractor.extract(samples, contract)
            } else {
                emptyArray()
            }
            onProgress(0.40f)
            check(!isCancelled()) { "CTC analysis cancelled" }
            require(waveform.isNotEmpty() || features.isNotEmpty()) { "CTC 音频特征为空" }
            Log.i(
                TAG,
                "AI_LYRIC_CTC_INFER model=${modelFile.name} samples=${samples.size} " +
                    "input=${contract.featureType} frames=${features.size}",
            )
            CtcOnnxSession.open(
                context = context,
                modelFile = modelFile,
                contract = contract,
                features = features,
                targetCount = target.size,
                outputTokenCount = outputTokenCount,
            ).use { session ->
                if (waveform.isNotEmpty()) {
                    session.inferWaveform(waveform, contract, onProgress, isCancelled)
                } else {
                    session.infer(features, contract, onProgress, isCancelled)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "AI_LYRIC_CTC unavailable reason=${error.message}", error)
        }
    }

    private companion object {
        const val TAG = "AiLyricCtc"

        fun normalizeWaveform(samples: FloatArray): FloatArray {
            if (samples.isEmpty()) return samples
            var mean = 0.0
            samples.forEach { mean += it }
            mean /= samples.size
            var variance = 0.0
            samples.forEach { sample ->
                val delta = sample - mean
                variance += delta * delta
            }
            val standardDeviation = kotlin.math.sqrt(variance / samples.size).coerceAtLeast(1.0e-5)
            return FloatArray(samples.size) { index ->
                ((samples[index] - mean) / standardDeviation).coerceIn(-5.0, 5.0).toFloat()
            }
        }
    }
}

private class CtcOnnxSession private constructor(
    private val environment: Any,
    private val options: Any,
    private val session: Any,
    private val tensorClass: Class<*>,
    private val inputName: String,
    private val outputName: String,
    private val inputShape: LongArray,
    private val outputTokenCount: Int,
) : Closeable {
    fun infer(
        features: Array<FloatArray>,
        contract: AiLyricForcedAlignmentContract,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): CtcFrameScores {
        val chunks = chunkFeatures(features, inputShape, contract)
        val outputValues = ArrayList<Float>()
        var outputFrames = 0
        var tokenCount = 0
        chunks.forEachIndexed { index, chunk ->
            check(!isCancelled()) { "CTC analysis cancelled" }
            val input = flattenFeatures(chunk, contract.inputLayout)
            val result = runOnce(input, shapeFor(chunk.size, contract))
            outputValues += result.values.toList()
            outputFrames += result.frameCount
            tokenCount = max(tokenCount, result.tokenCount)
            onProgress(inferenceProgress(index + 1, chunks.size))
        }
        require(outputFrames > 0 && tokenCount > 1) { "CTC 输出维度无效" }
        return CtcFrameScores(
            frameCount = outputFrames,
            tokenCount = tokenCount,
            values = outputValues.toFloatArray(),
            valuesAreLogProbabilities = false,
        )
    }

    fun inferWaveform(
        samples: FloatArray,
        contract: AiLyricForcedAlignmentContract,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): CtcFrameScores {
        val chunks = chunkWaveform(samples, inputShape, contract)
        val outputValues = ArrayList<Float>()
        var outputFrames = 0
        var tokenCount = 0
        chunks.forEachIndexed { index, chunk ->
            check(!isCancelled()) { "CTC analysis cancelled" }
            val result = runOnce(chunk, shapeFor(chunk.size, contract))
            outputValues += result.values.toList()
            outputFrames += result.frameCount
            tokenCount = max(tokenCount, result.tokenCount)
            onProgress(inferenceProgress(index + 1, chunks.size))
        }
        require(outputFrames > 0 && tokenCount > 1) { "CTC 波形输出维度无效" }
        return CtcFrameScores(
            frameCount = outputFrames,
            tokenCount = tokenCount,
            values = outputValues.toFloatArray(),
            valuesAreLogProbabilities = false,
        )
    }

    private fun runOnce(input: FloatArray, shape: LongArray): CtcFrameScores {
        val createTensor = tensorClass.getMethod(
            "createTensor",
            environment.javaClass,
            FloatBuffer::class.java,
            LongArray::class.java,
        )
        val tensor = createTensor.invoke(
            null,
            environment,
            ByteBuffer.allocateDirect(input.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(input); rewind() },
            shape,
        )
        return try {
            val inputs = linkedMapOf<String, Any>(inputName to tensor)
            val runMethod = session.javaClass.methods.firstOrNull { method ->
                method.name == "run" && method.parameterTypes.contentEquals(arrayOf(Map::class.java))
            } ?: error("ONNX Runtime 缺少 run(Map) 方法")
            val result = runMethod.invoke(session, inputs)
            try {
                val value = result.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                    .invoke(result, 0)
                val outputValue = requireNotNull(value.javaClass.getMethod("getValue").invoke(value)) {
                    "CTC 输出值为空"
                }
                decodeOutput(outputValue)
            } finally {
                closeReflective(result)
            }
        } finally {
            closeReflective(tensor)
        }
    }

    private fun decodeOutput(value: Any): CtcFrameScores {
        val shape = nestedShape(value)
        val flat = ArrayList<Float>()
        flatten(value, flat)
        require(flat.isNotEmpty()) { "CTC 输出为空" }
        val dimensions = shape.toIntArray()
        val batchRemoved = if (dimensions.firstOrNull() == 1) dimensions.drop(1) else dimensions.toList()
        require(batchRemoved.size in 1..2) { "CTC 输出必须是 [T,V] 或 [V,T]" }
        val dims = if (batchRemoved.size == 1) {
            listOf(batchRemoved[0], outputTokenCount)
        } else {
            batchRemoved
        }
        val tokenCount = outputTokenCount.takeIf { it > 1 }
            ?: max(dims[1], dims[0])
        val frameCount = flat.size / tokenCount
        require(frameCount > 0 && flat.size == frameCount * tokenCount) {
            "CTC 输出元素数量与 token 数量不匹配"
        }
        val frameMajor = when {
            dims.size == 2 && dims[1] == tokenCount -> flat.toFloatArray()
            dims.size == 2 && dims[0] == tokenCount -> transpose(flat, dims[0], dims[1])
            else -> error("无法识别 CTC 输出布局")
        }
        return CtcFrameScores(frameCount, tokenCount, frameMajor)
    }

    override fun close() {
        closeReflective(session)
        closeReflective(options)
    }

    companion object {
        private fun inferenceProgress(completed: Int, total: Int): Float =
            0.40f + 0.50f * (completed.toFloat() / total.coerceAtLeast(1).toFloat())

        fun open(
            context: Context,
            modelFile: File,
            contract: AiLyricForcedAlignmentContract,
            features: Array<FloatArray>,
            targetCount: Int,
            outputTokenCount: Int,
        ): CtcOnnxSession {
            AiOnnxRuntimeLoader.ensureLoaded(context).getOrThrow()
            val environmentClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val optionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val tensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
            val environment = requireNotNull(environmentClass.getMethod("getEnvironment").invoke(null)) {
                "ONNX Runtime 环境为空"
            }
            val options = optionsClass.getConstructor().newInstance()
            runCatching {
                optionsClass.getMethod(
                    "setIntraOpNumThreads",
                    Int::class.javaPrimitiveType,
                ).invoke(options, min(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
                optionsClass.getMethod(
                    "setInterOpNumThreads",
                    Int::class.javaPrimitiveType,
                ).invoke(options, 1)
            }
            val session = try {
                environmentClass.getMethod("createSession", String::class.java, optionsClass)
                    .invoke(environment, modelFile.absolutePath, options)
            } catch (error: Throwable) {
                closeReflective(options)
                throw error.cause ?: error
            }
            try {
                val inputInfo = session.javaClass.getMethod("getInputInfo").invoke(session) as Map<*, *>
                val outputInfo = session.javaClass.getMethod("getOutputInfo").invoke(session) as Map<*, *>
                require(inputInfo.size == 1 && outputInfo.size == 1) {
                    "CTC 模型必须是单输入单输出"
                }
                val inputName = inputInfo.keys.single().toString()
                val outputName = outputInfo.keys.single().toString()
                require(contract.inputName == "*" || contract.inputName == inputName) {
                    "CTC 输入张量名不匹配"
                }
                require(contract.outputName == "*" || contract.outputName == outputName) {
                    "CTC 输出张量名不匹配"
                }
                val inputShape = tensorShape(requireNotNull(inputInfo.values.single()))
                val outputShape = tensorShape(requireNotNull(outputInfo.values.single()))
                validateInputShape(inputShape, contract)
                validateOutputShape(outputShape, contract, targetCount, outputTokenCount)
                return CtcOnnxSession(
                    environment = environment,
                    options = options,
                    session = session,
                    tensorClass = tensorClass,
                    inputName = inputName,
                    outputName = outputName,
                    inputShape = inputShape,
                    outputTokenCount = outputTokenCount,
                )
            } catch (error: Throwable) {
                closeReflective(session)
                closeReflective(options)
                throw error.cause ?: error
            }
        }

        private fun tensorShape(nodeInfo: Any): LongArray {
            val info = requireNotNull(nodeInfo.javaClass.getMethod("getInfo").invoke(nodeInfo)) {
                "CTC tensor info 为空"
            }
            require(info.javaClass.name == "ai.onnxruntime.TensorInfo") {
                "CTC 节点不是 Tensor"
            }
            val type = requireNotNull(info.javaClass.getField("type").get(info)).toString()
            require(type == "FLOAT") { "CTC 模型必须输入输出 float32" }
            return info.javaClass.getMethod("getShape").invoke(info) as LongArray
        }

        private fun validateInputShape(
            actual: LongArray,
            contract: AiLyricForcedAlignmentContract,
        ) {
            val expectedRank = when (contract.inputLayout) {
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_FRAMES_MELS,
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_MELS_FRAMES -> 3
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_CHANNEL_MELS_FRAMES -> 4
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_SAMPLES -> 2
                else -> error("不支持的 CTC 输入布局")
            }
            require(actual.size == expectedRank) { "CTC 输入 rank 不匹配" }
            require(actual[0] <= 0L || actual[0] == 1L) { "CTC 输入 batch 必须为 1" }
            when (contract.inputLayout) {
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_SAMPLES -> {
                    require(actual[1] <= 0L || contract.inputFrames <= 0 ||
                        actual[1] == contract.inputFrames.toLong()) {
                        "CTC 波形输入样本数不匹配"
                    }
                }
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_FRAMES_MELS -> {
                    require(actual[2] <= 0L || actual[2] == contract.melBins.toLong()) {
                        "CTC 输入 mel 维度不匹配"
                    }
                }
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_MELS_FRAMES -> {
                    require(actual[1] <= 0L || actual[1] == contract.melBins.toLong()) {
                        "CTC 输入 mel 维度不匹配"
                    }
                }
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_CHANNEL_MELS_FRAMES -> {
                    require(actual[1] <= 0L || actual[1] == 1L) {
                        "CTC 输入声道维度必须为 1"
                    }
                    require(actual[2] <= 0L || actual[2] == contract.melBins.toLong()) {
                        "CTC 输入 mel 维度不匹配"
                    }
                }
            }
        }

        private fun validateOutputShape(
            actual: LongArray,
            contract: AiLyricForcedAlignmentContract,
            targetCount: Int,
            outputTokenCount: Int,
        ) {
            require(actual.size in 2..3) { "CTC 输出 rank 不受支持" }
            val tokenDimension = if (actual.size == 3) {
                when (contract.outputLayout) {
                    AiLyricForcedAlignmentContract.OUTPUT_LAYOUT_BATCH_FRAMES_TOKENS -> actual[2]
                    AiLyricForcedAlignmentContract.OUTPUT_LAYOUT_BATCH_TOKENS_FRAMES -> actual[1]
                    else -> error("不支持的 CTC 输出布局")
                }
            } else {
                outputTokenCount.toLong()
            }
            if (outputTokenCount > 0 && tokenDimension > 0L) {
                require(tokenDimension == outputTokenCount.toLong()) { "CTC 输出 token 数不匹配" }
            }
            require(targetCount <= 16_384) { "CTC 目标过长" }
        }

        private fun chunkFeatures(
            features: Array<FloatArray>,
            inputShape: LongArray,
            contract: AiLyricForcedAlignmentContract,
        ): List<Array<FloatArray>> {
            val fixedFrames = contract.inputFrames.takeIf { it > 0 }
                ?: inputShape.firstOrNull { it > 0L && it != 1L && it != contract.melBins.toLong() }
                    ?.toInt()
                ?: features.size
            require(fixedFrames > 0) { "CTC 输入帧数无效" }
            return features.toList().chunked(fixedFrames).map { chunk ->
                Array(fixedFrames) { index ->
                    chunk.getOrNull(index) ?: FloatArray(contract.melBins)
                }
            }
        }

        private fun chunkWaveform(
            samples: FloatArray,
            inputShape: LongArray,
            contract: AiLyricForcedAlignmentContract,
        ): List<FloatArray> {
            val fixedSamples = contract.inputFrames.takeIf { it > 0 }
                ?: inputShape.firstOrNull { it > 0L && it != 1L }?.toInt()
                ?: samples.size
            require(fixedSamples > 0) { "CTC 波形输入样本数无效" }
            return samples.toList().chunked(fixedSamples).map { chunk ->
                FloatArray(fixedSamples) { index -> chunk.getOrNull(index) ?: 0f }
            }
        }

        private fun shapeFor(frameCount: Int, contract: AiLyricForcedAlignmentContract): LongArray = when (
            contract.inputLayout
        ) {
            AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_SAMPLES ->
                longArrayOf(1L, frameCount.toLong())
            AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_FRAMES_MELS ->
                longArrayOf(1L, frameCount.toLong(), contract.melBins.toLong())
            AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_MELS_FRAMES ->
                longArrayOf(1L, contract.melBins.toLong(), frameCount.toLong())
            AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_CHANNEL_MELS_FRAMES ->
                longArrayOf(1L, 1L, contract.melBins.toLong(), frameCount.toLong())
            else -> error("不支持的 CTC 输入布局")
        }

        private fun flattenFeatures(features: Array<FloatArray>, layout: String): FloatArray {
            val melBins = features.firstOrNull()?.size ?: 0
            val output = FloatArray(features.size * melBins)
            when (layout) {
                AiLyricForcedAlignmentContract.INPUT_LAYOUT_BATCH_FRAMES_MELS -> {
                    var offset = 0
                    features.forEach { frame ->
                        frame.copyInto(output, offset)
                        offset += frame.size
                    }
                }
                else -> {
                    var offset = 0
                    repeat(melBins) { mel ->
                        features.forEach { frame -> output[offset++] = frame[mel] }
                    }
                }
            }
            return output
        }

        private fun nestedShape(value: Any): List<Int> {
            if (!value.javaClass.isArray) return emptyList()
            val length = JavaArray.getLength(value)
            if (length == 0) return listOf(0)
            return listOf(length) + nestedShape(requireNotNull(JavaArray.get(value, 0)))
        }

        private fun flatten(value: Any, output: MutableList<Float>) {
            if (value.javaClass.isArray) {
                repeat(JavaArray.getLength(value)) { index ->
                    flatten(requireNotNull(JavaArray.get(value, index)), output)
                }
            } else if (value is Number) {
                output += value.toFloat()
            } else {
                error("CTC 输出包含不支持的元素类型：${value.javaClass.name}")
            }
        }

        private fun transpose(values: List<Float>, rows: Int, columns: Int): FloatArray {
            val result = FloatArray(values.size)
            var offset = 0
            repeat(columns) { column ->
                repeat(rows) { row -> result[offset++] = values[row * columns + column] }
            }
            return result
        }

        private fun closeReflective(value: Any?) {
            if (value == null) return
            runCatching { value.javaClass.getMethod("close").invoke(value) }
        }
    }
}

private object WavMonoReader {
    fun readResampled(file: File, targetRate: Int): FloatArray = RandomAccessFile(file, "r").use { input ->
        val header = ByteArray(12)
        input.readFully(header)
        require(ascii(header, 0, 4) == "RIFF" && ascii(header, 8, 4) == "WAVE") {
            "CTC 输入不是 RIFF/WAVE"
        }
        var format = 0
        var channels = 0
        var sourceRate = 0
        var bits = 0
        var blockAlign = 0
        var dataOffset = -1L
        var dataSize = 0L
        while (input.filePointer + 8L <= input.length() && (format == 0 || dataOffset < 0L)) {
            val chunk = ByteArray(8)
            input.readFully(chunk)
            val size = uint32(chunk, 4)
            val payload = input.filePointer
            when (ascii(chunk, 0, 4)) {
                "fmt " -> {
                    require(size in 16..4096) { "CTC WAV fmt 区块无效" }
                    val bytes = ByteArray(size.toInt())
                    input.readFully(bytes)
                    format = uint16(bytes, 0)
                    channels = uint16(bytes, 2)
                    sourceRate = uint32(bytes, 4).toInt()
                    blockAlign = uint16(bytes, 12)
                    bits = uint16(bytes, 14)
                }
                "data" -> {
                    dataOffset = payload
                    dataSize = size
                }
            }
            input.seek(payload + size + (size and 1L))
        }
        require(format == 1 || format == 3) { "CTC WAV 只支持 PCM 或 float" }
        require(channels in 1..2 && sourceRate > 0 && bits in setOf(16, 24, 32)) {
            "CTC WAV 音频参数无效"
        }
        require(dataOffset >= 0L && blockAlign > 0L && dataSize >= blockAlign) {
            "CTC WAV 缺少音频数据"
        }
        val sourceFrames = dataSize / blockAlign
        require(sourceFrames <= sourceRate.toLong() * MAX_AUDIO_SECONDS) {
            "CTC 音频过长，请先截取较短片段"
        }
        input.seek(dataOffset)
        val source = FloatArray(sourceFrames.toInt())
        val bytesPerSample = bits / 8
        val frameBytes = ByteArray(blockAlign)
        source.indices.forEach { frame ->
            input.readFully(frameBytes)
            var sum = 0f
            repeat(channels) { channel ->
                sum += decode(frameBytes, channel * bytesPerSample, format, bits)
            }
            source[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        if (sourceRate == targetRate) return@use source
        val targetFrames = (source.size.toLong() * targetRate / sourceRate).toInt().coerceAtLeast(1)
        FloatArray(targetFrames) { index ->
            val position = index.toDouble() * sourceRate / targetRate
            val left = floor(position).toInt().coerceIn(0, source.lastIndex)
            val right = min(left + 1, source.lastIndex)
            val fraction = (position - left).toFloat()
            source[left] * (1f - fraction) + source[right] * fraction
        }
    }

    private fun decode(bytes: ByteArray, offset: Int, format: Int, bits: Int): Float {
        if (format == 3) return Float.fromBits(uint32(bytes, offset).toInt()).takeIf { it.isFinite() } ?: 0f
        return when (bits) {
            16 -> (short(bytes, offset) / 32768f).coerceIn(-1f, 1f)
            24 -> {
                var value = (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16)
                if ((value and 0x800000) != 0) value = value or -0x1000000
                (value / 8_388_608f).coerceIn(-1f, 1f)
            }
            else -> (uint32(bytes, offset).toInt() / 2_147_483_648f).coerceIn(-1f, 1f)
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun short(bytes: ByteArray, offset: Int): Short =
        uint16(bytes, offset).toShort()

    private const val MAX_AUDIO_SECONDS = 30 * 60
}

private object LogMelFeatureExtractor {
    fun extract(samples: FloatArray, contract: AiLyricForcedAlignmentContract): Array<FloatArray> {
        val frameCount = max(1, ceil(max(1, samples.size - contract.hopLength).toDouble() / contract.hopLength).toInt())
        val filters = melFilters(contract)
        val window = FloatArray(contract.windowSize) { index ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * index / contract.windowSize)).toFloat()
        }
        return Array(frameCount) { frame ->
            val real = DoubleArray(contract.fftSize)
            val imaginary = DoubleArray(contract.fftSize)
            val start = frame * contract.hopLength
            repeat(contract.windowSize) { index ->
                val sample = samples.getOrElse(start + index) { 0f }
                real[index] = sample.toDouble() * window[index].toDouble()
            }
            fft(real, imaginary)
            val power = DoubleArray(contract.fftSize / 2 + 1) { bin ->
                (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) / contract.fftSize
            }
            FloatArray(contract.melBins) { mel ->
                var energy = 1.0e-10
                filters[mel].forEach { (bin, weight) -> energy += power[bin] * weight }
                log10(energy).toFloat()
            }
        }
    }

    private fun melFilters(contract: AiLyricForcedAlignmentContract): Array<List<Pair<Int, Double>>> {
        val minMel = hzToMel(20.0)
        val maxMel = hzToMel(contract.sampleRate / 2.0)
        val points = DoubleArray(contract.melBins + 2) { index ->
            melToHz(minMel + (maxMel - minMel) * index / (contract.melBins + 1))
        }
        val bins = points.map { floor((contract.fftSize + 1) * it / contract.sampleRate).toInt() }
        return Array(contract.melBins) { index ->
            val left = bins[index]
            val center = max(left + 1, bins[index + 1])
            val right = max(center + 1, bins[index + 2])
            buildList {
                for (bin in left until center) add(bin.coerceIn(0, contract.fftSize / 2) to (bin - left).toDouble() / (center - left))
                for (bin in center until right) add(bin.coerceIn(0, contract.fftSize / 2) to (right - bin).toDouble() / (right - center))
            }
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until real.size) {
            var bit = real.size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realValue = real[i]
                real[i] = real[j]
                real[j] = realValue
            }
        }
        var length = 2
        while (length <= real.size) {
            val angle = -2.0 * Math.PI / length
            val phaseReal = cos(angle)
            val phaseImaginary = sin(angle)
            var block = 0
            while (block < real.size) {
                var currentReal = 1.0
                var currentImaginary = 0.0
                repeat(length / 2) { offset ->
                    val even = block + offset
                    val odd = even + length / 2
                    val transformedReal = currentReal * real[odd] - currentImaginary * imaginary[odd]
                    val transformedImaginary = currentReal * imaginary[odd] + currentImaginary * real[odd]
                    real[odd] = real[even] - transformedReal
                    imaginary[odd] = imaginary[even] - transformedImaginary
                    real[even] += transformedReal
                    imaginary[even] += transformedImaginary
                    val nextReal = currentReal * phaseReal - currentImaginary * phaseImaginary
                    currentImaginary = currentReal * phaseImaginary + currentImaginary * phaseReal
                    currentReal = nextReal
                }
                block += length
            }
            length = length shl 1
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
