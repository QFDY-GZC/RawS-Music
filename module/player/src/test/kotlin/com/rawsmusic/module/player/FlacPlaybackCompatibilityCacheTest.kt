package com.rawsmusic.module.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FlacPlaybackCompatibilityCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsMetadataChainAfterDeclaredLastBlock() {
        val malformed = malformedFlac()
        val file = temporaryFolder.newFile("malformed.flac").apply { writeBytes(malformed.bytes) }

        val range = RandomAccessFile(file, "r").use {
            FlacPlaybackCompatibilityCache.detectOrphanMetadataRange(it)
        }

        requireNotNull(range)
        assertEquals(malformed.skipStart.toLong(), range.skipStart)
        assertEquals(malformed.skipEnd.toLong(), range.skipEnd)
        assertEquals(2, range.orphanBlockCount)
    }

    @Test
    fun leavesNormalFlacUntouched() {
        val normalBytes = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            writeMetadataBlock(type = 0, isLast = true, payload = ByteArray(34))
            write(byteArrayOf(0xff.toByte(), 0xf8.toByte(), 1, 2, 3, 4))
        }.toByteArray()
        val file = temporaryFolder.newFile("normal.flac").apply { writeBytes(normalBytes) }

        val range = RandomAccessFile(file, "r").use {
            FlacPlaybackCompatibilityCache.detectOrphanMetadataRange(it)
        }
        assertNull(range)

        val resolved = FlacPlaybackCompatibilityCache(
            cacheDirectory = temporaryFolder.newFolder("normal-cache"),
            tag = "FlacCompatTest",
        ).resolve(file.absolutePath)
        assertEquals(file.absolutePath, resolved)
    }

    @Test
    fun materializesCacheCopyWithoutChangingEncodedAudioBytes() {
        val malformed = malformedFlac()
        val source = temporaryFolder.newFile("orphan.flac").apply { writeBytes(malformed.bytes) }
        val originalBefore = source.readBytes()
        val cache = temporaryFolder.newFolder("compat-cache")

        val resolvedPath = FlacPlaybackCompatibilityCache(cache, "FlacCompatTest")
            .resolve(source.absolutePath)
        val resolved = File(resolvedPath)

        assertNotEquals(source.absolutePath, resolved.absolutePath)
        assertTrue(resolved.isFile)
        assertArrayEquals(originalBefore, source.readBytes())
        val expected = originalBefore.copyOfRange(0, malformed.skipStart) +
            originalBefore.copyOfRange(malformed.skipEnd, originalBefore.size)
        assertArrayEquals(expected, resolved.readBytes())
        assertEquals(0xff.toByte(), resolved.readBytes()[malformed.skipStart])
        assertEquals(0xf8.toByte(), resolved.readBytes()[malformed.skipStart + 1])
    }

    private data class MalformedFlac(
        val bytes: ByteArray,
        val skipStart: Int,
        val skipEnd: Int,
    )

    private fun malformedFlac(): MalformedFlac {
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray())
        output.writeMetadataBlock(type = 0, isLast = false, payload = ByteArray(34))
        output.writeMetadataBlock(type = 4, isLast = false, payload = byteArrayOf(1, 2, 3))
        output.writeMetadataBlock(type = 1, isLast = true, payload = ByteArray(5))
        val skipStart = output.size()
        output.writeMetadataBlock(type = 6, isLast = false, payload = byteArrayOf(9, 8, 7, 6))
        output.writeMetadataBlock(type = 1, isLast = true, payload = ByteArray(7))
        val skipEnd = output.size()
        output.write(byteArrayOf(0xff.toByte(), 0xf8.toByte(), 10, 11, 12, 13, 14, 15))
        return MalformedFlac(output.toByteArray(), skipStart, skipEnd)
    }

    private fun ByteArrayOutputStream.writeMetadataBlock(
        type: Int,
        isLast: Boolean,
        payload: ByteArray,
    ) {
        val first = type or if (isLast) 0x80 else 0
        write(first)
        write((payload.size ushr 16) and 0xff)
        write((payload.size ushr 8) and 0xff)
        write(payload.size and 0xff)
        write(payload)
    }
}
