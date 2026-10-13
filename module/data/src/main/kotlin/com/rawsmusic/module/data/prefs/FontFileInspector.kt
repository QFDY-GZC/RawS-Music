package com.rawsmusic.module.data.prefs

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Minimal OpenType/TTC table inspection used by the font settings pipeline.
 *
 * Android's font APIs can silently ignore an unsupported variation axis. RawSMusic therefore
 * needs to know whether a user-selected *file* actually exposes a `wght` axis before advertising
 * that file to Compose as a complete 100..900 family. The parser intentionally reads only the
 * sfnt table directory and `fvar`; it does not decode glyph data and is safe to run on the IO
 * dispatcher while the font catalog/settings screen is opened.
 */
object FontFileInspector {
    data class VariationAxis(
        val tag: String,
        val minValue: Float,
        val defaultValue: Float,
        val maxValue: Float,
    )

    data class Capabilities(
        val axes: Map<String, VariationAxis> = emptyMap(),
        val staticWeight: Int? = null,
    ) {
        val weightAxis: VariationAxis?
            get() = axes["wght"]

        val weightRange: IntRange?
            get() = weightAxis?.let { axis ->
                val min = axis.minValue.roundToInt().coerceIn(1, 1000)
                val max = axis.maxValue.roundToInt().coerceIn(min, 1000)
                min..max
            }

        val nativeWeightRange: IntRange?
            get() = weightRange ?: staticWeight?.let { it..it }

        val hasVariableWeight: Boolean
            get() = weightAxis != null
    }

    private data class CacheKey(
        val path: String,
        val length: Long,
        val lastModified: Long,
    )

    private val cache = ConcurrentHashMap<CacheKey, Capabilities>()

    fun inspect(path: String): Capabilities {
        if (path.isBlank()) return Capabilities()
        val file = File(path)
        if (!file.isFile) return Capabilities()
        val key = CacheKey(file.absolutePath, file.length(), file.lastModified())
        return cache[key] ?: readCapabilities(file).also { cache[key] = it }
    }

    private fun readCapabilities(file: File): Capabilities = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12L) return@use Capabilities()

            val firstTag = raf.readInt()
            val sfntOffset = if (firstTag == TAG_TTCF) {
                if (raf.length() < 16L) return@use Capabilities()
                raf.readInt() // TTC version
                val numFonts = raf.readUnsignedIntCompat()
                if (numFonts <= 0L || numFonts > 1024L) return@use Capabilities()
                raf.readUnsignedIntCompat()
            } else {
                0L
            }

            if (sfntOffset < 0L || sfntOffset + 12L > raf.length()) return@use Capabilities()
            raf.seek(sfntOffset + 4L)
            val numTables = raf.readUnsignedShort()
            if (numTables <= 0 || numTables > 4096) return@use Capabilities()
            raf.skipBytes(6)

            var fvarOffset = -1L
            var fvarLength = 0L
            var os2Offset = -1L
            var os2Length = 0L
            repeat(numTables) {
                if (raf.filePointer + 16L > raf.length()) return@repeat
                val tag = raf.readInt()
                raf.readInt() // checksum
                val tableOffset = raf.readUnsignedIntCompat()
                val tableLength = raf.readUnsignedIntCompat()
                if (tag == TAG_FVAR) {
                    fvarOffset = tableOffset
                    fvarLength = tableLength
                } else if (tag == TAG_OS2) {
                    os2Offset = tableOffset
                    os2Length = tableLength
                }
            }

            val staticWeight = if (
                os2Offset >= 0L &&
                os2Length >= 6L &&
                os2Offset + 6L <= raf.length()
            ) {
                raf.seek(os2Offset + 4L)
                raf.readUnsignedShort().coerceIn(1, 1000)
            } else {
                null
            }

            if (fvarOffset < 0L || fvarLength < 16L || fvarOffset + fvarLength > raf.length()) {
                return@use Capabilities(staticWeight = staticWeight)
            }

            raf.seek(fvarOffset)
            raf.readUnsignedShort() // majorVersion
            raf.readUnsignedShort() // minorVersion
            val axesArrayOffset = raf.readUnsignedShort()
            raf.readUnsignedShort() // reserved
            val axisCount = raf.readUnsignedShort()
            val axisSize = raf.readUnsignedShort()
            raf.readUnsignedShort() // instanceCount
            raf.readUnsignedShort() // instanceSize
            if (axisCount <= 0 || axisCount > 64 || axisSize < 20) {
                return@use Capabilities(staticWeight = staticWeight)
            }

            val axesStart = fvarOffset + axesArrayOffset
            val axesEnd = axesStart + axisCount.toLong() * axisSize.toLong()
            if (axesStart < fvarOffset || axesEnd > fvarOffset + fvarLength || axesEnd > raf.length()) {
                return@use Capabilities(staticWeight = staticWeight)
            }

            val axes = LinkedHashMap<String, VariationAxis>(axisCount)
            repeat(axisCount) { index ->
                raf.seek(axesStart + index.toLong() * axisSize.toLong())
                val tag = raf.readTag()
                val min = raf.readFixed16_16()
                val default = raf.readFixed16_16()
                val max = raf.readFixed16_16()
                raf.readUnsignedShort() // flags
                raf.readUnsignedShort() // axisNameID
                if (tag.length == 4 && min.isFinite() && default.isFinite() && max.isFinite() && max >= min) {
                    axes[tag] = VariationAxis(tag, min, default, max)
                }
            }
            Capabilities(
                axes = axes,
                staticWeight = staticWeight,
            )
        }
    }.getOrDefault(Capabilities())

    private fun RandomAccessFile.readUnsignedIntCompat(): Long = readInt().toLong() and 0xFFFF_FFFFL

    private fun RandomAccessFile.readFixed16_16(): Float = readInt() / 65536f

    private fun RandomAccessFile.readTag(): String {
        val value = readInt()
        return buildString(4) {
            append(((value ushr 24) and 0xFF).toChar())
            append(((value ushr 16) and 0xFF).toChar())
            append(((value ushr 8) and 0xFF).toChar())
            append((value and 0xFF).toChar())
        }
    }

    private const val TAG_TTCF = 0x74746366 // 'ttcf'
    private const val TAG_FVAR = 0x66766172 // 'fvar'
    private const val TAG_OS2 = 0x4F532F32 // 'OS/2'
}
