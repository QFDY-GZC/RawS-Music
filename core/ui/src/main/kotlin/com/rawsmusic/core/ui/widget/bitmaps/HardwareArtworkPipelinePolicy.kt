package com.rawsmusic.core.ui.widget.bitmaps

/**
 * HWUI-facing artwork policy.
 *
 * A HARDWARE Bitmap is already GPU resident. Reading it back merely to force an exact provider
 * size/crop, or to JPEG it into the reconstructable thumbnail cache, turns one decode into a
 * GPU upload + GPU->CPU readback (+ another upload when the software result is drawn). Provider
 * presentation surfaces already own scale/crop, so keep the decoder geometry and pixels resident.
 */
internal object HardwareArtworkPipelinePolicy {
    const val PIXEL_ANALYSIS_SIDE = 96

    fun keepDecoderGeometry(isHardware: Boolean): Boolean = isHardware

    fun allowDiskThumbnailWrite(isHardware: Boolean): Boolean = !isHardware

    /** CPU Palette/getPixels work must never force a HARDWARE bitmap back across the GPU boundary. */
    fun mayAnalyzePixelsDirectly(isHardware: Boolean): Boolean = !isHardware

    /**
     * The decoded-source resize cache is a CPU raster pipeline. A HARDWARE provider decode is
     * already the authoritative low/high wrapper and must stay GPU-resident instead of being
     * detached through Bitmap.copy()/Canvas scaling.
     */
    fun allowDecodedSourceRescaleCache(isHardwarePreferred: Boolean): Boolean = !isHardwarePreferred

    /** Playback View/Shader owns presentation scaling for a GPU-resident wrapper. */
    fun allowPlaybackWrapperNormalization(isHardware: Boolean): Boolean = !isHardware
}
