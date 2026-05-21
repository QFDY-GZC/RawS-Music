package com.rawsmusic.core.common.taglib

import android.util.Log

/**
 * TagLib 风格的 WAV 元数据解析桥接。
 * 专门用于读取 WAV 文件的 RIFF INFO 块和 ID3v2 标签。
 * 
 * 基于 TagLib 的 RIFF::WAV::File 和 RIFF::Info::Tag 实现，
 * 提供比 FFmpeg 更全面的 WAV 元数据解析能力。
 */
object TagLibBridge {
    private const val TAG = "TagLibBridge"
    private var loaded = false

    init {
        try {
            System.loadLibrary("rawsmusic_taglib")
            loaded = true
            Log.d(TAG, "TagLib native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load TagLib native library", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    /**
     * 检查文件是否为 WAV 格式。
     * 通过读取文件头验证 RIFF/WAVE 签名。
     */
    fun isWavFile(filePath: String): Boolean {
        if (!loaded) return false
        return nativeIsWavFile(filePath)
    }

    /**
     * 读取 WAV 文件的完整元数据。
     * 包括 RIFF INFO 块、ID3v2 标签和音频属性。
     * 
     * @param filePath WAV 文件路径
     * @return 元数据键值对，失败返回空 Map
     */
    fun readWavMetadata(filePath: String): Map<String, String> {
        if (!loaded) {
            Log.w(TAG, "readWavMetadata: Native library not loaded")
            return emptyMap()
        }
        return nativeReadWavMetadata(filePath) ?: emptyMap()
    }

    /**
     * 读取 WAV 文件的标签信息。
     * 仅返回标签字段，不包括音频属性。
     */
    fun readWavTags(filePath: String): Map<String, String> {
        val metadata = readWavMetadata(filePath)
        val audioProps = setOf(
            "sample_rate", "channels", "bits_per_sample", "bit_rate",
            "duration_ms", "duration", "sample_frames", "format_code",
            "format_name", "codec_name"
        )
        return metadata.filterKeys { it !in audioProps }
    }

    /**
     * 读取 WAV 文件的音频属性。
     * 仅返回音频属性字段，不包括标签信息。
     */
    fun readWavAudioProperties(filePath: String): Map<String, String> {
        val metadata = readWavMetadata(filePath)
        val audioProps = setOf(
            "sample_rate", "channels", "bits_per_sample", "bit_rate",
            "duration_ms", "duration", "sample_frames", "format_code",
            "format_name", "codec_name"
        )
        return metadata.filterKeys { it in audioProps }
    }

    // JNI native methods
    private external fun nativeReadWavMetadata(path: String): Map<String, String>?
    private external fun nativeIsWavFile(path: String): Boolean
}
