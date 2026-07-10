package com.rawsmusic.module.scanner

import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.common.utils.SampleRateNormalizer
import java.io.RandomAccessFile

/**
 * 基于 FFprobeKit (ffmpeg-kit) 的通用元数据提取器。
 * 支持 WAV/FLAC/MP3/AAC/ALAC/DSF/DFF 等所有格式的标签和流信息读取。
 */
object FfmpegMetadataReader {

    private const val TAG = "FfmpegMetadataReader"
    private const val ENABLE_TRACE_LOGS = false

    private inline fun logd(message: () -> String) {
        if (ENABLE_TRACE_LOGS) Log.d(TAG, message())
    }

    private inline fun logw(message: () -> String) {
        if (ENABLE_TRACE_LOGS) Log.w(TAG, message())
    }

    private inline fun loge(message: () -> String) {
        if (ENABLE_TRACE_LOGS) Log.e(TAG, message())
    }

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
        val year: Int = 0,
        val cueSheet: String = ""
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
            var tagLibInfo: FullAudioInfo? = null

            // 优先使用 TagLib（全格式支持，比 FFmpeg 更快）
            if (TagLibBridge.isLoaded() && TagLibBridge.isSupported(filePath)) {
                logd { "readFullInfo: Using TagLib for: $filePath" }
                tagLibInfo = readFullInfoFromTagLib(filePath)

                if (!tagLibInfo.stream.needsFfmpegStreamFallback(filePath)) {
                    return tagLibInfo
                }

                logd { "readFullInfo: TagLib stream incomplete, fallback FFmpeg. sr=${tagLibInfo.stream.sampleRate}, bits=${tagLibInfo.stream.bitsPerSample}, ch=${tagLibInfo.stream.channels}, file=$filePath" }
            }

            // 回退到 FFmpeg
            logd { "readFullInfo: Using FFmpeg for: $filePath" }
            val ffmpegInfo = readFullInfoFromFfmpeg(filePath)

            if (tagLibInfo != null) {
                // 合并 TagLib 标签 + FFmpeg 流信息
                FullAudioInfo(
                    tags = mergeTags(tagLibInfo.tags, ffmpegInfo.tags),
                    stream = mergeStreamInfo(tagLibInfo.stream, ffmpegInfo.stream, filePath)
                )
            } else {
                ffmpegInfo
            }
        } catch (e: Exception) {
            logw { "readFullInfo failed for $filePath: ${e.message}" }
            FullAudioInfo()
        }
    }

    private fun readFullInfoFromFfmpeg(filePath: String): FullAudioInfo {
        val info = FFmpegBridge.getMediaInfo(filePath)
        if (info == null) {
            loge { "readFullInfoFromFfmpeg: FFmpegBridge.getMediaInfo returned NULL for $filePath" }
            return FullAudioInfo()
        }

        val tags = parseTags(info)
        val stream = parseStreamInfo(info, filePath).withResolvedBitDepth(filePath)

        logd { "readFullInfoFromFfmpeg result: sr=${stream.sampleRate}, bps=${stream.bitsPerSample}, br=${stream.bitRate}, ch=${stream.channels}, codec=${stream.codecName}" }

        return FullAudioInfo(tags = tags, stream = stream)
    }

    /**
     * 使用 TagLib 读取音频文件的完整元数据（支持所有格式）。
     */
    private fun readFullInfoFromTagLib(filePath: String): FullAudioInfo {
        return try {
            val metadata = TagLibBridge.readMetadata(filePath)
            if (metadata.isEmpty()) {
                logw { "readFullInfoFromTagLib: TagLib returned empty metadata for $filePath" }
                return FullAudioInfo()
            }

            logd { "readFullInfoFromTagLib: file=$filePath, totalKeys=${metadata.size}" }
            for ((key, value) in metadata) {
                logd { "  TAG: $key = '$value'" }
            }

            val tags = parseTagLibTags(metadata)
            val stream = parseTagLibStreamInfo(metadata)

            logd { "readFullInfoFromTagLib parsed tags: title='${tags.title}', artist='${tags.artist}', album='${tags.album}', genre='${tags.genre}', year=${tags.year}, track=${tags.trackNumber}" }
            logd { "readFullInfoFromTagLib result: sr=${stream.sampleRate}, bps=${stream.bitsPerSample}, br=${stream.bitRate}, ch=${stream.channels}, codec=${stream.codecName}" }

            FullAudioInfo(tags = tags, stream = stream)
        } catch (e: Exception) {
            logw { "readFullInfoFromTagLib failed for $filePath: ${e.message}" }
            FullAudioInfo()
        }
    }

    // ==================== TagLib/FFmpeg 合并逻辑 ====================

    private fun AudioStreamInfo.needsFfmpegStreamFallback(filePath: String): Boolean {
        val ext = filePath.substringAfterLast('.', "").uppercase()
        val needsBits = ext in setOf("FLAC", "WAV", "AIFF", "AIF", "ALAC", "APE", "DSF", "DFF")
        val mp4Family = ext in setOf("M4A", "MP4", "M4B", "M4P", "M4R", "ALAC", "AAX")
        if (sampleRate <= 0) return true
        if (channels <= 0) return true
        if (needsBits && bitsPerSample <= 0) return true
        // MP4 家族（m4a/mp4 等）：需要 codecName 才能区分有损 AAC 与无损 ALAC，
        // 且需要 bitsPerSample 才能拿到 ALAC 的真实位深，否则回退 FFmpeg 解析。
        if (mp4Family && (codecName.isBlank() || bitsPerSample <= 0)) return true
        return false
    }

    private fun mergeStreamInfo(
        tagLib: AudioStreamInfo,
        ffmpeg: AudioStreamInfo,
        filePath: String
    ): AudioStreamInfo {
        val merged = AudioStreamInfo(
            durationMs = if (tagLib.durationMs > 0) tagLib.durationMs else ffmpeg.durationMs,
            sampleRate = when {
                ffmpeg.sampleRate > 0 && ffmpeg.sampleRate > tagLib.sampleRate -> ffmpeg.sampleRate
                tagLib.sampleRate > 0 -> tagLib.sampleRate
                else -> ffmpeg.sampleRate
            },
            channels = if (tagLib.channels > 0) tagLib.channels else ffmpeg.channels,
            bitsPerSample = when {
                tagLib.bitsPerSample > 0 -> tagLib.bitsPerSample
                ffmpeg.bitsPerSample > 0 -> ffmpeg.bitsPerSample
                else -> inferBitDepthFromCodecOrExtension(
                    codecName = ffmpeg.codecName.ifBlank { tagLib.codecName },
                    filePath = filePath
                )
            },
            bitRate = if (tagLib.bitRate > 0) tagLib.bitRate else ffmpeg.bitRate,
            codecName = ffmpeg.codecName.ifBlank { tagLib.codecName },
            codecLongName = ffmpeg.codecLongName.ifBlank { tagLib.codecLongName },
            formatName = ffmpeg.formatName.ifBlank { tagLib.formatName }
        )
        return merged.withResolvedBitDepth(filePath)
    }

    private fun mergeTags(tagLib: ExtendedTags, ffmpeg: ExtendedTags): ExtendedTags {
        return tagLib.copy(
            title = tagLib.title.ifBlank { ffmpeg.title },
            artist = tagLib.artist.ifBlank { ffmpeg.artist },
            album = tagLib.album.ifBlank { ffmpeg.album },
            genre = tagLib.genre.ifBlank { ffmpeg.genre },
            composer = tagLib.composer.ifBlank { ffmpeg.composer },
            albumArtist = tagLib.albumArtist.ifBlank { ffmpeg.albumArtist },
            lyrics = tagLib.lyrics.ifBlank { ffmpeg.lyrics },
            year = if (tagLib.year > 0) tagLib.year else ffmpeg.year,
            trackNumber = if (tagLib.trackNumber > 0) tagLib.trackNumber else ffmpeg.trackNumber,
            discNumber = if (tagLib.discNumber > 1) tagLib.discNumber else ffmpeg.discNumber,
            bpm = if (tagLib.bpm > 0) tagLib.bpm else ffmpeg.bpm
        )
    }

    private fun AudioStreamInfo.withResolvedBitDepth(filePath: String): AudioStreamInfo {
        if (bitsPerSample > 0) return this
        val resolved = inferBitDepthFromCodecOrExtension(codecName, filePath)
        return if (resolved > 0) copy(bitsPerSample = resolved) else this
    }

    private fun inferBitDepthFromCodecOrExtension(codecName: String, filePath: String): Int {
        val codec = codecName.lowercase()
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when {
            codec.contains("pcm_s8") || codec.contains("pcm_u8") -> 8
            codec.contains("pcm_s16") -> 16
            codec.contains("pcm_s24") -> 24
            codec.contains("pcm_s32") -> 32
            codec.contains("pcm_f32") -> 32
            codec.contains("pcm_f64") -> 64
            codec.contains("dsd") || ext == "dsf" || ext == "dff" -> 1
            else -> 0
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
            albumPeak = parseReplayGainPeak(metadata["replaygain_album_peak"]),
            cueSheet = metadata.entries.firstOrNull { (key, value) ->
                value.isNotBlank() && key.replace("_", "").replace("-", "")
                    .equals("cuesheet", ignoreCase = true)
            }?.value.orEmpty()
        )
    }

    /**
     * 解析 TagLib 返回的 WAV 音频属性到 AudioStreamInfo。
     */
    private fun parseTagLibStreamInfo(metadata: Map<String, String>): AudioStreamInfo {
        return AudioStreamInfo(
            durationMs = metadata["duration_ms"]?.toLongOrNull() ?: 0L,
            sampleRate = SampleRateNormalizer.normalize(
                rawSampleRate = metadata["sample_rate"]?.toIntOrNull() ?: 0,
                codecName = metadata["codec_name"].orEmpty(),
                formatName = metadata["format_name"].orEmpty()
            ),
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
            albumPeak = parseReplayGainPeak(tag("replaygain_album_peak")),
            cueSheet = tag("cuesheet", "cue_sheet", "CUESHEET", "CUE_SHEET")
        )
    }

    // ==================== 流信息解析 ====================

    private fun parseStreamInfo(info: Map<String, String>, filePath: String): AudioStreamInfo {
        var durationMs = 0L
        var formatName = ""

        fun parseDurationMs(value: String): Long =
            value.toDoubleOrNull()?.let { (it * 1000).toLong() }?.coerceAtLeast(0L) ?: 0L

        for ((key, value) in info) {
            val k = key.toString()
            val v = value.toString()
            if (k == "duration" || k == "format_duration") {
                durationMs = maxOf(durationMs, parseDurationMs(v))
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
            logw { "parseStreamInfo: NO audio stream found!" }
            return AudioStreamInfo(durationMs = durationMs, formatName = formatName)
        }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var bitsPerRawSample = 0
        var bitsPerCodedSample = 0
        var sampleFmt = ""
        var bitRate = 0
        var effectiveSampleRate = 0
        var codecName = ""
        var codecLongName = ""

        val suffixMap = mapOf(
            "sample_rate" to { v: String -> sampleRate = v.toIntOrNull() ?: 0 },
            "channels" to { v: String -> channels = v.toIntOrNull() ?: 0 },
            "bits_per_sample" to { v: String -> bitsPerSample = v.toIntOrNull() ?: 0 },
            "bits_per_raw_sample" to { v: String -> bitsPerRawSample = v.toIntOrNull() ?: 0 },
            "bits_per_coded_sample" to { v: String -> bitsPerCodedSample = v.toIntOrNull() ?: 0 },
            "sample_fmt" to { v: String -> sampleFmt = v },
            "bit_rate" to { v: String -> bitRate = v.toIntOrNull() ?: 0 },
            "duration" to { v: String -> durationMs = maxOf(durationMs, parseDurationMs(v)) },
            "effective_sample_rate" to { v: String -> effectiveSampleRate = v.toIntOrNull() ?: 0 },
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

        // 统一通过 AudioBitDepthResolver 解析源位深，metadata 与位深解耦：
        // - 有损格式（AAC/MP3/Opus/Vorbis/WMA/AMR 等）一律返回 0
        // - 无损格式按 bits_per_sample -> bits_per_raw_sample -> bits_per_coded_sample
        //   -> sample_fmt -> codec/扩展名 的优先级回退推断真实位深
        val resolvedBits = AudioBitDepthResolver.resolveSourceBitDepth(
            codecName = codecName,
            formatName = formatName,
            filePath = filePath,
            bitsPerSample = bitsPerSample,
            bitsPerRawSample = bitsPerRawSample,
            bitsPerCodedSample = bitsPerCodedSample,
            sampleFmt = sampleFmt
        )
        val isLossy = AudioBitDepthResolver.isLossyCodec(codecName, formatName)
        sampleRate = SampleRateNormalizer.normalize(
            rawSampleRate = sampleRate,
            codecName = codecName,
            formatName = formatName,
            filePath = filePath,
            effectiveSampleRate = effectiveSampleRate
        )
        bitsPerSample = resolvedBits

        logd { "parseStreamInfo: idx=$audioStreamIndex, codec=$codecName, sr=$sampleRate, effectiveSr=$effectiveSampleRate, ch=$channels, bps=$bitsPerSample, br=$bitRate, lossy=$isLossy" }

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
            ext == "MP3" -> "MP3"
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
