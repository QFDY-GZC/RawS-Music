package com.rawsmusic.transcode

import java.nio.ByteBuffer

internal object NativeAudioTranscoder {
    const val ERR_CANCELLED = -70001

    init {
        // Offline conversion owns the encoder-enabled split FFmpeg build. Do not route these
        // JNI calls through rawscoreservice: playback intentionally remains on the existing
        // librawsmusic_ffmpeg.so bridge and must not inherit encoder-build lifecycle changes.
        System.loadLibrary("avutil")
        System.loadLibrary("swresample")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("rawsmusic_audio_transcode")
    }

    fun probe(path: String): AudioTranscodeProbe? {
        val values = nativeProbeAudio(path) ?: return null
        if (values.size < 6) return null
        return AudioTranscodeProbe(
            sampleRateHz = values[0].toInt(),
            bitDepth = values[1].toInt(),
            channels = values[2].toInt(),
            durationMs = values[3],
            bitRate = values[4],
            codecId = values[5].toInt(),
            isDsdSource = values.getOrNull(6) == 1L,
            dsdSampleRateHz = values.getOrNull(7)?.toInt() ?: 0,
            bitDepthMeaningful = values.getOrNull(8)?.let { it == 1L } ?: true,
            hasVideoStream = values.getOrNull(9)?.let { it == 1L } ?: false,
        )
    }

    fun probeDsf(path: String): AudioDsfProbe? {
        val values = nativeProbeDsf(path) ?: return null
        if (values.size < 9) return null
        return AudioDsfProbe(
            sampleRateHz = values[0].toInt(),
            channels = values[1].toInt(),
            bitsPerSample = values[2].toInt(),
            sampleCount = values[3],
            blockSizePerChannel = values[4].toInt(),
            durationMs = values[5],
            fileSize = values[6],
            metadataOffset = values[7],
            dataChunkSize = values[8],
        )
    }

    fun capabilities(): List<AudioTranscodeCapability> =
        nativeListCapabilities().orEmpty().mapNotNull(::parseCapability)

    fun canOpenProfile(
        formatId: String,
        sampleRateHz: Int,
        bitDepth: Int,
        bitRateKbps: Int,
        channels: Int,
    ): Boolean = nativeCanOpenProfile(
        formatId,
        sampleRateHz,
        bitDepth,
        bitRateKbps,
        channels,
    )

    private fun parseCapability(serialized: String): AudioTranscodeCapability? {
        val values = serialized.split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null
                else part.substring(0, separator) to part.substring(separator + 1)
            }
            .toMap()
        val id = values["id"].orEmpty()
        val container = values["container"].orEmpty()
        if (id.isBlank() || container.isBlank()) return null

        val profiles = values["profiles"]
            .orEmpty()
            .split(',')
            .mapNotNull { token ->
                val parts = token.split('/')
                if (parts.size !in 3..4) return@mapNotNull null
                val rate = parts[0].toIntOrNull() ?: return@mapNotNull null
                val bits = parts[1].toIntOrNull() ?: return@mapNotNull null
                val codecId = parts[2].toIntOrNull() ?: return@mapNotNull null
                val bitRateKbps = parts.getOrNull(3)?.toIntOrNull() ?: 0
                AudioTranscodeProfile(rate, bits, codecId, bitRateKbps)
            }
            .toSet()
        val dsdRates = values["dsdRates"]
            .orEmpty()
            .split(',')
            .mapNotNull { token ->
                val multiplier = token.toIntOrNull() ?: return@mapNotNull null
                AudioTranscodeDsdRate.values().firstOrNull { it.multiplier == multiplier }
            }
            .toSet()
        if (profiles.isEmpty() && dsdRates.isEmpty()) return null

        val compressionMin = values["compressionMin"]?.toIntOrNull()
        val compressionMax = values["compressionMax"]?.toIntOrNull()
        return AudioTranscodeCapability(
            id = id,
            container = container,
            lossless = values["lossless"] == "1",
            profiles = profiles,
            compressionRange = if (compressionMin != null && compressionMax != null) {
                compressionMin..compressionMax
            } else {
                null
            },
            dsdRates = dsdRates,
            defaultBitRateKbps = values["defaultBitRate"]?.toIntOrNull(),
        )
    }

    class Session internal constructor(@Volatile private var handle: Long) : AutoCloseable {
        private val lifecycleLock = Any()

        fun transcodeLossless(
            inputPath: String,
            outputPath: String,
            formatId: String,
            sampleRateHz: Int,
            bitDepth: Int,
            flacCompressionLevel: Int,
            autoDither: Boolean,
        ): Int {
            val current = handle
            check(current != 0L) { "AudioTranscodeSession is closed" }
            return nativeTranscodeLossless(
                current,
                inputPath,
                outputPath,
                formatId,
                sampleRateHz,
                bitDepth,
                flacCompressionLevel,
                autoDither,
            )
        }

        fun transcodeLossy(
            inputPath: String,
            outputPath: String,
            formatId: String,
            sampleRateHz: Int,
            bitRateKbps: Int,
        ): Int {
            val current = handle
            check(current != 0L) { "AudioTranscodeSession is closed" }
            return nativeTranscodeLossy(
                current,
                inputPath,
                outputPath,
                formatId,
                sampleRateHz,
                bitRateKbps,
            )
        }

        fun transcodeDsf(
            inputPath: String,
            outputPath: String,
            dsdRate: AudioTranscodeDsdRate,
        ): Int {
            val current = handle
            check(current != 0L) { "AudioTranscodeSession is closed" }
            return nativeTranscodeDsf(
                current,
                inputPath,
                outputPath,
                dsdRate.multiplier,
            )
        }

        fun remuxDsdToDsf(
            inputPath: String,
            outputPath: String,
            dsdRate: AudioTranscodeDsdRate,
        ): Int {
            val current = handle
            check(current != 0L) { "AudioTranscodeSession is closed" }
            return nativeRemuxDsdToDsf(
                current,
                inputPath,
                outputPath,
                dsdRate.multiplier,
            )
        }

        fun openPcm16Reader(
            inputPath: String,
            sampleRateHz: Int,
            channels: Int,
        ): Pcm16Reader? {
            val current = handle
            check(current != 0L) { "AudioTranscodeSession is closed" }
            val readerHandle = nativeCreatePcm16Reader(
                current,
                inputPath,
                sampleRateHz,
                channels,
            )
            return if (readerHandle == 0L) null else Pcm16Reader(readerHandle, channels)
        }

        fun cancel() {
            synchronized(lifecycleLock) {
                val current = handle
                if (current != 0L) nativeCancelSession(current)
            }
        }

        fun progressPermille(): Int = synchronized(lifecycleLock) {
            val current = handle
            if (current == 0L) 0 else nativeProgressPermille(current).coerceIn(0, 1000)
        }

        fun lastErrorDetail(): String? = synchronized(lifecycleLock) {
            val current = handle
            if (current == 0L) null else nativeLastErrorDetail(current)
        }

        fun canonicalPcmDigest(
            inputPath: String,
            sampleRateHz: Int,
            bitDepth: Int,
        ): AudioTranscodePcmDigest? {
            val current = handle
            check(current != 0L) { "AudioTranscodeSession is closed" }
            val serialized = nativeCanonicalPcmDigest(
                current,
                inputPath,
                sampleRateHz,
                bitDepth,
            ) ?: return null
            val separator = serialized.indexOf(';')
            if (separator <= 0 || separator >= serialized.lastIndex) return null
            val sha256 = serialized.substring(0, separator)
            val frames = serialized.substring(separator + 1).toLongOrNull() ?: return null
            if (sha256.length != 64 || frames < 0L) return null
            return AudioTranscodePcmDigest(sha256 = sha256, frames = frames)
        }

        override fun close() {
            synchronized(lifecycleLock) {
                val current = handle
                if (current == 0L) return
                handle = 0L
                nativeDestroySession(current)
            }
        }
    }

    class Pcm16Reader internal constructor(
        @Volatile private var handle: Long,
        val channels: Int,
    ) : AutoCloseable {
        private val lifecycleLock = Any()

        /** Returns PCM frames, 0 at EOF, or a negative native error code. */
        fun readInto(buffer: ByteBuffer, maxFrames: Int): Int = synchronized(lifecycleLock) {
            val current = handle
            check(current != 0L) { "Pcm16Reader is closed" }
            require(buffer.isDirect) { "Pcm16Reader requires a direct ByteBuffer" }
            require(maxFrames > 0) { "maxFrames must be positive" }
            nativeReadPcm16(current, buffer, maxFrames)
        }

        override fun close() {
            synchronized(lifecycleLock) {
                val current = handle
                if (current == 0L) return
                handle = 0L
                nativeDestroyPcm16Reader(current)
            }
        }
    }

    fun createSession(): Session {
        val handle = nativeCreateSession()
        check(handle != 0L) { "Unable to allocate native audio transcode session" }
        return Session(handle)
    }

    private external fun nativeCreateSession(): Long
    private external fun nativeCancelSession(handle: Long)
    private external fun nativeProgressPermille(handle: Long): Int
    private external fun nativeLastErrorDetail(handle: Long): String?
    private external fun nativeDestroySession(handle: Long)
    private external fun nativeTranscodeLossless(
        handle: Long,
        inputPath: String,
        outputPath: String,
        formatId: String,
        sampleRateHz: Int,
        bitDepth: Int,
        flacCompressionLevel: Int,
        autoDither: Boolean,
    ): Int
    private external fun nativeTranscodeLossy(
        handle: Long,
        inputPath: String,
        outputPath: String,
        formatId: String,
        sampleRateHz: Int,
        bitRateKbps: Int,
    ): Int
    private external fun nativeTranscodeDsf(
        handle: Long,
        inputPath: String,
        outputPath: String,
        dsdMultiplier: Int,
    ): Int
    private external fun nativeRemuxDsdToDsf(
        handle: Long,
        inputPath: String,
        outputPath: String,
        dsdMultiplier: Int,
    ): Int
    private external fun nativeCreatePcm16Reader(
        sessionHandle: Long,
        inputPath: String,
        sampleRateHz: Int,
        channels: Int,
    ): Long
    private external fun nativeReadPcm16(
        readerHandle: Long,
        directBuffer: ByteBuffer,
        maxFrames: Int,
    ): Int
    private external fun nativeDestroyPcm16Reader(readerHandle: Long)
    private external fun nativeProbeAudio(inputPath: String): LongArray?
    private external fun nativeProbeDsf(inputPath: String): LongArray?
    private external fun nativeCanonicalPcmDigest(
        handle: Long,
        inputPath: String,
        sampleRateHz: Int,
        bitDepth: Int,
    ): String?
    private external fun nativeCanOpenProfile(
        formatId: String,
        sampleRateHz: Int,
        bitDepth: Int,
        bitRateKbps: Int,
        channels: Int,
    ): Boolean
    private external fun nativeListCapabilities(): Array<String>?
}
