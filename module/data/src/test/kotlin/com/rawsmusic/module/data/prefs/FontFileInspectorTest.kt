package com.rawsmusic.module.data.prefs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontFileInspectorTest {
    @Test
    fun readsWeightAxisFromSfntFvarTable() {
        val file = Files.createTempFile("raw-font-vf", ".ttf").toFile()
        try {
            file.writeBytes(variableSfnt(sfntOffset = 0))
            val capabilities = FontFileInspector.inspect(file.absolutePath)

            assertEquals(100..900, capabilities.weightRange)
            assertEquals(400f, capabilities.weightAxis?.defaultValue)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsWeightAxisFromFirstTtcFace() {
        val file = Files.createTempFile("raw-font-vf", ".ttc").toFile()
        try {
            val sfntOffset = 16
            val sfnt = variableSfnt(sfntOffset = sfntOffset)
            val buffer = ByteBuffer.allocate(sfnt.size)
                .order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(0x74746366) // ttcf
            buffer.putInt(0x00010000)
            buffer.putInt(1)
            buffer.putInt(sfntOffset)
            buffer.position(sfntOffset)
            buffer.put(sfnt, sfntOffset, sfnt.size - sfntOffset)
            file.writeBytes(buffer.array())

            assertEquals(100..900, FontFileInspector.inspect(file.absolutePath).weightRange)
        } finally {
            file.delete()
        }
    }

    @Test
    fun staticSfntIsNotMisreportedAsVariable() {
        val file = Files.createTempFile("raw-font-static", ".ttf").toFile()
        try {
            val buffer = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(0x00010000)
            buffer.putShort(0)
            buffer.putShort(0)
            buffer.putShort(0)
            buffer.putShort(0)
            file.writeBytes(buffer.array())

            assertNull(FontFileInspector.inspect(file.absolutePath).weightRange)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsNativeStaticWeightFromOs2Table() {
        val file = Files.createTempFile("raw-font-static-weight", ".ttf").toFile()
        try {
            val tableDirectorySize = 12 + 16
            val os2Size = 6
            val buffer = ByteBuffer.allocate(tableDirectorySize + os2Size)
                .order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(0x00010000)
            buffer.putShort(1)
            buffer.putShort(0)
            buffer.putShort(0)
            buffer.putShort(0)
            buffer.putInt(0x4F532F32) // OS/2
            buffer.putInt(0)
            buffer.putInt(tableDirectorySize)
            buffer.putInt(os2Size)
            buffer.position(tableDirectorySize)
            buffer.putShort(0) // version
            buffer.putShort(0) // xAvgCharWidth
            buffer.putShort(600) // usWeightClass
            file.writeBytes(buffer.array())

            val capabilities = FontFileInspector.inspect(file.absolutePath)
            assertNull(capabilities.weightRange)
            assertEquals(600, capabilities.staticWeight)
            assertEquals(600..600, capabilities.nativeWeightRange)
        } finally {
            file.delete()
        }
    }

    private fun variableSfnt(sfntOffset: Int): ByteArray {
        val tableDirectorySize = 12 + 16
        val fvarSize = 16 + 20
        val fvarOffset = sfntOffset + tableDirectorySize
        val buffer = ByteBuffer.allocate(sfntOffset + tableDirectorySize + fvarSize)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.position(sfntOffset)
        buffer.putInt(0x00010000)
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putInt(0x66766172) // fvar
        buffer.putInt(0)
        buffer.putInt(fvarOffset)
        buffer.putInt(fvarSize)
        buffer.position(fvarOffset)
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(16)
        buffer.putShort(0)
        buffer.putShort(1)
        buffer.putShort(20)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putInt(0x77676874) // wght
        buffer.putInt(100 shl 16)
        buffer.putInt(400 shl 16)
        buffer.putInt(900 shl 16)
        buffer.putShort(0)
        buffer.putShort(0)
        return buffer.array()
    }
}
