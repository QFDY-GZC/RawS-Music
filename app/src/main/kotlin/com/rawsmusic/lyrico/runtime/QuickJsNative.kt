package com.rawsmusic.lyrico.runtime

import androidx.annotation.Keep

@Keep
object QuickJsNative {
    init {
        System.loadLibrary("rawsmusic_lyrico_quickjs")
    }

    external fun createRuntime(
        memoryLimitBytes: Long,
        stackSizeBytes: Long,
        timeoutMs: Long,
        hostApi: QuickJsHostApi?
    ): Long

    external fun eval(runtimePtr: Long, script: String, filename: String): String

    external fun call(runtimePtr: Long, functionName: String, requestJson: String): String

    external fun closeRuntime(runtimePtr: Long)
}
