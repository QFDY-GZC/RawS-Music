package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.common.utils.BitrateNormalizer
import com.rawsmusic.core.common.utils.SampleRateNormalizer
import com.rawsmusic.module.scanner.parser.CueParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

object MetadataParser {

    fun parseFromFile(filePath: String): AudioFile? {
        return try {
            val file = File(filePath)
            if (!file.exists() || !AudioUtils.isAudioFile(file)) return null

            // 使用 FFprobeKit 一次性读取全部元数据（标签 + 流信息）
            val fullInfo = FfmpegMetadataReader.readFullInfo(filePath)
            val tags = fullInfo.tags
            val stream = fullInfo.stream

            val encodingFormat = FfmpegMetadataReader.mapCodecToFormat(stream.codecName, filePath)
            val mimeType = FfmpegMetadataReader.mapFormatToMimeType(encodingFormat, filePath)

            // 时长：优先 FFprobeKit，回退计算
            var durationMs = stream.durationMs
            var bitRate = BitrateNormalizer.toBps(
                rawBitrate = stream.bitRate,
                durationMs = durationMs,
                fileSizeBytes = file.length(),
                codecName = stream.codecName,
                formatName = encodingFormat,
                filePath = filePath
            )
            if (durationMs <= 0 && bitRate > 0 && file.length() > 0) {
                durationMs = (file.length() * 8.0 / bitRate * 1000).toLong()
            } else if (durationMs > 0 && bitRate <= 0) {
                bitRate = BitrateNormalizer.toBps(
                    rawBitrate = (file.length() * 8.0 / (durationMs / 1000.0)).toInt(),
                    durationMs = durationMs,
                    fileSizeBytes = file.length(),
                    codecName = stream.codecName,
                    formatName = encodingFormat,
                    filePath = filePath
                )
            }

            AudioFile(
                path = filePath,
                title = tags.title.ifBlank { file.nameWithoutExtension },
                artist = tags.artist,
                album = tags.album,
                duration = durationMs.coerceAtLeast(0),
                sampleRate = SampleRateNormalizer.normalize(
                    rawSampleRate = stream.sampleRate,
                    codecName = stream.codecName,
                    formatName = stream.formatName,
                    filePath = filePath
                ),
                bitRate = bitRate,
                bitsPerSample = stream.bitsPerSample,
                format = mimeType.substringAfter("/").uppercase(),
                fileSize = file.length(),
                trackNumber = tags.trackNumber,
                year = tags.year,
                dateAdded = file.lastModified(),
                dateModified = file.lastModified(),
                albumArtPath = "",
                genre = tags.genre,
                composer = tags.composer,
                discNumber = tags.discNumber,
                channelCount = stream.channels,
                bpm = tags.bpm,
                albumArtist = tags.albumArtist,
                encodingFormat = encodingFormat,
                trackGain = tags.trackGain,
                trackPeak = tags.trackPeak,
                albumGain = tags.albumGain,
                albumPeak = tags.albumPeak
            )
        } catch (e: Exception) {
            null
        }
    }

    fun scanDirectory(directory: File): Flow<ScanProgress> = flow {
        val startTime = System.currentTimeMillis()
        val audioFiles = mutableListOf<AudioFile>()

        if (!directory.exists() || !directory.isDirectory) {
            emit(ScanProgress.Error("Directory does not exist"))
            return@flow
        }

        val allFiles = walkDirectory(directory)
        val total = allFiles.size
        emit(ScanProgress.Started(total))

        allFiles.forEachIndexed { index, file ->
            val options = MediaStoreScanner.ScanOptions.fromPreferences()
            val parsed = parseFromFile(file.absolutePath)
            if (parsed != null && MediaStoreScanner.shouldInclude(parsed, options)) {
                val expanded = MediaStoreScanner.expandCueTracks(parsed)
                audioFiles.addAll(expanded.filter { MediaStoreScanner.shouldInclude(it, options) })
            }
            if (index % 10 == 0) {
                emit(ScanProgress.Progress(index + 1, total))
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        emit(ScanProgress.Completed(audioFiles, audioFiles.size, elapsed))
    }.flowOn(Dispatchers.IO)

    private fun walkDirectory(directory: File): List<File> {
        val result = mutableListOf<File>()
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                result.addAll(walkDirectory(file))
            } else if (AudioUtils.isAudioFile(file)) {
                result.add(file)
            }
        }
        return result
    }

}
