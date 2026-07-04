package com.rawsmusic.core.common.taglib

import android.util.Log

/**
 * TagLib 全格式元数据解析桥接。
 * 支持所有 TagLib 支持的格式：MP3, FLAC, OGG, M4A, WMA, APE, WAV, AIFF, DSD, WavPack, etc.
 */
object TagLibBridge {
    private const val TAG = "TagLibBridge"
    private var loaded = false

    init {
        try {
            System.loadLibrary("rawsmusic_taglib_full")
            loaded = true
            Log.d(TAG, "TagLib full native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load TagLib full native library", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    fun isSupported(filePath: String): Boolean {
        if (!loaded) return false
        return nativeIsSupported(filePath)
    }

    fun readMetadata(filePath: String): Map<String, String> {
        if (!loaded) return emptyMap()
        return nativeReadMetadata(filePath) ?: emptyMap()
    }

    fun readTags(filePath: String): Map<String, String> {
        val metadata = readMetadata(filePath)
        val audioProps = setOf(
            "sample_rate", "channels", "bits_per_sample", "bit_rate",
            "duration_ms", "duration", "sample_frames", "format_code",
            "format_name", "codec_name"
        )
        return metadata.filterKeys { it !in audioProps }
    }

    fun readAudioProperties(filePath: String): Map<String, String> {
        val metadata = readMetadata(filePath)
        val audioProps = setOf(
            "sample_rate", "channels", "bits_per_sample", "bit_rate",
            "duration_ms", "duration", "sample_frames", "format_code",
            "format_name", "codec_name"
        )
        return metadata.filterKeys { it in audioProps }
    }

    // 兼容旧接口
    fun isWavFile(filePath: String): Boolean {
        if (!loaded) return false
        return filePath.endsWith(".wav", ignoreCase = true) && isSupported(filePath)
    }

    fun readWavMetadata(filePath: String): Map<String, String> = readMetadata(filePath)

    private external fun nativeReadMetadata(path: String): Map<String, String>?
    private external fun nativeIsSupported(path: String): Boolean
}
