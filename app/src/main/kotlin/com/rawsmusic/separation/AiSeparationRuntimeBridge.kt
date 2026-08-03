package com.rawsmusic.separation

import android.content.Context
import java.io.File
import java.nio.ByteBuffer

data class AiSeparationRuntimeStatus(
    val bridgeLoaded: Boolean,
    val abiVersion: Int,
    val onnxRuntimePresent: Boolean,
    val details: String,
)

data class AiNativeSeparationStats(
    val totalFrames: Long,
    val processedSegments: Int,
    val elapsedMs: Long,
)

interface AiNativeSeparationCallback {
    fun isCancelled(): Boolean
    fun onProgress(processedFrames: Long, totalFrames: Long, segmentIndex: Int, segmentCount: Int)
}

/** Stable JNI boundary between the offline native STFT pipeline and the ORT adapter. */
object AiSeparationRuntimeBridge {
    private val bridgeLoaded = runCatching {
        System.loadLibrary("rawscoreservice")
        true
    }.getOrDefault(false)

    fun status(context: Context): AiSeparationRuntimeStatus {
        if (!bridgeLoaded) {
            return AiSeparationRuntimeStatus(false, 0, false, "rawscoreservice 未加载")
        }
        val runtime = AiOnnxRuntimeSession.runtimeDetails(context)
        return AiSeparationRuntimeStatus(
            bridgeLoaded = true,
            abiVersion = runCatching { nativeBridgeAbiVersion() }.getOrDefault(0),
            onnxRuntimePresent = runtime.isSuccess,
            details = runtime.fold(
                onSuccess = { it },
                onFailure = { it.message ?: it.javaClass.simpleName },
            ),
        )
    }

    fun probeModel(
        context: Context,
        model: File,
        contract: AiSeparationModelContract,
    ): Result<Unit> {
        if (!bridgeLoaded) return Result.failure(IllegalStateException("AI native bridge unavailable"))
        return AiOnnxRuntimeSession.probeModel(context, model, contract)
    }

    fun separatePcm(
        pcmFile: File,
        vocalsFile: File,
        instrumentalFile: File,
        sampleRate: Int,
        segmentSamples: Int,
        overlap: Double,
        contract: AiSeparationModelContract,
        runtimeSession: AiOnnxRuntimeSession,
        callback: AiNativeSeparationCallback,
        denoise: Boolean = false,
    ): Result<AiNativeSeparationStats> {
        if (!bridgeLoaded) return Result.failure(IllegalStateException("AI native bridge unavailable"))
        if (!pcmFile.isFile) return Result.failure(IllegalArgumentException("PCM 输入不存在"))
        val raw = runCatching {
            nativeSeparatePcm(
                pcmPath = pcmFile.absolutePath,
                vocalsPath = vocalsFile.absolutePath,
                instrumentalPath = instrumentalFile.absolutePath,
                sampleRate = sampleRate,
                segmentSamples = segmentSamples,
                overlap = overlap,
                fftSize = contract.fftSize,
                hopLength = contract.hopLength,
                frequencyBins = contract.frequencyBins,
                timeFrames = contract.timeFrames,
                center = contract.center,
                paddingMode = when (contract.paddingMode) {
                    "reflect" -> 1
                    else -> 0
                },
                normalization = when (contract.normalization) {
                    "global_mean_std" -> 1
                    else -> 0
                },
                outputType = when (contract.outputType) {
                    "complex_mask" -> 1
                    else -> 0
                },
                chunkMode = when (contract.chunkMode) {
                    "uvr_mdx_center_trim" -> 1
                    else -> 0
                },
                edgeTrimSamples = contract.edgeTrimSamples,
                compensation = contract.compensation,
                denoise = denoise && contract.supportsDenoise,
                inputBuffer = runtimeSession.inputBuffer,
                outputBuffer = runtimeSession.outputBuffer,
                runner = runtimeSession,
                callback = callback,
            )
        }.getOrElse { return Result.failure(it) }
        if (raw.startsWith("ERROR:")) {
            return Result.failure(IllegalStateException(raw.removePrefix("ERROR:")))
        }
        return runCatching {
            val parts = raw.removePrefix("OK:").split('|')
            require(parts.size == 3) { "native result 格式无效" }
            AiNativeSeparationStats(
                totalFrames = parts[0].toLong(),
                processedSegments = parts[1].toInt(),
                elapsedMs = parts[2].toLong(),
            )
        }
    }

    fun separateSegment(
        mixtureBuffer: ByteBuffer,
        vocalBuffer: ByteBuffer,
        sampleRate: Int,
        segmentSamples: Int,
        contract: AiSeparationModelContract,
        runtimeSession: AiOnnxRuntimeSession,
    ): Result<Unit> {
        if (!bridgeLoaded) {
            return Result.failure(IllegalStateException("AI native bridge unavailable"))
        }
        val raw = runCatching {
            nativeSeparateSegment(
                mixtureBuffer = mixtureBuffer,
                vocalBuffer = vocalBuffer,
                sampleRate = sampleRate,
                segmentSamples = segmentSamples,
                fftSize = contract.fftSize,
                hopLength = contract.hopLength,
                frequencyBins = contract.frequencyBins,
                timeFrames = contract.timeFrames,
                center = contract.center,
                paddingMode = if (contract.paddingMode == "reflect") 1 else 0,
                normalization = if (contract.normalization == "global_mean_std") 1 else 0,
                outputType = if (contract.outputType == "complex_mask") 1 else 0,
                chunkMode = if (contract.chunkMode == "uvr_mdx_center_trim") 1 else 0,
                edgeTrimSamples = contract.edgeTrimSamples,
                compensation = contract.compensation,
                inputBuffer = runtimeSession.inputBuffer,
                outputBuffer = runtimeSession.outputBuffer,
                runner = runtimeSession,
            )
        }.getOrElse { return Result.failure(it) }
        return if (raw == "OK") {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(raw.removePrefix("ERROR:")))
        }
    }

    @JvmStatic private external fun nativeBridgeAbiVersion(): Int

    @JvmStatic
    private external fun nativeSeparateSegment(
        mixtureBuffer: ByteBuffer,
        vocalBuffer: ByteBuffer,
        sampleRate: Int,
        segmentSamples: Int,
        fftSize: Int,
        hopLength: Int,
        frequencyBins: Int,
        timeFrames: Int,
        center: Boolean,
        paddingMode: Int,
        normalization: Int,
        outputType: Int,
        chunkMode: Int,
        edgeTrimSamples: Int,
        compensation: Double,
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        runner: Any,
    ): String

    @JvmStatic
    private external fun nativeSeparatePcm(
        pcmPath: String,
        vocalsPath: String,
        instrumentalPath: String,
        sampleRate: Int,
        segmentSamples: Int,
        overlap: Double,
        fftSize: Int,
        hopLength: Int,
        frequencyBins: Int,
        timeFrames: Int,
        center: Boolean,
        paddingMode: Int,
        normalization: Int,
        outputType: Int,
        chunkMode: Int,
        edgeTrimSamples: Int,
        compensation: Double,
        denoise: Boolean,
        inputBuffer: java.nio.ByteBuffer,
        outputBuffer: java.nio.ByteBuffer,
        runner: Any,
        callback: AiNativeSeparationCallback,
    ): String
}
