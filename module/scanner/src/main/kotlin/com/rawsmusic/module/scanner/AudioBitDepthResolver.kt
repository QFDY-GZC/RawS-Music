package com.rawsmusic.module.scanner

/**
 * Centralized source bit-depth / precision policy for scanner metadata.
 *
 * Lossless PCM-like codecs have a real source bit depth and should be stored in
 * AudioFile.bitsPerSample. Lossy codecs do not carry a meaningful source bit
 * depth; callers should keep bitsPerSample=0 and let UI render them as lossy.
 */
object AudioBitDepthResolver {

    fun isLossyCodec(codecName: String, formatName: String = ""): Boolean {
        val c = codecName.lowercase()
        val f = formatName.lowercase()
        return c.contains("mp3") ||
            c.contains("aac") ||
            c.contains("opus") ||
            c.contains("vorbis") ||
            c.contains("wma") ||
            c.contains("amr") ||
            c.contains("musepack") ||
            c.contains("mpc") ||
            f.contains("mp3") ||
            f.contains("aac") ||
            f.contains("opus")
    }

    fun isLossyDisplayFormat(format: String): Boolean {
        val f = format.lowercase()
        return f == "aac" ||
            f == "mp3" ||
            f == "opus" ||
            f == "vorbis" ||
            f == "ogg" ||
            f == "wma" ||
            f == "amr" ||
            f == "m4a-aac" ||
            f.contains("aac") ||
            f.contains("mp3") ||
            f.contains("opus") ||
            f.contains("vorbis") ||
            f.contains("wma")
    }

    fun inferFromSampleFmt(sampleFmt: String): Int {
        val f = sampleFmt.lowercase()
        return when (f) {
            "u8", "u8p" -> 8
            "s16", "s16p" -> 16
            "s24", "s24p" -> 24
            "s32", "s32p" -> 32
            "s64", "s64p" -> 64
            "flt", "fltp" -> 32
            "dbl", "dblp" -> 64
            else -> 0
        }
    }

    fun inferFromCodecOrExtension(codecName: String, filePath: String): Int {
        val codec = codecName.lowercase()
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when {
            codec.contains("pcm_s8") || codec.contains("pcm_u8") -> 8
            codec.contains("pcm_s16") -> 16
            codec.contains("pcm_s24") -> 24
            codec.contains("pcm_s32") -> 32
            codec.contains("pcm_f32") -> 32
            codec.contains("pcm_f64") -> 64
            codec.contains("dsd") || ext == "dsf" || ext == "dff" -> 1
            else -> 0
        }
    }

    fun resolveSourceBitDepth(
        codecName: String,
        formatName: String,
        filePath: String,
        bitsPerSample: Int,
        bitsPerRawSample: Int = 0,
        bitsPerCodedSample: Int = 0,
        sampleFmt: String = ""
    ): Int {
        if (isLossyCodec(codecName, formatName)) return 0
        if (bitsPerSample > 0) return bitsPerSample
        if (bitsPerRawSample > 0) return bitsPerRawSample
        if (bitsPerCodedSample > 0) return bitsPerCodedSample
        if (sampleFmt.isNotBlank()) {
            val fmtBits = inferFromSampleFmt(sampleFmt)
            if (fmtBits > 0) return fmtBits
        }
        return inferFromCodecOrExtension(codecName, filePath)
    }
}
