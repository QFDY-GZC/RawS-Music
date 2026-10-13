package com.rawsmusic.transcode

import android.app.Application
import com.rawsmusic.core.common.CoreInit
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.sacd.SacdIsoBridge
import com.rawsmusic.core.common.sacd.SacdMetadataArchive
import com.rawsmusic.core.common.taglib.TagLibBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Android app_process harness; uses the real TagLib JNI and common metadata readers. */
object SacdFullMetadataHarness {
    private fun syncSafe(value: Int) = byteArrayOf((value ushr 21 and 127).toByte(),
        (value ushr 14 and 127).toByte(), (value ushr 7 and 127).toByte(), (value and 127).toByte())

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0])
        val application = object : Application() { override fun getCacheDir() = File(root, "metadata-cache") }
        CoreInit::class.java.getDeclaredField("app").also { it.isAccessible = true }.set(null, application)
        val title = "長い日本語と中文タイトル🪷".repeat(30)
        val lyrics = "[00:00.10]第一句 日文と中文 🪷\n[00:01.00]第二句"
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7ioAAAAASUVORK5CYII=")
        val frames = ByteArrayOutputStream()
        fun frame(id: String, payload: ByteArray) {
            frames.write(id.toByteArray()); frames.write(syncSafe(payload.size)); frames.write(byteArrayOf(0, 0)); frames.write(payload)
        }
        fun text(id: String, value: String) = frame(id, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))
        text("TIT2", title); text("TPE1", "演唱者甲\u0000演唱者乙"); text("TALB", "原始专辑")
        text("TPE2", "完整专辑艺术家"); text("TCOM", "作曲者"); text("TRCK", "7/12")
        text("TXXX", "RAWS_CUSTOM\u0000第一值\u0000第二值")
        frame("USLT", byteArrayOf(3) + "eng\u0000".toByteArray() + lyrics.toByteArray(Charsets.UTF_8))
        frame("APIC", byteArrayOf(3) + "image/png\u0000".toByteArray() + byteArrayOf(3) +
            "原始封面描述\u0000".toByteArray(Charsets.UTF_8) + png)
        frame("PRIV", "rawsmusic.original\u0000".toByteArray() + byteArrayOf(0, -1, 12, 34, 0, 56))
        val id3 = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncSafe(frames.size()) + frames.toByteArray()
        val audioSize = 48000 * 2 * 2 * 2
        val size = 44 + audioSize + 8 + id3.size + id3.size % 2
        val bytes = ByteArray(size); val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        "RIFF".toByteArray().copyInto(bytes); buffer.putInt(4, size - 8)
        "WAVEfmt ".toByteArray().copyInto(bytes, 8); buffer.putInt(16, 16)
        buffer.putShort(20, 1); buffer.putShort(22, 2); buffer.putInt(24, 48000)
        buffer.putInt(28, 192000); buffer.putShort(32, 4); buffer.putShort(34, 16)
        "data".toByteArray().copyInto(bytes, 36); buffer.putInt(40, audioSize)
        "id3 ".toByteArray().copyInto(bytes, 44 + audioSize)
        buffer.putInt(48 + audioSize, id3.size); id3.copyInto(bytes, 52 + audioSize)
        val source = File(root, "rich-source.wav").also { it.writeBytes(bytes) }
        val output = File(root, "full-tags.iso")
        File(root, "encoded.iso").copyTo(output, overwrite = true)
        val originalProperties = TagLibBridge.readAllTextProperties(source.path)
        check(originalProperties["TITLE"]?.first() == title) { "Native source Unicode read" }
        check(originalProperties["RAWS_CUSTOM"] == listOf("第一值", "第二值")) { "Multi-value source" }
        val inspected = TagLibBridge.inspectMetadataForTranscode(source.path)
        check(inspected.pictureCount == 1 && inspected.unsupportedOpaqueItems > 0) { "Picture/PRIV source inspection: $inspected" }
        val report = TagLibBridge.migrateMetadataForTranscode(source.path, output.path)
        check(report.strictOk && report.verifyOk && report.unsupportedBinaryItems == 0) { "Strict migration: $report" }
        check(TagLibBridge.readAllTextProperties(output.path) == originalProperties) { "Complete property map mismatch" }
        val metadata = TagLibBridge.readMetadata(output.path)
        check(metadata["title"] == title) { "Display Unicode title mismatch" }
        check(metadata["LYRICS"] == lyrics || metadata["lyrics"] == lyrics) { "Embedded lyric mismatch" }
        check(metadata["sample_rate"] == "2822400" && metadata["bits_per_sample"] == "1") { "ISO geometry replaced by source PCM" }
        val picture = File(root, "restored-cover.png")
        check(TagLibBridge.extractEmbeddedArtworkToFile(output.path, picture.path))
        check(picture.readBytes().contentEquals(png)) { "Picture payload mismatch" }
        val song = SacdIsoBridge.expand(AudioFile(path = output.path, format = "SACD-ISO", fileSize = output.length())).single()
        check(song.title == title && song.trackNumber == 7 && song.cueTrackIndex == 1 && song.bitsPerSample == 1)
        check(song.albumArtist == "完整专辑艺术家" && song.composer == "作曲者" && File(song.albumArtPath).isFile)
        check(SacdMetadataArchive.verify(source, output))
        NativeAudioTranscoder.createSession().use { session ->
            val formats = listOf("flac" to "flac", "alac" to "m4a", "aiff" to "aiff",
                "wavpack" to "wv", "tta" to "tta", "opus" to "opus", "vorbis" to "ogg", "dsf" to "dsf")
            for ((format, extension) in formats) {
                val sample = File(root, "metadata-source-$format.$extension")
                val result = when (format) {
                    "dsf" -> session.transcodeDsf(source.path, sample.path, AudioTranscodeDsdRate.DSD64)
                    "opus", "vorbis" -> session.transcodeLossy(source.path, sample.path, format, 48000, 128)
                    else -> session.transcodeLossless(source.path, sample.path, format, 44100, 16, 5, false)
                }
                if (result != 0) { println("SOURCE_FIXTURE_ENCODER_UNAVAILABLE=$format code=$result"); continue }
                val setup = TagLibBridge.migrateMetadataForTranscode(source.path, sample.path)
                check(setup.supported && setup.saveOk && setup.verifyOk) { "Source fixture tags $format: $setup" }
                val expected = TagLibBridge.readAllTextProperties(sample.path)
                val taggedIso = File(root, "metadata-$format.iso")
                File(root, "encoded.iso").copyTo(taggedIso, overwrite = true)
                val migrated = TagLibBridge.migrateMetadataForTranscode(sample.path, taggedIso.path)
                check(migrated.strictOk && TagLibBridge.readAllTextProperties(taggedIso.path) == expected) {
                    "Source container snapshot $format: $migrated"
                }
                val recovered = File(root, "cover-$format.png")
                check(TagLibBridge.extractEmbeddedArtworkToFile(taggedIso.path, recovered.path) && recovered.readBytes().contentEquals(png))
                println("SACD_SOURCE_FORMAT_STRICT=$format payloadBytes=" + SacdMetadataArchive.describe(taggedIso)?.payloadBytes)
            }
        }
        source.delete()
        File(application.cacheDir, "sacd_metadata").listFiles().orEmpty().forEach {
            check(it.canonicalPath.startsWith(root.canonicalPath + File.separator))
            check(it.isFile && it.delete())
        }
        check(TagLibBridge.readAllTextProperties(output.path) == originalProperties) { "Depends on external source" }
        println("SACD_FULL_METADATA_STRICT=true Unicode=true MultiValue=true Lyrics=true CoverBytes=true OpaquePRIV=true SourceDeleted=true")
        println("SACD_METADATA_PAYLOAD_BYTES=" + SacdMetadataArchive.describe(output)?.payloadBytes)
    }
}
