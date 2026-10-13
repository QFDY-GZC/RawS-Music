package com.rawsmusic.core.common.sacd

import android.net.Uri
import com.rawsmusic.core.common.CoreInit
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import java.io.File

object SacdIsoBridge {
    data class Track(
        val area: Int, val number: Int, val channels: Int, val startFrame: Long, val frames: Long,
        val dst: Boolean, val title: String, val artist: String, val album: String,
        val year: Int, val disc: Int, val composer: String, val albumArtist: String,
    )
    private val cache = object : LinkedHashMap<String, List<Track>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Track>>?) = size > 16
    }
    fun isIso(source: AudioFile): Boolean = source.format.equals("SACD-ISO", true) ||
        source.format.equals("ISO", true) || source.path.substringAfterLast('.').equals("iso", true)

    @Synchronized fun tracks(source: AudioFile): List<Track> {
        if (!FFmpegBridge.isLoaded()) return emptyList()
        val key = "${source.path}|${source.fileSize}|${source.dateModified}"
        cache[key]?.let { return it }
        val rows = if (source.path.startsWith("content://")) {
            CoreInit.getApp().contentResolver.openFileDescriptor(Uri.parse(source.path), "r")?.use {
                nativeReadDisc("/proc/self/fd/${it.fd}")
            } ?: emptyArray()
        } else nativeReadDisc(source.path)
        val parsed = rows.mapNotNull { row ->
            if (row.size != 13) return@mapNotNull null
            val area = row[0].toIntOrNull() ?: return@mapNotNull null
            val number = row[1].toIntOrNull() ?: return@mapNotNull null
            val channels = row[2].toIntOrNull() ?: return@mapNotNull null
            val start = row[3].toLongOrNull() ?: return@mapNotNull null
            val frames = row[4].toLongOrNull() ?: return@mapNotNull null
            if (area !in 0..1 || number !in 1..255 || channels !in 2..6 || start < 0 || frames <= 0)
                return@mapNotNull null
            Track(area, number, channels, start, frames, row[5] == "1", row[6], row[7], row[8],
                row[9].toIntOrNull() ?: 0, row[10].toIntOrNull() ?: 0, row[11], row[12])
        }
        val area = if (parsed.any { it.area == 0 }) 0 else 1
        val selected = parsed.filter { it.area == area }
        if (selected.isNotEmpty()) SacdIsoSourceRegistry.register(source.path, area)
        return selected.also { if (it.isNotEmpty()) cache[key] = it }
    }

    fun expand(source: AudioFile): List<AudioFile> = project(source, tracks(source))

    internal fun project(source: AudioFile, tracks: List<Track>): List<AudioFile> {
        val metadata = SacdIsoMetadata.withSource(source.path, knownIso = true) { file ->
            val tags = file?.let { com.rawsmusic.core.common.taglib.TagLibBridge.readMetadata(it.absolutePath) }.orEmpty()
            val cover = file?.let {
                val picture = File(it.parentFile, "${it.name}.cover")
                if (picture.isFile || com.rawsmusic.core.common.taglib.TagLibBridge.extractEmbeddedArtworkToFile(
                        it.absolutePath, picture.absolutePath)) picture.absolutePath else ""
            }.orEmpty()
            tags to cover
        }
        val fullTags = metadata.first
        fun tag(vararg names: String): String = names.firstNotNullOfOrNull { name ->
            fullTags.entries.firstOrNull { it.key.equals(name, true) }?.value?.takeIf { it.isNotBlank() }
        }.orEmpty()
        val embeddedCover = metadata.second
        val localCover = if (!source.path.startsWith("content://")) {
            val file = File(source.path)
            listOf(File(file.parentFile, "${file.nameWithoutExtension}.jpg"),
                File(file.parentFile, "cover.jpg"), File(file.parentFile, "folder.jpg"))
                .firstOrNull { it.isFile && it.canRead() }?.absolutePath.orEmpty()
        } else ""
        return tracks.map { track ->
            val offset = track.startFrame * 1000 / 75
            val end = (track.startFrame + track.frames) * 1000 / 75
            val identity = "${source.path}|sacd|${track.area}|${track.number}|${track.startFrame}"
            source.copy(
                id = -(identity.hashCode().toLong().and(0x7fffffffL) + 1),
                title = tag("title").ifBlank { track.title.ifBlank { "Track ${track.number}" } },
                artist = tag("artist").ifBlank { track.artist },
                album = tag("album").ifBlank { track.album.ifBlank { source.album.ifBlank { source.title } } },
                albumArtist = tag("album_artist", "ALBUMARTIST").ifBlank { track.albumArtist },
                composer = tag("composer").ifBlank { track.composer },
                year = tag("year", "DATE").take(4).toIntOrNull() ?: track.year,
                discNumber = tag("disc", "DISCNUMBER").substringBefore('/').toIntOrNull() ?: track.disc,
                trackNumber = tag("track", "TRACKNUMBER").substringBefore('/').toIntOrNull() ?: track.number,
                genre = tag("genre").ifBlank { source.genre }, bpm = tag("BPM").toIntOrNull() ?: source.bpm,
                trackGain = tag("REPLAYGAIN_TRACK_GAIN").substringBefore(' ').toFloatOrNull() ?: source.trackGain,
                trackPeak = tag("REPLAYGAIN_TRACK_PEAK").toFloatOrNull() ?: source.trackPeak,
                albumGain = tag("REPLAYGAIN_ALBUM_GAIN").substringBefore(' ').toFloatOrNull() ?: source.albumGain,
                albumPeak = tag("REPLAYGAIN_ALBUM_PEAK").toFloatOrNull() ?: source.albumPeak,
                channelCount = track.channels, sampleRate = 2_822_400, bitsPerSample = 1,
                bitRate = 2_822_400 * track.channels, format = "SACD-ISO",
                encodingFormat = if (track.dst) "DSD64 (DST)" else "DSD64",
                duration = end - offset, cueOffsetMs = offset, cueEndMs = end,
                cueTrackIndex = track.number,
                albumArtPath = embeddedCover.ifBlank { source.albumArtPath.ifBlank { localCover } },
            )
        }
    }

    /** Minimal container metadata for the existing enrichment/cache pipeline; splitting follows. */
    fun enrichContainer(source: AudioFile): AudioFile {
        val projected = expand(source)
        val first = projected.firstOrNull() ?: return source
        return source.copy(artist = first.albumArtist.ifBlank { first.artist }, album = first.album,
            albumArtist = first.albumArtist, duration = projected.maxOf { it.cueEndMs },
            sampleRate = first.sampleRate, bitsPerSample = 1, channelCount = first.channelCount,
            bitRate = first.bitRate, format = first.format, encodingFormat = first.encodingFormat,
            year = first.year, discNumber = first.discNumber, albumArtPath = first.albumArtPath)
    }

    fun nativeAreaPath(area: Int, source: String): String = "sacd://$area/0/${Uri.encode(source)}"

    /** Native readers reopen /proc/self/fd while this descriptor is alive and own their copy. */
    fun <T> withNativePath(path: String, action: (String) -> T): T {
        val virtual = path.startsWith("sacd://")
        val uri = if (virtual) Uri.parse(path) else null
        val source = if (virtual) uri?.pathSegments?.getOrNull(1).orEmpty() else path
        val area = if (virtual) uri?.host?.toIntOrNull() else SacdIsoSourceRegistry.area(source)
        if (area == null || area !in 0..1 || !source.startsWith("content://")) return action(path)
        return CoreInit.getApp().contentResolver.openFileDescriptor(Uri.parse(source), "r")?.use { descriptor ->
            val track = uri?.pathSegments?.getOrNull(0) ?: "0"
            action("sacd://$area/$track/${Uri.encode("/proc/self/fd/${descriptor.fd}")}")
        } ?: error("SACD ISO 无法打开文件描述符")
    }
    private external fun nativeReadDisc(source: String): Array<Array<String>>
}
