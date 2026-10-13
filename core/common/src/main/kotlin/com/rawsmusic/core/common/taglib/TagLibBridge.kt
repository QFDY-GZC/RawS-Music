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
        return com.rawsmusic.core.common.sacd.SacdIsoMetadata.withReadablePath(filePath) { nativeIsSupported(it) }
    }

    fun readMetadata(filePath: String): Map<String, String> {
        if (!loaded) return emptyMap()
        var snapshot = false
        val result = com.rawsmusic.core.common.sacd.SacdIsoMetadata.withReadablePath(filePath) {
            snapshot = it != filePath
            nativeReadMetadata(it) ?: emptyMap()
        }
        if (!snapshot) return result
        // Text is restored from the original container; audio geometry belongs to the ISO.
        val physical = if (filePath.startsWith("sacd://")) android.net.Uri.parse(filePath).pathSegments.getOrNull(1).orEmpty()
            else filePath
        val file = java.io.File(physical)
        val tracks = com.rawsmusic.core.common.sacd.SacdIsoBridge.tracks(
            com.rawsmusic.core.common.model.AudioFile(path = physical, format = "SACD-ISO",
                fileSize = file.length(), dateModified = file.lastModified()))
        val channels = tracks.firstOrNull()?.channels ?: 2
        return result.toMutableMap().apply {
            remove("format_code")
            put("sample_rate", "2822400"); put("bits_per_sample", "1"); put("channels", channels.toString())
            put("bit_rate", (2822400L * channels / 1000).toString())
            put("format_name", "SACD-ISO"); put("codec_name", "dsd_msbf")
            tracks.maxOfOrNull { (it.startFrame + it.frames) * 1000 / 75 }?.let {
                put("duration_ms", it.toString()); put("duration", (it / 1000).toString())
            }
        }
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
            com.rawsmusic.core.common.sacd.SacdIsoMetadata.withReadablePath(filePath) {
                nativeExtractEmbeddedArtworkToFile(it, outputPath)
            }
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
            com.rawsmusic.core.common.sacd.SacdIsoMetadata.withReadablePath(filePath) { nativeReadAllTextProperties(it) }
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
            com.rawsmusic.core.common.sacd.SacdIsoMetadata.withReadablePath(sourcePath) {
                TranscodeMetadataSourceInfo.parse(nativeInspectMetadataForTranscode(it))
            }
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
        isCancelled: () -> Boolean = { false },
    ): TranscodeMetadataReport {
        if (!loaded || sourcePath.isBlank() || targetPath.isBlank()) {
            return TranscodeMetadataReport.Unsupported
        }
        return try {
            if (targetPath.substringAfterLast('.').equals("iso", true)) {
                return migrateMetadataToSacd(sourcePath, targetPath, isCancelled)
            }
            TranscodeMetadataReport.parse(
                com.rawsmusic.core.common.sacd.SacdIsoMetadata.withReadablePath(sourcePath) {
                    nativeMigrateMetadataForTranscode(it, targetPath, encoderLabel)
                }
            )
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            Log.e(TAG, "migrateMetadataForTranscode failed: $sourcePath -> $targetPath", e)
            TranscodeMetadataReport.Unsupported
        }
    }

    private fun migrateMetadataToSacd(sourcePath: String, targetPath: String, isCancelled: () -> Boolean): TranscodeMetadataReport =
        com.rawsmusic.core.common.sacd.SacdIsoMetadata.withReadablePath(sourcePath) { readableSource ->
        val source = java.io.File(readableSource)
        val target = java.io.File(targetPath)
        val sourceInfo = inspectMetadataForTranscode(source.absolutePath)
        if (!sourceInfo.supported) return@withReadablePath TranscodeMetadataReport.Unsupported
        val sourceProperties = readAllTextProperties(source.absolutePath)
        com.rawsmusic.core.common.sacd.SacdMetadataArchive.append(source, target, isCancelled)
        com.rawsmusic.core.common.sacd.SacdIsoMetadata.withSource(targetPath) { restored ->
            if (restored == null) return@withSource TranscodeMetadataReport.Unsupported
            val restoredInfo = inspectMetadataForTranscode(restored.absolutePath)
            val restoredProperties = readAllTextProperties(restored.absolutePath)
            val textMismatch = (sourceProperties.keys + restoredProperties.keys).count {
                sourceProperties[it] != restoredProperties[it]
            }
            val pictureMismatch = kotlin.math.abs(sourceInfo.pictureCount - restoredInfo.pictureCount)
            // Original metadata bytes, including every opaque frame/atom, are retained unchanged.
            val bytesVerified = com.rawsmusic.core.common.sacd.SacdMetadataArchive.verify(source, target)
            val opaqueVerified = sourceInfo.unsupportedOpaqueItems == restoredInfo.unsupportedOpaqueItems &&
                sourceInfo.unsupportedKeys == restoredInfo.unsupportedKeys
            val passed = restoredInfo.supported && textMismatch == 0 && pictureMismatch == 0 && bytesVerified && opaqueVerified
            TranscodeMetadataReport(true, true, passed, passed, sourceProperties.size,
                sourceProperties.values.sumOf { it.size }, restoredProperties.size, restoredProperties.values.sumOf { it.size },
                0, textMismatch, restoredInfo.pictureCount, pictureMismatch,
                if (bytesVerified && opaqueVerified) 0 else sourceInfo.unsupportedOpaqueItems.coerceAtLeast(1),
                if (passed) emptyList() else listOf("SACD_METADATA_SNAPSHOT_VERIFICATION"))
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
