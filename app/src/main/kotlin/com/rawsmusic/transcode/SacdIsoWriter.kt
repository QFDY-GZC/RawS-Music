package com.rawsmusic.transcode

import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.CancellationException

/** Single-track, plain DSD64 SACD image. All audio IO is bounded to DSF blocks/sectors. */
internal object SacdIsoWriter {
    private const val SECTOR = 2048
    private const val AUDIO_LBA = 700
    data class Result(val frames: Long, val title: String, val artist: String, val album: String)

    fun write(
        dsf: File, output: File, title: String, artist: String, album: String,
        isCancelled: () -> Boolean = { false }, onProgress: (Int) -> Unit = {},
    ): Result {
        require(dsf.canonicalFile != output.canonicalFile)
        require(!output.exists()) { "SACD output already exists" }
        fun checkCancelled() { if (isCancelled()) throw CancellationException("SACD packaging cancelled") }
        checkCancelled()
        RandomAccessFile(dsf, "r").use { input ->
            val header = ByteArray(92); input.readFully(header)
            val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(String(header, 0, 4) == "DSD " && le.getLong(4) == 28L &&
                String(header, 28, 4) == "fmt " && le.getLong(32) == 52L &&
                le.getInt(40) == 1 && le.getInt(44) == 0 && String(header, 80, 4) == "data")
            val channels = le.getInt(52); val bits = le.getInt(60)
            val samples = le.getLong(64); val blockSize = le.getInt(72)
            require(le.getInt(56) == 2_822_400 && channels in 1..2 && bits in listOf(1, 8) &&
                samples > 0 && samples % 8 == 0L && blockSize == 4096) { "SACD requires DSD64 mono/stereo" }
            val bytesPerChannel = samples / 8
            val frames = (bytesPerChannel + 4703) / 4704
            require(frames < 256L * 60 * 75) { "SACD timecode exceeds 255 minutes" }
            val groups = (bytesPerChannel + blockSize - 1) / blockSize
            val dataBytes = groups * blockSize * channels
            require(le.getLong(84) >= dataBytes + 12 && input.length() >= dataBytes + 92) { "Truncated DSF data" }
            val sectors = ((frames + 2) / 3 * 14).toInt()

            val originals = listOf(title, artist, album).map { it.replace('\u0000', ' ') }
            val charset = when {
                originals.all { Charset.forName("US-ASCII").newEncoder().canEncode(it) } -> Charset.forName("US-ASCII")
                originals.all { Charset.forName("Shift_JIS").newEncoder().canEncode(it) } -> Charset.forName("Shift_JIS")
                originals.all { Charset.forName("GB2312").newEncoder().canEncode(it) } -> Charset.forName("GB2312")
                else -> Charset.forName("Shift_JIS")
            }
            val codepage = when (charset.name()) { "US-ASCII" -> 1; "GB2312" -> 5; else -> 3 }
            // SACD strings have bounded legacy character sets. Report the actual stored values.
            val encoded = originals.map { value ->
                var end = minOf(value.length, 240)
                if (end < value.length && end > 0 && value[end - 1].isHighSurrogate()) end--
                while (end > 0 && value.substring(0, end).toByteArray(charset).size > 240) end--
                value.substring(0, end).toByteArray(charset)
            }
            val actual = encoded.map { String(it, charset) }
            val prefix = ByteArray(AUDIO_LBA * SECTOR)
            val be = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
            fun signature(at: Int, value: String) { value.toByteArray(Charsets.US_ASCII).copyInto(prefix, at) }
            fun u16(at: Int, value: Int) { be.putShort(at, value.toShort()) }
            fun u32(at: Int, value: Int) { be.putInt(at, value) }
            fun time(at: Int) {
                prefix[at] = (frames / 4500).toByte(); prefix[at + 1] = (frames / 75 % 60).toByte()
                prefix[at + 2] = (frames % 75).toByte()
            }
            fun textChannel(at: Int) {
                prefix[at] = 1
                signature(at + 8, if (codepage == 3) "ja" else if (codepage == 5) "zh" else "en")
                prefix[at + 10] = codepage.toByte()
            }
            val master = 510 * SECTOR
            signature(master, "SACDMTOC"); prefix[master + 8] = 2
            u16(master + 16, 1); u16(master + 18, 1)
            u32(master + 64, 600); u32(master + 68, 608); u16(master + 84, 8)
            textChannel(master + 128)
            for (n in 0 until 8) signature((511 + n) * SECTOR, "SACDText")
            val masterText = 511 * SECTOR
            u16(masterText + 16, 64); u16(masterText + 32, 64)
            encoded[2].copyInto(prefix, masterText + 64)
            val artistOffset = 64 + encoded[2].size + 2
            u16(masterText + 18, artistOffset); u16(masterText + 34, artistOffset)
            encoded[1].copyInto(prefix, masterText + artistOffset)
            signature(519 * SECTOR, "SACD_Man")
            prefix.copyInto(prefix, 520 * SECTOR, 510 * SECTOR, 520 * SECTOR)
            prefix.copyInto(prefix, 530 * SECTOR, 510 * SECTOR, 520 * SECTOR)

            val area = 600 * SECTOR
            signature(area, "TWOCHTOC"); prefix[area + 8] = 2
            u16(area + 10, 8); u32(area + 16, 716800)
            prefix[area + 20] = 4; prefix[area + 21] = 2; prefix[area + 32] = 2
            time(area + 64); prefix[area + 69] = 1
            u32(area + 72, AUDIO_LBA); u32(area + 76, AUDIO_LBA + sectors - 1)
            textChannel(area + 80); u16(area + 128, 5)
            val trackSectors = 601 * SECTOR
            signature(trackSectors, "SACDTRL1"); u32(trackSectors + 8, AUDIO_LBA)
            u32(trackSectors + 1028, sectors)
            signature(602 * SECTOR, "SACDTRL2"); time(602 * SECTOR + 1028)
            signature(603 * SECTOR, "SACD_IGL")
            val trackText = 605 * SECTOR
            signature(trackText, "SACDTTxt"); u16(trackText + 8, 4096)
            var entry = trackText + 4096
            prefix[entry] = 2; entry += 4
            for (n in 0..1) {
                prefix[entry] = (n + 1).toByte(); prefix[entry + 1] = 0x20
                encoded[n].copyInto(prefix, entry + 2)
                val terminatorBytes = if (codepage in 3..6) 2 else 1
                entry += (2 + encoded[n].size + terminatorBytes + 3) and -4
            }
            prefix.copyInto(prefix, 608 * SECTOR, 600 * SECTOR, 608 * SECTOR)

            try {
                BufferedOutputStream(output.outputStream(), 64 * 1024).use { sink ->
                    sink.write(prefix)
                    val left = ByteArray(blockSize); val right = ByteArray(blockSize)
                    val sector = ByteArray(SECTOR)
                    var loadedGroup = -1L
                    var byteIndex = 0L
                    val totalBytes = sectors.toLong() * 2016 / 2
                    for (n in 0 until sectors) {
                        checkCancelled(); sector.fill(0); sector.fill(0x69, 32)
                        for (i in 0 until 1008) {
                            if (byteIndex < bytesPerChannel) {
                                val group = byteIndex / blockSize
                                if (loadedGroup != group) {
                                    input.seek(92 + group * blockSize * channels)
                                    input.readFully(left)
                                    if (channels == 2) input.readFully(right) else left.copyInto(right)
                                    loadedGroup = group
                                }
                                val offset = (byteIndex % blockSize).toInt()
                                fun normalized(value: Byte): Byte = if (bits == 8) value else
                                    (Integer.reverse(value.toInt() and 255) ushr 24).toByte()
                                sector[32 + i * 2] = normalized(left[offset])
                                sector[33 + i * 2] = normalized(right[offset])
                            }
                            byteIndex++
                        }
                        sink.write(sector)
                        if (n % 128 == 0) onProgress((byteIndex * 1000 / totalBytes).toInt())
                    }
                }
                onProgress(1000)
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
            return Result(frames, actual[0], actual[1], actual[2])
        }
    }
}
