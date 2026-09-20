package com.rawsmusic.separation

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Creates the temporary mono WAV consumed by external CTC models. */
internal object AiLyricCtcAudioInput {
    fun prepare(
        cacheDir: File,
        source: File,
        sampleRate: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        require(source.isFile) { "CTC source audio is unavailable" }
        check(!isCancelled()) { "CTC analysis cancelled" }

        val token = System.nanoTime()
        val raw = File(cacheDir, "lyric_ctc_$token.s16le")
        val wav = File(cacheDir, "lyric_ctc_$token.wav")
        try {
            onProgress(0.03f)
            val result = FFmpegBridge.convertToRawPcm(
                inputPath = source.absolutePath,
                outputPath = raw.absolutePath,
                targetSampleRate = sampleRate,
                bitsPerSample = 16,
                channels = 1,
            )
            require(result == 0 && raw.isFile && raw.length() > 0L) {
                "CTC source decode failed: $result"
            }
            check(!isCancelled()) { "CTC analysis cancelled" }
            onProgress(0.14f)
            writePcm16Wave(raw, wav, sampleRate)
            require(wav.isFile && wav.length() > WAV_HEADER_BYTES) {
                "CTC temporary audio is empty"
            }
            onProgress(0.18f)
            return wav
        } catch (error: Throwable) {
            wav.delete()
            throw error
        } finally {
            raw.delete()
        }
    }

    private fun writePcm16Wave(raw: File, wav: File, sampleRate: Int) {
        val pcmBytes = raw.length().coerceAtMost(UINT32_MAX - WAV_HEADER_BYTES)
        BufferedOutputStream(FileOutputStream(wav)).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLe32(pcmBytes + 36L)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLe32(16L)
            output.writeLe16(1)
            output.writeLe16(1)
            output.writeLe32(sampleRate.toLong())
            output.writeLe32(sampleRate.toLong() * 2L)
            output.writeLe16(2)
            output.writeLe16(16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLe32(pcmBytes)
            BufferedInputStream(FileInputStream(raw)).use { input ->
                input.copyTo(output, COPY_BUFFER_SIZE)
            }
        }
    }

    private fun BufferedOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun BufferedOutputStream.writeLe32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private const val WAV_HEADER_BYTES = 44L
    private const val UINT32_MAX = 0xffff_ffffL
    private const val COPY_BUFFER_SIZE = 256 * 1024
}
