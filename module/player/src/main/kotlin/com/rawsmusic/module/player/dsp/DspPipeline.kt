package com.rawsmusic.module.player.dsp

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DspPipeline {
    private const val TAG = "DspPipeline"
    private val modules = mutableMapOf<Int, DspModule>()

    fun register(module: DspModule) {
        modules[module.id] = module
        Log.d(TAG, "Registered DSP module: ${module.name} (id=${module.id})")
    }

    fun unregister(moduleId: Int) {
        modules.remove(moduleId)
    }

    fun getModule(moduleId: Int): DspModule? = modules[moduleId]

    fun process(buffer: ByteArray, byteCount: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        if (modules.isEmpty()) return
        for (module in modules.values) {
            if (module.isEnabled) {
                module.process(buffer, byteCount, channels, sampleRate, bitsPerSample)
            }
        }
    }

    fun resetAll() {
        for (module in modules.values) {
            module.reset()
        }
    }

    fun release() {
        modules.clear()
    }
}
