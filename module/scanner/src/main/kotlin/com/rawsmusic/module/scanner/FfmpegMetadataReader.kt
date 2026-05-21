package com.rawsmusic.module.scanner

import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.taglib.TagLibBridge
import java.io.RandomAccessFile

/**
 * 基于 FFprobeKit (ffmpeg-kit) 的通用元数据提取器。
 * 支持 WAV/FLAC/MP3/AAC/ALAC/DSF/DFF 等所有格式的标签和流信息读取。
 */
object FfmpegMetadataReader {

    private const val TAG = "FfmpegMetadataReader"

    data class ExtendedTags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val genre: String = "",
        val composer: String = "",
        val discNumber: Int = 1,
        val discTotal: Int = 1,
        val albumArtist: String = "",
        val bpm: Int = 0,
        val trackGain: Float = 0f,
        val trackPeak: Float = 1.0f,
        val albumGain: Float = 0f,
        val albumPeak: Float = 1.0f,
        val encoder: String = "",
        val lyrics: String = "",
        val grouping: String = "",
        val isrc: String = "",
        val catalogNo: String = "",
        val barcode: String = "",
        val trackNumber: Int = 0,
        val year: Int = 0
    )

    data class AudioStreamInfo(
        val durationMs: Long = 0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val bitsPerSample: Int = 0,
        val bitRate: Int = 0,
        val codecName: String = "",
        val codecLongName: String = "",
        val formatName: String = ""
    )

    data class FullAudioInfo(
        val tags: ExtendedTags = ExtendedTags(),
        val stream: AudioStreamInfo = AudioStreamInfo()
    )

    /**
     * 一次性读取音频文件的完整元数据（标签 + 流信息）。
     * WAV 文件优先使用 TagLib 解析（更全面的 RIFF INFO + ID3v2 支持），
     * 其他格式使用 FFmpeg 解析。
     */
    fun readFullInfo(filePath: String): FullAudioInfo {
        return try {
            // WAV 文件优先使用 TagLib 解析
            val isWav = filePath.endsWith(".wav", ignoreCase = true) && TagLibBridge.isLoaded()
            if (isWav && TagLibBridge.isWavFile(filePath)) {
                Log.d(TAG, "readFullInfo: Using TagLib for WAV file: $filePath")
                return readFullInfoFromTagLib(filePath)
            }

            // 其他格式使用 FFmpeg 解析
            Log.d(TAG, "readFullInfo: Using FFmpeg for file: $filePath")
            val info = FFmpegBridge.getMediaInfo(filePath)
            if (info == null) {
                Log.e(TAG, "readFullInfo: FFmpegBridge.getMediaInfo returned NULL for $filePath")
                return FullAudioInfo()
            }

            val streamKeys = info.keys.filter { it.startsWith("stream_") }.sorted()
            val tagKeys = info.keys.filter { !it.startsWith("stream_") && it != "format_name" }.sorted()
            Log.d(TAG, "readFullInfo: file=$filePath, totalKeys=${info.size}")
            Log.d(TAG, "  tagKeys=$tagKeys")
            for (key in tagKeys) {
                for ((k, v) in info) {
                    if (k.toString() == key) {
                        Log.d(TAG, "  TAG: $key = '$v'")
                        break
                    }
                }
            }

            val tags = parseTags(info)
            val stream = parseStreamInfo(info)

            Log.d(TAG, "readFullInfo result: sr=${stream.sampleRate}, bps=${stream.bitsPerSample}, " +
                    "br=${stream.bitRate}, ch=${stream.channels}, codec=${stream.codecName}")

            FullAudioInfo(tags = tags, stream = stream)
        } catch (e: Exception) {
            Log.w(TAG, "readFullInfo failed for $filePath: ${e.message}")
            FullAudioInfo()
        }
    }

    /**
     * 使用 TagLib 读取 WAV 文件的完整元数据。
     */
    private fun readFullInfoFromTagLib(filePath: String): FullAudioInfo {
        return try {
            val metadata = TagLibBridge.readWavMetadata(filePath)
            if (metadata.isEmpty()) {
                Log.w(TAG, "readFullInfoFromTagLib: TagLib returned empty metadata for $filePath")
                return FullAudioInfo()
            }

            Log.d(TAG, "readFullInfoFromTagLib: file=$filePath, totalKeys=${metadata.size}")
            for ((key, value) in metadata) {
                Log.d(TAG, "  TAG: $key = '$value'")
            }

            val tags = parseTagLibTags(metadata)
            val stream = parseTagLibStreamInfo(metadata)

            Log.d(TAG, "readFullInfoFromTagLib parsed tags: title='${tags.title}', artist='${tags.artist}', " +
                    "album='${tags.album}', genre='${tags.genre}', year=${tags.year}, track=${tags.trackNumber}")
            Log.d(TAG, "readFullInfoFromTagLib result: sr=${stream.sampleRate}, bps=${stream.bitsPerSample}, " +
                    "br=${stream.bitRate}, ch=${stream.channels}, codec=${stream.codecName}")

            FullAudioInfo(tags = tags, stream = stream)
        } catch (e: Exception) {
            Log.w(TAG, "readFullInfoFromTagLib failed for $filePath: ${e.message}")
            FullAudioInfo()
        }
    }

    /**
     * 仅读取标签（向后兼容）
     */
    fun readTags(filePath: String): ExtendedTags {
        return readFullInfo(filePath).tags
    }

    // ==================== TagLib 标签解析 ====================

    /**
     * 解析 TagLib 返回的 WAV 元数据到 ExtendedTags。
     */
    private fun parseTagLibTags(metadata: Map<String, String>): ExtendedTags {
        fun tag(vararg keys: String): String {
            for (key in keys) {
                val v = metadata[key]
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }

        return ExtendedTags(
            title = tag("TIT2", "INAM", "title"),
            artist = tag("TPE1", "IART", "artist"),
            album = tag("TALB", "IPRD", "album"),
            genre = tag("TCON", "IGNR", "genre"),
            composer = tag("TCOM", "IMUS", "composer"),
            albumArtist = tag("TPE2", "IART", "artist"),
            encoder = tag("TSSE", "ISFT", "IENG", "encoder"),
            lyrics = tag("USLT"),
            isrc = tag("TSRC", "isrc"),
            grouping = tag("TIT1"),
            trackNumber = tag("TRCK", "IPRT", "track").split("/").firstOrNull()?.toIntOrNull() ?: 0,
            discNumber = tag("TPOS", "part").split("/").firstOrNull()?.toIntOrNull() ?: 1,
            discTotal = tag("TPOS", "part").split("/").let {
                if (it.size > 1) it[1].toIntOrNull() ?: 1 else 1
            },
            bpm = tag("TBPM", "IBPM", "bpm").toIntOrNull() ?: 0,
            year = tag("TYER", "TDRC", "ICRD", "year").substringBefore("-").toIntOrNull() ?: 0,
            trackGain = parseReplayGain(metadata["replaygain_track_gain"]),
            trackPeak = parseReplayGainPeak(metadata["replaygain_track_peak"]),
            albumGain = parseReplayGain(metadata["replaygain_album_gain"]),
            albumPeak = parseReplayGainPeak(metadata["replaygain_album_peak"])
        )
    }

    /**
     * 解析 TagLib 返回的 WAV 音频属性到 AudioStreamInfo。
     */
    private fun parseTagLibStreamInfo(metadata: Map<String, String>): AudioStreamInfo {
        return AudioStreamInfo(
            durationMs = metadata["duration_ms"]?.toLongOrNull() ?: 0L,
            sampleRate = metadata["sample_rate"]?.toIntOrNull() ?: 0,
            channels = metadata["channels"]?.toIntOrNull() ?: 0,
            bitsPerSample = metadata["bits_per_sample"]?.toIntOrNull() ?: 0,
            bitRate = metadata["bit_rate"]?.toIntOrNull() ?: 0,
            codecName = metadata["codec_name"] ?: "",
            codecLongName = "",
            formatName = metadata["format_name"] ?: ""
        )
    }

    // ==================== FFmpeg 标签解析 ====================

    private fun parseTags(info: Map<String, String>): ExtendedTags {
        val lookupCache = HashMap<String, String>()
        for ((key, value) in info) {
            val safeKey = key.toString()
            val lowerKey = safeKey.lowercase()
            val safeValue = value.toString()
            lookupCache[safeKey] = safeValue
            lookupCache[lowerKey] = safeValue
            if (lowerKey.startsWith("stream_") && lowerKey.contains("_raw_tag_")) {
                val tagKey = safeKey.substringAfterLast("_raw_tag_")
                val lowerTagKey = tagKey.lowercase()
                if (!lookupCache.containsKey(tagKey)) {
                    lookupCache[tagKey] = safeValue
                }
                if (!lookupCache.containsKey(lowerTagKey)) {
                    lookupCache[lowerTagKey] = safeValue
                }
            }
        }

        fun tag(vararg keys: String): String {
            for (key in keys) {
                val v = lookupCache[key] ?: lookupCache[key.lowercase()]
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }

        return ExtendedTags(
            title = tag("title", "TIT2", "INAM", "name"),
            artist = tag("artist", "TPE1", "IART", "author", "album_artist"),
            album = tag("album", "album_title", "WM/AlbumTitle", "TALB", "IPRD", "product", "prd"),
            genre = tag("genre", "TCON", "IGNR"),
            composer = tag("composer", "TCOM", "IMUS", "writer"),
            albumArtist = tag("album_artist", "albumartist", "TPE2", "IART"),
            encoder = tag("encoder", "encoding", "ISFT", "IENG"),
            lyrics = tag("lyrics", "unsynced_lyrics", "lyrics-eng", "USLT"),
            isrc = tag("isrc", "TSRC"),
            grouping = tag("grouping", "contentgroup", "TIT1"),
            trackNumber = tag("track", "track_number", "TRCK", "ITRK").split("/").firstOrNull()?.toIntOrNull() ?: 0,
            discNumber = tag("disc", "disc_number", "TPOS", "part").split("/").firstOrNull()?.toIntOrNull() ?: 1,
            discTotal = tag("disc", "TPOS", "part").split("/").let {
                if (it.size > 1) it[1].toIntOrNull() ?: 1 else 1
            },
            bpm = tag("bpm", "TBPM", "tmpo").toIntOrNull() ?: 0,
            year = tag("date", "year", "TYER", "TDRC", "ICRD").substringBefore("-").toIntOrNull() ?: 0,
            trackGain = parseReplayGain(tag("replaygain_track_gain")),
            trackPeak = parseReplayGainPeak(tag("replaygain_track_peak")),
            albumGain = parseReplayGain(tag("replaygain_album_gain")),
            albumPeak = parseReplayGainPeak(tag("replaygain_album_peak"))
        )
    }

    // ==================== 流信息解析 ====================

    private fun parseStreamInfo(info: Map<String, String>): AudioStreamInfo {
        var durationMs = 0L
        var formatName = ""

        for ((key, value) in info) {
            val k = key.toString()
            val v = value.toString()
            if (k == "duration") {
                durationMs = try { v.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L } catch (_: Exception) { 0L }
            }
            if (k == "format_name") {
                formatName = v
            }
        }

        var audioStreamIndex = -1
        for ((key, value) in info) {
            if (key.startsWith("stream_") && key.endsWith("_codec_type") && value == "audio") {
                val idxStr = key.removePrefix("stream_").removeSuffix("_codec_type")
                audioStreamIndex = idxStr.toIntOrNull() ?: 0
                break
            }
        }
        if (audioStreamIndex < 0) {
            for ((key, value) in info) {
                if (key.startsWith("stream_") && key.endsWith("_raw_tag_codec_type") && value == "audio") {
                    val idxStr = key.removePrefix("stream_").removeSuffix("_raw_tag_codec_type")
                    audioStreamIndex = idxStr.toIntOrNull() ?: 0
                    break
                }
            }
        }

        if (audioStreamIndex < 0) {
            Log.w(TAG, "parseStreamInfo: NO audio stream found!")
            return AudioStreamInfo(durationMs = durationMs, formatName = formatName)
        }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var bitRate = 0
        var codecName = ""
        var codecLongName = ""

        val suffixMap = mapOf(
            "sample_rate" to { v: String -> sampleRate = v.toIntOrNull() ?: 0 },
            "channels" to { v: String -> channels = v.toIntOrNull() ?: 0 },
            "bits_per_sample" to { v: String -> bitsPerSample = v.toIntOrNull() ?: 0 },
            "bit_rate" to { v: String -> bitRate = v.toIntOrNull() ?: 0 },
            "codec_name" to { v: String -> codecName = v },
            "codec_long_name" to { v: String -> codecLongName = v }
        )

        for ((key, value) in info) {
            if (!key.startsWith("stream_")) continue
            for ((suffix, setter) in suffixMap) {
                if (key.endsWith("_$suffix") || key.endsWith(suffix)) {
                    val idxPart = key.removeSuffix("_$suffix").removePrefix("stream_")
                    if (idxPart.toIntOrNull() == audioStreamIndex) {
                        setter(value)
                    }
                }
            }
        }

        if (bitRate <= 0) {
            for ((key, value) in info) {
                if (key == "bit_rate") {
                    bitRate = value.toIntOrNull() ?: 0
                    break
                }
            }
        }

        val isLossy = codecName.contains("mp3", true) || codecName.contains("aac", true) ||
                codecName.contains("opus", true) || codecName.contains("vorbis", true) ||
                codecName.contains("wma", true) || codecName.contains("amr", true)
        if (isLossy) bitsPerSample = 0

        Log.d(TAG, "parseStreamInfo: idx=$audioStreamIndex, codec=$codecName, sr=$sampleRate, ch=$channels, " +
                "bps=$bitsPerSample, br=$bitRate, lossy=$isLossy")

        return AudioStreamInfo(
            durationMs = durationMs,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            bitRate = bitRate,
            codecName = codecName,
            codecLongName = codecLongName,
            formatName = formatName
        )
    }

    private fun parseReplayGain(value: String?): Float {
        if (value.isNullOrBlank()) return 0f
        return value.replace(" dB", "").replace("dB", "").trim().toFloatOrNull() ?: 0f
    }

    private fun parseReplayGainPeak(value: String?): Float {
        if (value.isNullOrBlank()) return 1.0f
        return value.trim().toFloatOrNull() ?: 1.0f
    }

    // ==================== 编码格式映射 ====================

    fun mapCodecToFormat(codecName: String, filePath: String): String {
        val ext = filePath.substringAfterLast(".", "").uppercase()
        return when {
            codecName.contains("flac", true) -> "FLAC"
            codecName.contains("alac", true) -> "ALAC"
            codecName.contains("opus", true) -> "Opus"
            codecName.contains("vorbis", true) -> "Vorbis"
            codecName.contains("aac", true) -> "AAC"
            codecName.contains("mp3", true) || codecName.contains("mp3float", true) -> "MP3"
            codecName.contains("pcm_f32", true) || codecName.contains("pcm_f64", true) -> when (ext) {
                "WAV" -> "WAV Float"
                "AIFF", "AIF" -> "AIFF Float"
                else -> ext.ifBlank { "PCM Float" }
            }
            codecName.contains("pcm", true) -> when (ext) {
                "WAV" -> "WAV"
                "AIFF", "AIF" -> "AIFF"
                else -> ext.ifBlank { "PCM" }
            }
            codecName.contains("dsd", true) -> "DSD"
            codecName.contains("ape", true) -> "APE"
            codecName.contains("wma", true) -> "WMA"
            else -> ext.ifBlank { codecName.uppercase() }
        }
    }

    fun mapFormatToMimeType(format: String, filePath: String): String {
        val ext = filePath.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aiff", "aif" -> "audio/aiff"
            "m4a", "mp4" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/x-ape"
            "opus" -> "audio/opus"
            "dsf" -> "audio/x-dsf"
            "dff" -> "audio/x-dff"
            "alac" -> "audio/mp4"
            else -> "audio/*"
        }
    }

    // ==================== DSD 文件头解析 ====================

    data class DsdInfo(
        val sampleRate: Int = 0,
        val channelCount: Int = 2,
        val bitsPerSample: Int = 1,
        val format: String = "",
        val sampleCount: Long = 0
    )

    fun parseDsdHeader(filePath: String): DsdInfo {
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                val magicStr = String(magic, Charsets.US_ASCII)

                when (magicStr) {
                    "DSD " -> parseDsfFormat(raf)
                    "FRM8" -> parseDffFormat(raf)
                    else -> DsdInfo()
                }
            }
        } catch (_: Exception) {
            DsdInfo()
        }
    }

    private fun parseDsfFormat(raf: RandomAccessFile): DsdInfo {
        raf.seek(16)
        readLeInt(raf)
        readLeShort(raf)
        val channelCount = readLeShort(raf).toInt()
        val sampleRate = readLeInt(raf)
        val bitsPerSample = readLeShort(raf).toInt()

        return DsdInfo(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            format = "DSF"
        )
    }

    private fun parseDffFormat(raf: RandomAccessFile): DsdInfo {
        var sampleRate = 0
        var channelCount = 2
        var bitsPerSample = 1

        raf.seek(0)
        while (raf.filePointer < raf.length().coerceAtMost(65536)) {
            val chunkId = ByteArray(4)
            try { raf.readFully(chunkId) } catch (_: Exception) { break }
            val chunkSize = try { readBeInt(raf).toLong() } catch (_: Exception) { break }

            val chunkIdStr = String(chunkId, Charsets.US_ASCII)
            when (chunkIdStr) {
                "PROP" -> {
                    val propId = ByteArray(4)
                    raf.readFully(propId)
                    val propIdStr = String(propId, Charsets.US_ASCII)
                    if (propIdStr == "SND ") {
                        channelCount = try { readBeShort(raf).toInt() } catch (_: Exception) { 2 }
                        val fsCode = try { readBeShort(raf).toInt() } catch (_: Exception) { 6 }
                        sampleRate = when (fsCode) {
                            1 -> 2822400; 2 -> 5644800; 3 -> 11289600
                            4 -> 22579200; 5 -> 45158400; else -> 2822400
                        }
                    }
                    break
                }
                "SST ", "DST ", "DSTC", "DSTI", "DSD " -> { /* 有数据，继续 */ }
            }
            if (chunkSize > 0) raf.seek(raf.filePointer + chunkSize)
            else break
        }

        return DsdInfo(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            format = "DFF"
        )
    }

    private fun readLeShort(raf: RandomAccessFile): Short {
        val b = ByteArray(2); raf.readFully(b)
        return ((b[1].toInt() and 0xFF) shl 8 or (b[0].toInt() and 0xFF)).toShort()
    }

    private fun readLeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[3].toInt() and 0xFF) shl 24 or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
    }

    private fun readBeShort(raf: RandomAccessFile): Short {
        val b = ByteArray(2); raf.readFully(b)
        return ((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)).toShort()
    }

    private fun readBeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) shl 24 or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }
}
