package com.rawsmusic.module.player.dsp

import android.util.Log

class NativeDSPEngine {
    private var nativeHandle: Long = 0

    companion object {
        private const val TAG = "NativeDSPEngine"
        init {
            try {
                System.loadLibrary("rawsmusic_dsp")
                Log.d(TAG, "rawsmusic_dsp library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load rawsmusic_dsp", e)
            }
        }
    }

    fun init(sampleRate: Int, channels: Int) {
        if (nativeHandle != 0L) release()
        nativeHandle = nativeCreate(sampleRate, channels)
        Log.d(TAG, "init: sampleRate=$sampleRate, channels=$channels, handle=$nativeHandle")
    }

    fun setStereoWiden(factor: Float) {
        if (nativeHandle == 0L) return
        nativeSetStereoWiden(nativeHandle, factor)
    }

    fun process(buffer: ShortArray, length: Int, channels: Int): Int {
        if (nativeHandle == 0L) return -1
        return nativeProcess(nativeHandle, buffer, length, channels)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0
        }
    }

    fun isInitialized(): Boolean = nativeHandle != 0L

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeSetStereoWiden(handle: Long, factor: Float)
    private external fun nativeProcess(handle: Long, buffer: ShortArray, length: Int, channels: Int): Int
    private external fun nativeRelease(handle: Long)
}
