package com.rawsmusic.core.common.artwork

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EmbeddedArtworkRegionTest {
    @Test
    fun readsId3v23FrontCoverDirectlyFromDsf() {
        val image = fakeJpeg(2_048)
        val file = createDsfFile(id3Version = 3, image = image)
        try {
            val handle = assertNotNull(EmbeddedArtworkRegion.find(file.absolutePath))
            assertEquals("dsf-front", handle.format)
            assertEquals("image/jpeg", handle.mime)
            assertEquals(image.size.toLong(), handle.length)
            assertContentEquals(image.copyOfRange(0, 4), handle.openStream().use { it.readExact(4) })
        } finally {
            EmbeddedArtworkRegion.invalidate(file.absolutePath)
            file.delete()
        }
    }

    @Test
    fun readsId3v24FrontCoverDirectlyFromDsf() {
        val image = fakePng(2_048)
        val file = createDsfFile(id3Version = 4, image = image)
        try {
            val handle = assertNotNull(EmbeddedArtworkRegion.find(file.absolutePath))
            assertEquals("dsf-front", handle.format)
            assertEquals("image/png", handle.mime)
            assertEquals(image.size.toLong(), handle.length)
            assertContentEquals(image.copyOfRange(0, 8), handle.openStream().use { it.readExact(8) })
        } finally {
            EmbeddedArtworkRegion.invalidate(file.absolutePath)
            file.delete()
        }
    }

    @Test
    fun readsId3v23FrontCoverFromTrailingWavId3Chunk() {
        val image = fakeJpeg(2_048)
        val file = createWavFile(id3Version = 3, image = image, chunkId = "ID3 ")
        try {
            val handle = assertNotNull(EmbeddedArtworkRegion.find(file.absolutePath))
            assertEquals("wav-front", handle.format)
            assertEquals("image/jpeg", handle.mime)
            assertEquals(image.size.toLong(), handle.length)
            assertContentEquals(image.copyOfRange(0, 4), handle.openStream().use { it.readExact(4) })
        } finally {
            EmbeddedArtworkRegion.invalidate(file.absolutePath)
            file.delete()
        }
    }

    @Test
    fun readsLowercaseId3v24ChunkAfterOddSizedWavChunk() {
        val image = fakePng(2_048)
        val file = createWavFile(id3Version = 4, image = image, chunkId = "id3 ", oddDataSize = true)
        try {
            val handle = assertNotNull(EmbeddedArtworkRegion.find(file.absolutePath))
            assertEquals("wav-front", handle.format)
            assertEquals("image/png", handle.mime)
            assertEquals(image.size.toLong(), handle.length)
            assertContentEquals(image.copyOfRange(0, 8), handle.openStream().use { it.readExact(8) })
        } finally {
            EmbeddedArtworkRegion.invalidate(file.absolutePath)
            file.delete()
        }
    }

    @Test
    fun readsStandardApev2FrontCoverDirectlyFromApe() {
        val image = fakeJpeg(2_048)
        val file = createApeFile(image)
        try {
            val handle = assertNotNull(EmbeddedArtworkRegion.find(file.absolutePath))
            assertEquals("ape-front", handle.format)
            assertEquals("image/jpeg", handle.mime)
            assertEquals(image.size.toLong(), handle.length)
            assertContentEquals(image.copyOfRange(0, 4), handle.openStream().use { it.readExact(4) })
        } finally {
            EmbeddedArtworkRegion.invalidate(file.absolutePath)
            file.delete()
        }
    }

    private fun createDsfFile(id3Version: Int, image: ByteArray): File {
        val apicPayload = ByteArrayOutputStream().apply {
            write(0)
            write("image/${if (image[0] == 0x89.toByte()) "png" else "jpeg"}".toByteArray(Charsets.US_ASCII))
            write(0)
            write(3)
            write(0)
            write(image)
        }.toByteArray()

        val frame = ByteArrayOutputStream().apply {
            write("APIC".toByteArray(Charsets.US_ASCII))
            if (id3Version == 4) writeSynchsafe(apicPayload.size) else writeUInt32Be(apicPayload.size)
            write(0)
            write(0)
            write(apicPayload)
        }.toByteArray()

        val id3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(id3Version)
            write(0)
            write(0)
            writeSynchsafe(frame.size)
            write(frame)
        }.toByteArray()

        val fileBytes = ByteArrayOutputStream().apply {
            write("DSD ".toByteArray(Charsets.US_ASCII))
            writeUInt64Le(28L)
            writeUInt64Le(28L + id3.size)
            writeUInt64Le(28L)
            write(id3)
        }.toByteArray()

        return Files.createTempFile("raws-art-", ".dsf").toFile().apply { writeBytes(fileBytes) }
    }

    private fun createWavFile(
        id3Version: Int,
        image: ByteArray,
        chunkId: String,
        oddDataSize: Boolean = false,
    ): File {
        val apicPayload = ByteArrayOutputStream().apply {
            write(0)
            write("image/${if (image[0] == 0x89.toByte()) "png" else "jpeg"}".toByteArray(Charsets.US_ASCII))
            write(0)
            write(3)
            write(0)
            write(image)
        }.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            write("APIC".toByteArray(Charsets.US_ASCII))
            if (id3Version == 4) writeSynchsafe(apicPayload.size) else writeUInt32Be(apicPayload.size)
            write(0)
            write(0)
            write(apicPayload)
        }.toByteArray()
        val id3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(id3Version)
            write(0)
            write(0)
            writeSynchsafe(frame.size)
            write(frame)
        }.toByteArray()

        val body = ByteArrayOutputStream().apply {
            write("fmt ".toByteArray(Charsets.US_ASCII))
            writeUInt32Le(16L)
            write(ByteArray(16))

            val pcmBytes = if (oddDataSize) 5 else 4
            write("data".toByteArray(Charsets.US_ASCII))
            writeUInt32Le(pcmBytes.toLong())
            write(ByteArray(pcmBytes))
            if ((pcmBytes and 1) != 0) write(0)

            write(chunkId.toByteArray(Charsets.US_ASCII))
            writeUInt32Le(id3.size.toLong())
            write(id3)
            if ((id3.size and 1) != 0) write(0)
        }.toByteArray()
        val bytes = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            writeUInt32Le(body.size + 4L)
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write(body)
        }.toByteArray()

        return Files.createTempFile("raws-art-", ".wav").toFile().apply { writeBytes(bytes) }
    }

    private fun createApeFile(image: ByteArray): File {
        val itemValue = ByteArrayOutputStream().apply {
            write("Cover.jpg".toByteArray(Charsets.UTF_8))
            write(0)
            write(image)
        }.toByteArray()
        val item = ByteArrayOutputStream().apply {
            writeUInt32Le(itemValue.size.toLong())
            writeUInt32Le(2L)
            write("Cover Art (Front)".toByteArray(Charsets.UTF_8))
            write(0)
            write(itemValue)
        }.toByteArray()
        val footer = ByteArrayOutputStream().apply {
            write("APETAGEX".toByteArray(Charsets.US_ASCII))
            writeUInt32Le(2_000L)
            writeUInt32Le((item.size + 32).toLong())
            writeUInt32Le(1L)
            writeUInt32Le(0x8000_0000L)
            write(ByteArray(8))
        }.toByteArray()
        val bytes = ByteArrayOutputStream().apply {
            write(ByteArray(512))
            write(item)
            write(footer)
        }.toByteArray()
        return Files.createTempFile("raws-art-", ".ape").toFile().apply { writeBytes(bytes) }
    }

    private fun fakeJpeg(size: Int): ByteArray = ByteArray(size).apply {
        this[0] = 0xFF.toByte()
        this[1] = 0xD8.toByte()
        this[2] = 0xFF.toByte()
        this[3] = 0xE0.toByte()
    }

    private fun fakePng(size: Int): ByteArray = ByteArray(size).apply {
        val magic = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        magic.copyInto(this)
    }

    private fun ByteArrayOutputStream.writeSynchsafe(value: Int) {
        write((value ushr 21) and 0x7F)
        write((value ushr 14) and 0x7F)
        write((value ushr 7) and 0x7F)
        write(value and 0x7F)
    }

    private fun ByteArrayOutputStream.writeUInt32Be(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeUInt32Le(value: Long) {
        repeat(4) { index -> write(((value ushr (index * 8)) and 0xFF).toInt()) }
    }

    private fun ByteArrayOutputStream.writeUInt64Le(value: Long) {
        repeat(8) { index -> write(((value ushr (index * 8)) and 0xFF).toInt()) }
    }

    private fun java.io.InputStream.readExact(length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(out, offset, length - offset)
            if (count < 0) error("Unexpected EOF")
            offset += count
        }
        return out
    }
}
