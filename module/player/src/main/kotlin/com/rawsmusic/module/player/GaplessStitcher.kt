package com.rawsmusic.module.player

/** Joins the current tail and pending head into one complete renderer block. */
internal interface GaplessStitcher {
    data class Result(
        val totalBytes: Int,
        val pendingBytes: Int,
        val boundaryFrameOffset: Int,
        val nativeBacked: Boolean,
    )

    fun stitch(
        outputBuffer: ByteArray,
        currentBytes: Int,
        pendingBuffer: ByteArray,
        pendingBytes: Int,
        targetBytes: Int,
        frameSize: Int,
    ): Result?
}
