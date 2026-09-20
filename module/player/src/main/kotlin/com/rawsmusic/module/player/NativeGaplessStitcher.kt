package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/** Optional JNI-backed byte-exact gapless block stitcher. */
internal object NativeGaplessStitcher : GaplessStitcher {
    private const val TAG = "NativeGaplessStitcher"
    private const val FIELD_BITS = 21
    private const val FIELD_MASK = (1L shl FIELD_BITS) - 1L

    private val libraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("rawscoreservice")
            true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Native gapless stitcher unavailable", t)
            false
        }
    }

    @Volatile
    private var bridgeDisabled = false

    override fun stitch(
        outputBuffer: ByteArray,
        currentBytes: Int,
        pendingBuffer: ByteArray,
        pendingBytes: Int,
        targetBytes: Int,
        frameSize: Int,
    ): GaplessStitcher.Result? {
        if (!libraryLoaded || bridgeDisabled) {
            return KotlinGaplessStitcher.stitch(
                outputBuffer,
                currentBytes,
                pendingBuffer,
                pendingBytes,
                targetBytes,
                frameSize,
            )
        }
        return try {
            val packed = nativeStitch(
                outputBuffer,
                currentBytes,
                pendingBuffer,
                pendingBytes,
                targetBytes,
                frameSize,
            )
            if (packed == 0L) return null
            val total = (packed and FIELD_MASK).toInt()
            val pending = ((packed ushr FIELD_BITS) and FIELD_MASK).toInt()
            val boundary = ((packed ushr (FIELD_BITS * 2)) and FIELD_MASK).toInt()
            if (total <= currentBytes || pending <= 0) null else GaplessStitcher.Result(
                totalBytes = total,
                pendingBytes = pending,
                boundaryFrameOffset = boundary,
                nativeBacked = true,
            )
        } catch (t: Throwable) {
            bridgeDisabled = true
            AppLogger.w(TAG, "Native gapless stitcher failed; using Kotlin fallback", t)
            KotlinGaplessStitcher.stitch(
                outputBuffer,
                currentBytes,
                pendingBuffer,
                pendingBytes,
                targetBytes,
                frameSize,
            )
        }
    }

    @JvmStatic
    private external fun nativeStitch(
        outputBuffer: ByteArray,
        currentBytes: Int,
        pendingBuffer: ByteArray,
        pendingBytes: Int,
        targetBytes: Int,
        frameSize: Int,
    ): Long
}
