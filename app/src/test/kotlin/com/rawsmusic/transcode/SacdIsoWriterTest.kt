package com.rawsmusic.transcode

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SacdIsoWriterTest {
    private fun dsf(dir: File, bits: Int = 8, rate: Int = 2_822_400, channels: Int = 2): File {
        val bytes = 4704 * 75 + 19
        val blocks = (bytes + 4095) / 4096
        val groupBytes = 4096 * channels
        val data = ByteArray(92 + blocks * groupBytes)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun text(at: Int, value: String) { value.toByteArray().copyInto(data, at) }
        text(0, "DSD "); buffer.putLong(4, 28); buffer.putLong(12, data.size.toLong())
        text(28, "fmt "); buffer.putLong(32, 52); buffer.putInt(40, 1)
        buffer.putInt(48, channels); buffer.putInt(52, channels); buffer.putInt(56, rate)
        buffer.putInt(60, bits); buffer.putLong(64, bytes * 8L); buffer.putInt(72, 4096)
        text(80, "data"); buffer.putLong(84, blocks * groupBytes.toLong() + 12)
        for (block in 0 until blocks) {
            data.fill(0x12, 92 + block * groupBytes, 92 + block * groupBytes + 4096)
            if (channels == 2) data.fill(0x34, 92 + block * groupBytes + 4096, 92 + (block + 1) * groupBytes)
        }
        return File(dir, "source.dsf").also { it.writeBytes(data) }
    }

    @Test fun writesSingleTrackTocAndInterleavedAudioWithFramePadding() {
        val dir = Files.createTempDirectory("sacd-writer").toFile()
        try {
            val output = File(dir, "converted.iso")
            val result = SacdIsoWriter.write(dsf(dir), output, "Test song", "Test artist", "Test album")
            assertEquals(76L, result.frames)
            val bytes = output.readBytes()
            assertEquals("SACDMTOC", String(bytes, 510 * 2048, 8))
            assertEquals("TWOCHTOC", String(bytes, 600 * 2048, 8))
            assertEquals(0x12, bytes[700 * 2048 + 32].toInt() and 255)
            assertEquals(0x34, bytes[700 * 2048 + 33].toInt() and 255)
            assertEquals(2, bytes[605 * 2048 + 4096 + 4 + 12].toInt())
            assertEquals(0x69, bytes.last().toInt() and 255)
            assertEquals((700L + 26 * 14) * 2048, output.length())
            // Saved for the actual Android SACD reader/decoder interoperability check.
            File("../.codex_tmp/sacd-writer-output.iso").also {
                it.parentFile?.mkdirs(); output.copyTo(it, overwrite = true)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun normalizesLsbfAndRejectsNonSacdClocks() {
        val dir = Files.createTempDirectory("sacd-writer-bits").toFile()
        try {
            val output = File(dir, "converted.iso")
            SacdIsoWriter.write(dsf(dir, bits = 1), output, "Song", "Artist", "Album")
            assertEquals(0x48, output.readBytes()[700 * 2048 + 32].toInt() and 255)
            assertThrows(IllegalArgumentException::class.java) {
                SacdIsoWriter.write(dsf(dir, rate = 3_072_000), File(dir, "bad.iso"), "", "", "")
            }
            assertFalse(File(dir, "bad.iso").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationDoesNotPublishAnImage() {
        val dir = Files.createTempDirectory("sacd-writer-cancel").toFile()
        try {
            val output = File(dir, "cancelled.iso")
            assertThrows(CancellationException::class.java) {
                SacdIsoWriter.write(dsf(dir), output, "", "", "", isCancelled = { true })
            }
            assertFalse(output.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun japaneseTocUsesTheJapaneseCharsetWithoutGrowingAudioBuffers() {
        val dir = Files.createTempDirectory("sacd-writer-japanese").toFile()
        try {
            val output = File(dir, "japanese.iso")
            val result = SacdIsoWriter.write(dsf(dir), output, "ハレルヤルーヤ", "横山だいすけ", "歌の世界")
            assertEquals("ハレルヤルーヤ", result.title)
            assertEquals("横山だいすけ", result.artist)
            assertEquals(3, output.readBytes()[600 * 2048 + 90].toInt())
            output.copyTo(File("../.codex_tmp/sacd-writer-japanese.iso"), overwrite = true)
        } finally { dir.deleteRecursively() }
    }

    @Test fun monoDuplicatesChannelsAndMidWriteCancellationRetiresPartial() {
        val dir = Files.createTempDirectory("sacd-writer-mono").toFile()
        try {
            val input = dsf(dir, channels = 1)
            val original = input.readBytes()
            val output = File(dir, "mono.iso")
            SacdIsoWriter.write(input, output, "Song", "Artist", "Album")
            val bytes = output.readBytes()
            assertEquals(bytes[700 * 2048 + 32], bytes[700 * 2048 + 33])
            assertArrayEquals(original, input.readBytes())
            var checks = 0
            val cancelled = File(dir, "mid-write.iso")
            assertThrows(CancellationException::class.java) {
                SacdIsoWriter.write(input, cancelled, "", "", "", isCancelled = { ++checks > 12 })
            }
            assertFalse(cancelled.exists())
            assertArrayEquals(original, input.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
