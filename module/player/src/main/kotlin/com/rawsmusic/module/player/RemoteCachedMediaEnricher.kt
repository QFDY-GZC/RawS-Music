package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.core.common.utils.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * Enriches a remote playback identity from the ordinary local file used by its fallback transport.
 *
 * The public AudioFile.path deliberately remains the original HTTP/WebDAV URL. The local cache is
 * only a physical read source for tags and embedded artwork, so queue persistence and credential
 * rehydration never depend on an app-private cache path.
 */
internal class RemoteCachedMediaEnricher(
    private val cacheDir: File,
    private val tag: String,
) {
    fun enrich(song: AudioFile): AudioFile? {
        if (!song.path.startsWith("http://", true) && !song.path.startsWith("https://", true)) {
            return null
        }
        val entry = RemoteHttpStreamRegistry.lookup(song.path) ?: return null
        val resolvedPath = entry.resolveUrl(song.path)
        if (resolvedPath.startsWith("http://", true) || resolvedPath.startsWith("https://", true)) {
            return null
        }
        if (resolvedPath.startsWith("content://", true) || resolvedPath.startsWith("rawfd://", true)) {
            return null
        }
        val source = File(resolvedPath)
        if (!source.isFile || source.length() <= 0L) return null

        val info = runCatching { FFmpegBridge.getMediaInfo(source.absolutePath).orEmpty() }
            .onFailure { AppLogger.w(tag, "Remote cached media-info failed: ${source.name}", it) }
            .getOrDefault(emptyMap())
        val artworkPath = extractArtwork(song, source)
        val enriched = merge(song, info, artworkPath, source.length())
        AppLogger.i(
            tag,
            "ONLINE_PIPE REMOTE_METADATA_READY owner=${entry.owner} " +
                "title=${enriched.title.take(80)} sr=${enriched.sampleRate} bits=${enriched.bitsPerSample} " +
                "ch=${enriched.channelCount} bitrate=${enriched.bitRate} duration=${enriched.duration} " +
                "art=${enriched.albumArtPath.isNotBlank()} source=${source.name}",
        )
        return enriched
    }

    private fun extractArtwork(song: AudioFile, source: File): String {
        val artworkDir = File(cacheDir, "albumart/webdav_embedded").apply { mkdirs() }
        val key = sha256("${song.path}\u0000${source.length()}").take(32)
        val target = File(artworkDir, "$key.jpg")
        if (target.isFile && target.length() > MIN_ARTWORK_BYTES) {
            return "file://${target.absolutePath}"
        }
        val temp = File(artworkDir, "$key.tmp")
        temp.delete()
        return try {
            val result = FFmpegBridge.extractCover(source.absolutePath, temp.absolutePath)
            if (result == 0 && temp.isFile && temp.length() > MIN_ARTWORK_BYTES) {
                if (target.exists()) target.delete()
                if (temp.renameTo(target)) {
                    "file://${target.absolutePath}"
                } else {
                    temp.delete()
                    ""
                }
            } else {
                temp.delete()
                ""
            }
        } catch (error: Throwable) {
            temp.delete()
            AppLogger.w(tag, "Remote cached artwork extract failed: ${source.name}", error)
            ""
        }
    }

    companion object {
        private const val MIN_ARTWORK_BYTES = 1024L

        internal fun merge(
            song: AudioFile,
            info: Map<String, String>,
            artworkPath: String,
            localSize: Long,
        ): AudioFile {
            val audioPrefix = info.entries
                .firstOrNull { (key, value) ->
                    key.startsWith("stream_") && key.endsWith("_codec_type") && value.equals("audio", true)
                }
                ?.key
                ?.removeSuffix("codec_type")
                .orEmpty()

            fun value(vararg keys: String): String = keys.firstNotNullOfOrNull { wanted ->
                info.entries.firstOrNull { it.key.equals(wanted, true) }?.value?.trim()?.takeIf(String::isNotBlank)
            }.orEmpty()

            fun streamValue(suffix: String): String =
                if (audioPrefix.isBlank()) "" else value("$audioPrefix$suffix")

            fun positiveInt(raw: String): Int = raw.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 0

            fun indexedNumber(raw: String): Int = raw.substringBefore('/').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

            val durationMs = value("duration").toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { (it * 1000.0).toLong() }
                ?: 0L
            val sampleRate = positiveInt(streamValue("effective_sample_rate"))
                .takeIf { it > 0 }
                ?: positiveInt(streamValue("sample_rate"))
            val bits = positiveInt(streamValue("bits_per_sample"))
                .takeIf { it > 0 }
                ?: positiveInt(streamValue("bits_per_raw_sample"))
            val channels = positiveInt(streamValue("channels"))
            val bitRate = positiveInt(streamValue("bit_rate"))
                .takeIf { it > 0 }
                ?: positiveInt(value("bit_rate"))
            val codec = streamValue("codec_name")
            val year = value("date", "year")
                .let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() ?: 0 }
            val track = indexedNumber(value("track", "tracknumber"))
            val disc = indexedNumber(value("disc", "discnumber"))
            val bpm = positiveInt(value("bpm", "tbpm"))

            return song.copy(
                title = value("title").ifBlank { song.title },
                artist = value("artist").ifBlank { song.artist },
                album = value("album").ifBlank { song.album },
                albumArtist = value("album_artist", "albumartist", "album artist").ifBlank { song.albumArtist },
                duration = durationMs.takeIf { it > 0L } ?: song.duration,
                sampleRate = sampleRate.takeIf { it > 0 } ?: song.sampleRate,
                bitRate = bitRate.takeIf { it > 0 } ?: song.bitRate,
                bitsPerSample = bits.takeIf { it > 0 } ?: song.bitsPerSample,
                fileSize = localSize.takeIf { it > 0L } ?: song.fileSize,
                trackNumber = track.takeIf { it > 0 } ?: song.trackNumber,
                year = year.takeIf { it > 0 } ?: song.year,
                albumArtPath = artworkPath.ifBlank { song.albumArtPath },
                genre = value("genre").ifBlank { song.genre },
                composer = value("composer").ifBlank { song.composer },
                discNumber = disc.takeIf { it > 0 } ?: song.discNumber,
                channelCount = channels.takeIf { it > 0 } ?: song.channelCount,
                bpm = bpm.takeIf { it > 0 } ?: song.bpm,
                encodingFormat = codec.ifBlank { song.encodingFormat }.uppercase(),
            )
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
