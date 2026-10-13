package com.rawsmusic.core.ui.widget.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/** Frame geometry for one export. */
internal data class LyricVideoSize(val width: Int, val height: Int)

internal enum class LyricVideoAspect(val label: String, val ratio: Float) {
    PORTRAIT("9:16", 9f / 16f),
    LANDSCAPE("16:9", 16f / 9f),
    SQUARE("1:1", 1f),
}

internal enum class LyricVideoCodec(val mimeType: String, val label: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264"),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC"),
}

internal fun lyricVideoSizeFor(aspect: LyricVideoAspect, shortEdge: Int): LyricVideoSize {
    val longEdge = (shortEdge * 16 / 9).let { it - it % 2 }
    return when (aspect) {
        LyricVideoAspect.PORTRAIT -> LyricVideoSize(shortEdge, longEdge)
        LyricVideoAspect.LANDSCAPE -> LyricVideoSize(longEdge, shortEdge)
        LyricVideoAspect.SQUARE -> LyricVideoSize(shortEdge, shortEdge)
    }
}

/**
 * Hardware H.264/HEVC encoder plus one MP4 muxer that also carries the AAC segment.
 *
 * MediaMuxer requires every track to be added before start(), and the encoder output format is only
 * known after the first encoded frame. Both tracks are therefore added on the first
 * INFO_OUTPUT_FORMAT_CHANGED, and the audio samples (encoded up front, kept in memory) are written
 * after the video stream ends.
 */
internal class LyricVideoMuxer(
    private val outputPath: String,
    private val audioTrack: EncodedAudioTrack?,
) {
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var started = false

    fun onVideoFormatChanged(format: MediaFormat) {
        if (started) return
        val target = muxer ?: MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            .also { muxer = it }
        if (videoTrackIndex < 0) videoTrackIndex = target.addTrack(format)
        val audio = audioTrack
        if (audio != null && audioTrackIndex < 0) audioTrackIndex = target.addTrack(audio.format)
        target.start()
        started = true
    }

    fun writeVideoSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || videoTrackIndex < 0) return
        muxer?.writeSampleData(videoTrackIndex, data, info)
    }

    /** Write the pre-encoded audio segment. Call once, after the last video frame. */
    fun writeAudioSamples() {
        if (!started) return
        val track = audioTrack ?: return
        val target = muxer ?: return
        if (audioTrackIndex < 0) return
        val info = MediaCodec.BufferInfo()
        for (sample in track.samples) {
            val buffer = ByteBuffer.wrap(sample.data)
            info.set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
            target.writeSampleData(audioTrackIndex, buffer, info)
        }
    }

    fun release() {
        runCatching { if (started) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        started = false
    }
}

/** Resolves (and caches) a hardware encoder for a mime type; software encoders are rejected. */
internal object MediaCodecListHolder {
    private val cache = HashMap<String, List<MediaCodecInfo>>()

    fun encoderFor(mimeType: String): MediaCodecInfo? = encodersFor(mimeType).firstOrNull()

    fun encodersFor(mimeType: String): List<MediaCodecInfo> = synchronized(cache) {
        cache.getOrPut(mimeType) { resolve(mimeType) }
    }

    private fun resolve(mimeType: String): List<MediaCodecInfo> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && !isSoftwareCodec(info) &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }.getOrDefault(emptyList())

    private fun isSoftwareCodec(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isSoftwareOnly
        } else {
            val name = info.name.lowercase()
            name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.")
        }
}

/** True when the device hardware encoder accepts this exact size/frame-rate combination. */
internal fun isVideoSizeSupported(
    codec: LyricVideoCodec,
    width: Int,
    height: Int,
    frameRate: Int,
): Boolean = MediaCodecListHolder.encodersFor(codec.mimeType).any { info ->
    supportsVideoProfile(info, codec.mimeType, width, height, frameRate)
}

private fun supportsVideoProfile(
    info: MediaCodecInfo,
    mimeType: String,
    width: Int,
    height: Int,
    frameRate: Int,
): Boolean = runCatching {
    val capabilities = info.getCapabilitiesForType(mimeType)
    val video = capabilities.videoCapabilities ?: return@runCatching false
    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in capabilities.colorFormats &&
        video.areSizeAndRateSupported(width, height, frameRate.toDouble())
}.getOrDefault(false)

/**
 * Create the encoder for a lyric video and return it with its input surface.
 *
 * The surface belongs to [MediaCodec]; frames must be drawn through `lockHardwareCanvas()`, which
 * keeps the hardware-accelerated blur used by the lyric effects working.
 */
internal fun createVideoEncoder(
    codec: LyricVideoCodec,
    size: LyricVideoSize,
    frameRate: Int,
    bitRate: Int,
): Pair<MediaCodec, Surface>? {
    val format = MediaFormat.createVideoFormat(codec.mimeType, size.width, size.height).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // Output presentation times are rewritten in output order, which is only valid without
        // B-frame reordering.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
    }
    val candidates = MediaCodecListHolder.encodersFor(codec.mimeType)
    Log.i(VIDEO_EXPORT_TAG, "encoder candidates=${candidates.size} mime=${codec.mimeType} size=${size.width}x${size.height} fps=$frameRate")
    for (info in candidates) {
        if (!supportsVideoProfile(info, codec.mimeType, size.width, size.height, frameRate)) continue
        var instance: MediaCodec? = null
        try {
            instance = MediaCodec.createByCodecName(info.name)
            instance.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = instance.createInputSurface()
            instance.start()
            Log.i(VIDEO_EXPORT_TAG, "encoder started name=${info.name}")
            return instance to surface
        } catch (error: Exception) {
            Log.w(VIDEO_EXPORT_TAG, "encoder rejected name=${info.name}", error)
            runCatching { instance?.release() }
        }
    }
    Log.e(VIDEO_EXPORT_TAG, "no usable encoder mime=${codec.mimeType} size=${size.width}x${size.height} fps=$frameRate")
    return null
}

internal const val VIDEO_EXPORT_TAG = "LyricVideoExport"

/** Sensible default bit rate for the chosen resolution/frame rate. */
internal fun defaultVideoBitRate(size: LyricVideoSize, frameRate: Int): Int {
    val pixels = size.width.toLong() * size.height.toLong()
    // ~0.11 bits per pixel per frame, clamped to a band that phone playback handles well.
    val bits = (pixels * frameRate * 0.11).toLong()
    return bits.coerceIn(4_000_000L, 40_000_000L).toInt()
}
