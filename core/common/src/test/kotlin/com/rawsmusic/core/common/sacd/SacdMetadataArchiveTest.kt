package com.rawsmusic.core.common.sacd

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SacdMetadataArchiveTest {
    private fun wave(dir: File): File {
        val payload = "長いタイトル 🪷\u0000CUSTOM=第一;第二\u0000[00:01.00]歌詞".toByteArray(Charsets.UTF_8)
        val size = 12 + 8 + payload.size + payload.size % 2 + 8 + 65536
        val bytes = ByteArray(size)
        val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        "RIFF".toByteArray().copyInto(bytes); le.putInt(4, size - 8)
        "WAVEINFO".toByteArray().copyInto(bytes, 8); le.putInt(16, payload.size)
        payload.copyInto(bytes, 20)
        val data = 20 + payload.size + payload.size % 2
        "data".toByteArray().copyInto(bytes, data); le.putInt(data + 4, 65536)
        bytes.fill(0x39, data + 8)
        return File(dir, "source.wav").also { it.writeBytes(bytes) }
    }
    private fun image(dir: File) = File(dir, "disc.iso").also {
        val bytes = ByteArray(700 * 2048)
        "SACDMTOC".toByteArray().copyInto(bytes, 510 * 2048)
        it.writeBytes(bytes)
    }
    private fun cleanup(dir: File) {
        check(dir.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
        dir.deleteRecursively()
    }

    @Test fun rawMetadataSurvivesWithoutEmbeddingPcmAndWithoutExternalSidecars() {
        val dir = Files.createTempDirectory("sacd-metadata").toFile()
        try {
            val source = wave(dir); val original = source.readBytes(); val iso = image(dir)
            val audio = iso.readBytes()
            val descriptor = SacdMetadataArchive.append(source, iso)
            assertTrue(descriptor.payloadBytes < 4096)
            assertTrue(SacdMetadataArchive.verify(source, iso))
            assertEquals(0L, iso.length() % 2048)
            assertArrayEquals(audio, iso.readBytes().copyOf(audio.size))
            source.delete()
            val restored = requireNotNull(SacdMetadataArchive.restore(iso, File(dir, "cache")))
            val bytes = restored.readBytes()
            assertEquals(original.size, bytes.size)
            assertArrayEquals(original.copyOf(original.size - 65536), bytes.copyOf(bytes.size - 65536))
            assertTrue(bytes.takeLast(65536).all { it == 0.toByte() })
        } finally { cleanup(dir) }
    }

    @Test fun unknownContainerIsPreservedExactlyInsteadOfDroppingOpaqueData() {
        val dir = Files.createTempDirectory("sacd-metadata-opaque").toFile()
        try {
            val source = File(dir, "source.wv").also { it.writeBytes(ByteArray(4097) { (it * 7).toByte() }) }
            val original = source.readBytes(); val iso = image(dir)
            SacdMetadataArchive.append(source, iso)
            val restored = requireNotNull(SacdMetadataArchive.restore(iso, File(dir, "cache")))
            assertArrayEquals(original, restored.readBytes())
        } finally { cleanup(dir) }
    }

    @Test fun corruptionCannotPassStrictVerificationOrPopulateCache() {
        val dir = Files.createTempDirectory("sacd-metadata-corrupt").toFile()
        try {
            val source = wave(dir); val iso = image(dir)
            val descriptor = SacdMetadataArchive.append(source, iso)
            RandomAccessFile(iso, "rw").use { it.seek(descriptor.payloadOffset + 40); it.write(0xff) }
            assertFalse(SacdMetadataArchive.verify(source, iso))
            assertThrows(java.io.IOException::class.java) {
                SacdMetadataArchive.restore(iso, File(dir, "cache"))
            }
            assertTrue(File(dir, "cache").listFiles().orEmpty().none { !it.name.endsWith(".part") })
        } finally { cleanup(dir) }
    }

    @Test fun cancellationLeavesTheExistingIsoIntactAndLegacyIsoHasNoSnapshot() {
        val dir = Files.createTempDirectory("sacd-metadata-cancel").toFile()
        try {
            val iso = image(dir); val original = iso.readBytes()
            assertNull(SacdMetadataArchive.restore(iso, File(dir, "cache")))
            assertThrows(CancellationException::class.java) {
                SacdMetadataArchive.append(wave(dir), iso, isCancelled = { true })
            }
            assertArrayEquals(original, iso.readBytes())
        } finally { cleanup(dir) }
    }
}
