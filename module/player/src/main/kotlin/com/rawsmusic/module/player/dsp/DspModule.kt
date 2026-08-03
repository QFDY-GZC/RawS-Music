package com.rawsmusic.module.player.dsp

interface DspModule {
    val id: Int
    val name: String
    val isEnabled: Boolean

    fun process(buffer: ByteArray, byteCount: Int, channels: Int, sampleRate: Int, bitsPerSample: Int)
    fun setEnabled(enabled: Boolean)
    fun reset()
}
