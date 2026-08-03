package com.rawsmusic.separation

import java.io.File

object AiStemAudioEncoder {
    fun encode(inputWav: File, output: File, lossless: Boolean): Result<File> = runCatching {
        require(inputWav.isFile && inputWav.length() > 44L) { "分轨临时音频为空" }
        output.delete()
        val result = nativeEncode(inputWav.absolutePath, output.absolutePath, lossless)
        require(result == 0 && output.isFile && output.length() > 0L) {
            if (lossless) "FLAC 编码失败：$result" else "AAC 编码失败：$result"
        }
        output
    }

    private external fun nativeEncode(
        inputPath: String,
        outputPath: String,
        lossless: Boolean,
    ): Int
}
