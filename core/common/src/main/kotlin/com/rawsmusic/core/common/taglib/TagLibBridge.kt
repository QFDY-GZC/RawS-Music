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
            System.loadLibrary("rawscoreservice")
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

    /**
     * Project-style artwork path: extract embedded art in native code into a cache file, then let
     * BitmapProvider decode that file with target-size sampling. This avoids moving the full
     * embedded picture through MediaMetadataRetriever.embeddedPicture as a Java byte[].
     */
    fun extractEmbeddedArtworkToFile(filePath: String, outputPath: String): Boolean {
        if (!loaded) return false
        if (filePath.isBlank() || outputPath.isBlank()) return false
        return try {
            nativeExtractEmbeddedArtworkToFile(filePath, outputPath)
        } catch (e: Throwable) {
            Log.d(TAG, "extractEmbeddedArtworkToFile failed: ${filePath.takeLast(80)}, ${e.message}")
            false
        }
    }

    /** Replaces the front cover while preserving all other tags and audio frames. */
    fun writeEmbeddedArtwork(filePath: String, artworkPath: String, mimeType: String): Boolean {
        if (!loaded || filePath.isBlank() || artworkPath.isBlank() || mimeType.isBlank()) return false
        return try {
            nativeWriteEmbeddedArtwork(filePath, artworkPath, mimeType)
        } catch (e: Throwable) {
            Log.e(TAG, "writeEmbeddedArtwork failed for $filePath", e)
            false
        }
    }

    /** Writes tags without remuxing or touching the encoded audio frames. */
    fun writeMetadata(filePath: String, metadata: Map<String, String>): Boolean {
        if (!loaded || filePath.isBlank()) return false
        return try {
            nativeWriteMetadata(filePath, metadata.keys.toTypedArray(), metadata.values.toTypedArray())
        } catch (e: Throwable) {
            Log.e(TAG, "writeMetadata failed for $filePath", e)
            false
        }
    }


    /**
     * Lossless textual property view for offline format conversion. Unlike [readMetadata], this
     * keeps unknown keys and repeated values instead of collapsing them into Map<String, String>.
     */
    fun readAllTextProperties(filePath: String): Map<String, List<String>> {
        if (!loaded || filePath.isBlank()) return emptyMap()
        val flattened = try {
            nativeReadAllTextProperties(filePath)
        } catch (e: Throwable) {
            Log.e(TAG, "readAllTextProperties failed for $filePath", e)
            null
        } ?: return emptyMap()

        val result = LinkedHashMap<String, MutableList<String>>()
        var index = 0
        while (index + 1 < flattened.size) {
            val key = flattened[index]
            val value = flattened[index + 1]
            if (!key.isNullOrBlank() && value != null) {
                result.getOrPut(key) { mutableListOf() }.add(value)
            }
            index += 2
        }
        return result.mapValues { (_, values) -> values.toList() }
    }

    /**
     * Reads the conversion-relevant metadata surface before audio encoding starts. Opaque objects
     * that TagLib cannot represent through PropertyMap/PICTURE are counted so STRICT can fail
     * before spending time on the encoder.
     */
    fun inspectMetadataForTranscode(sourcePath: String): TranscodeMetadataSourceInfo {
        if (!loaded || sourcePath.isBlank()) return TranscodeMetadataSourceInfo.Unsupported
        return try {
            TranscodeMetadataSourceInfo.parse(nativeInspectMetadataForTranscode(sourcePath))
        } catch (e: Throwable) {
            Log.e(TAG, "inspectMetadataForTranscode failed: $sourcePath", e)
            TranscodeMetadataSourceInfo.Unsupported
        }
    }

    /**
     * Format-aware migration used after audio encoding and before the partial output is committed.
     * Unsupported metadata is reported; it is never silently treated as a successful strict copy.
     */
    fun migrateMetadataForTranscode(
        sourcePath: String,
        targetPath: String,
        encoderLabel: String? = null,
    ): TranscodeMetadataReport {
        if (!loaded || sourcePath.isBlank() || targetPath.isBlank()) {
            return TranscodeMetadataReport.Unsupported
        }
        return try {
            TranscodeMetadataReport.parse(
                nativeMigrateMetadataForTranscode(sourcePath, targetPath, encoderLabel)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "migrateMetadataForTranscode failed: $sourcePath -> $targetPath", e)
            TranscodeMetadataReport.Unsupported
        }
    }

    // 兼容旧接口
    fun isWavFile(filePath: String): Boolean {
        if (!loaded) return false
        return filePath.endsWith(".wav", ignoreCase = true) && isSupported(filePath)
    }

    fun readWavMetadata(filePath: String): Map<String, String> = readMetadata(filePath)

    private external fun nativeReadMetadata(path: String): Map<String, String>?
    private external fun nativeReadAllTextProperties(path: String): Array<String?>?
    private external fun nativeInspectMetadataForTranscode(sourcePath: String): String?
    private external fun nativeMigrateMetadataForTranscode(
        sourcePath: String,
        targetPath: String,
        encoderLabel: String?,
    ): String?
    private external fun nativeIsSupported(path: String): Boolean
    private external fun nativeExtractEmbeddedArtworkToFile(path: String, outputPath: String): Boolean
    private external fun nativeWriteEmbeddedArtwork(path: String, artworkPath: String, mimeType: String): Boolean
    private external fun nativeWriteMetadata(path: String, keys: Array<String>, values: Array<String>): Boolean
}
