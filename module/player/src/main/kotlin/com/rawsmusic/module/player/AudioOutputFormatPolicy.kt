package com.rawsmusic.module.player

/** Pure Android output encoding decisions used by the PCM writer. */
internal object AudioOutputFormatPolicy {
    fun useFloatOutput(
        usbExclusiveMode: Boolean,
        probedEncoding: Int,
        floatEncoding: Int
    ): Boolean = !usbExclusiveMode && probedEncoding == floatEncoding

    fun usePacked24Output(
        usbExclusiveMode: Boolean,
        probedEncoding: Int,
        packed24Encoding: Int?
    ): Boolean = !usbExclusiveMode && packed24Encoding != null && probedEncoding == packed24Encoding
}
