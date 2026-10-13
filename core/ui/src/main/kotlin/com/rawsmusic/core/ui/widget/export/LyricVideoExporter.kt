package com.rawsmusic.core.ui.widget.export

import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import com.rawsmusic.core.ui.widget.flow.prepareRawBackgroundFrameRenderer
import io.github.proify.lyricon.lyric.model.Song
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal data class LyricVideoRequest(
    val audioPath: String,
    /** Playback-time window (ms) of the exported clip. */
    val startMs: Long,
    val endMs: Long,
    /** Lyrics already trimmed to the selected continuous range, or null when the song has none. */
    val lyricSong: Song?,
    val title: String,
    val artist: String,
    val coverKey: String?,
    /** CPU-readable cover, used for the background palette/texture and the on-frame artwork. */
    val cover: Bitmap?,
    val size: LyricVideoSize,
    val frameRate: Int,
    val codec: LyricVideoCodec,
    val displayTranslation: Boolean,
    val displayRoma: Boolean,
    val simpleBackgroundArgb: Int,
    val outputFile: File,
    val signature: String,
)

internal sealed interface LyricExportResult {
    data class Success(val file: File) : LyricExportResult
    data object Cancelled : LyricExportResult
    data class Failed(val reason: String) : LyricExportResult
}

/**
 * Renders and encodes one lyric video. Must be called from the main thread (the lyric composition
 * and the hardware canvas both live there); heavy audio work runs on a worker dispatcher.
 *
 * Progress: 0..0.08 audio segment, 0.08..1 video frames.
 */
internal suspend fun exportLyricVideo(
    activity: Activity,
    request: LyricVideoRequest,
    onProgress: (Float) -> Unit,
): LyricExportResult {
    val durationMs = (request.endMs - request.startMs).coerceAtLeast(0L)
    if (durationMs < MIN_CLIP_MS) return LyricExportResult.Failed("片段太短")
    val size = request.size
    val fps = request.frameRate
    Log.i(VIDEO_EXPORT_TAG, "start codec=${request.codec} size=${size.width}x${size.height} fps=$fps durationMs=$durationMs")
    var codec: MediaCodec? = null
    var host: OffscreenComposeFrameHost? = null
    var muxer: LyricVideoMuxer? = null
    request.outputFile.parentFile?.mkdirs()
    request.outputFile.delete()
    try {
        return coroutineScope {
            val background = prepareRawBackgroundFrameRenderer(
                context = activity,
                coverKey = request.coverKey,
                artwork = request.cover,
                simpleBackgroundArgb = request.simpleBackgroundArgb,
            )
            val audio = withContext(Dispatchers.Default) {
                encodeAudioSegmentAac(
                    path = request.audioPath,
                    startMs = request.startMs,
                    endMs = request.endMs,
                    isCancelled = { !isActive },
                    onProgress = { onProgress(it * AUDIO_PROGRESS_SHARE) },
                )
            }
            ensureActive()

            val (encoder, surface) = createVideoEncoder(
                codec = request.codec,
                size = size,
                frameRate = fps,
                bitRate = defaultVideoBitRate(size, fps),
            ) ?: return@coroutineScope LyricExportResult.Failed("设备不支持该分辨率/编码格式；详见 $VIDEO_EXPORT_TAG 日志")
            codec = encoder
            val mux = LyricVideoMuxer(request.outputFile.absolutePath, audio).also { muxer = it }

            val frameCount = ceil(durationMs * fps / 1000.0).toInt().coerceAtLeast(1)
            // Output is drained on its own thread: if the encoder's output queue filled up while
            // the main thread waited in lockHardwareCanvas() the two would deadlock.
            val drain = async(Dispatchers.IO) {
                drainVideoEncoder(encoder, mux, fps, frameCount, isActive = { isActive })
            }

            val coverImage = request.cover?.takeUnless { it.isRecycled }?.asImageBitmap()
            val frameState = ExportFrameState(request.startMs)
            val frameHost = OffscreenComposeFrameHost(activity, size.width, size.height) {
                LyricVideoFrameContent(
                    song = request.lyricSong,
                    positionMs = frameState.positionMs,
                    title = request.title,
                    artist = request.artist,
                    coverKey = request.coverKey,
                    cover = coverImage,
                    widthPx = size.width,
                    heightPx = size.height,
                    displayTranslation = request.displayTranslation,
                    displayRoma = request.displayRoma,
                    signature = request.signature,
                )
            }.also { host = it }

            val frameNanos = 1_000_000_000L / fps
            val captureStartNs = SystemClock.elapsedRealtimeNanos()
            for (index in 0 until frameCount) {
                ensureActive()
                val waitMs = exportFrameWaitMillis(
                    startNs = captureStartNs,
                    frameIndex = index,
                    fps = fps,
                    nowNs = SystemClock.elapsedRealtimeNanos(),
                )
                if (waitMs > 0L) delay(waitMs)
                frameState.positionMs = request.startMs + index * 1000L / fps
                frameHost.advanceFrame(FRAME_CLOCK_ORIGIN_NS + index * frameNanos)
                val canvas = surface.lockHardwareCanvas()
                try {
                    background.drawFrame(
                        canvas,
                        size.width,
                        size.height,
                        timeSeconds = request.startMs / 1000f + index.toFloat() / fps,
                    )
                    frameHost.draw(canvas)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                onProgress(AUDIO_PROGRESS_SHARE + (1f - AUDIO_PROGRESS_SHARE) * (index + 1) / frameCount)
            }
            Log.i(VIDEO_EXPORT_TAG, "input posted=$frameCount elapsedMs=${(SystemClock.elapsedRealtimeNanos() - captureStartNs) / 1_000_000L}")
            encoder.signalEndOfInputStream()
            val drained = drain.await()
            if (!drained) {
                Log.e(VIDEO_EXPORT_TAG, "drain incomplete codec=${request.codec} expectedFrames=$frameCount")
                return@coroutineScope LyricExportResult.Failed("视频编码未完整输出；详见 $VIDEO_EXPORT_TAG 日志")
            }
            mux.writeAudioSamples()
            mux.release()
            muxer = null
            Log.i(VIDEO_EXPORT_TAG, "complete codec=${request.codec} frames=$frameCount bytes=${request.outputFile.length()}")
            LyricExportResult.Success(request.outputFile)
        }
    } catch (cancel: CancellationException) {
        request.outputFile.delete()
        throw cancel
    } catch (t: Throwable) {
        Log.e(VIDEO_EXPORT_TAG, "failed codec=${request.codec} size=${size.width}x${size.height} fps=$fps", t)
        request.outputFile.delete()
        return LyricExportResult.Failed(t.message ?: t::class.java.simpleName)
    } finally {
        host?.release()
        muxer?.release()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
    }
}

private class ExportFrameState(initial: Long) {
    var positionMs by mutableLongStateOf(initial)
}

/**
 * Drain encoded video into the muxer. Canvas input carries real capture timestamps; preserve
 * their spacing so a dropped frame cannot shorten the video relative to the audio track.
 */
private fun drainVideoEncoder(
    encoder: MediaCodec,
    muxer: LyricVideoMuxer,
    fps: Int,
    expectedFrames: Int,
    isActive: () -> Boolean,
): Boolean {
    val info = MediaCodec.BufferInfo()
    var outputFrames = 0L
    var firstRawPtsUs: Long? = null
    var lastPtsUs = -1L
    while (isActive()) {
        val index = encoder.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                muxer.onVideoFormatChanged(encoder.outputFormat)
                Log.i(VIDEO_EXPORT_TAG, "output format ready")
            }
            index >= 0 -> {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val buffer = encoder.getOutputBuffer(index)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buffer != null && info.size > 0 && !isConfig) {
                    val rawPtsUs = info.presentationTimeUs
                    val firstPtsUs = firstRawPtsUs ?: rawPtsUs.also { firstRawPtsUs = it }
                    val ptsUs = exportVideoPresentationTimeUs(rawPtsUs, firstPtsUs, lastPtsUs)
                    lastPtsUs = ptsUs
                    outputFrames++
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    info.set(info.offset, info.size, ptsUs, flags)
                    muxer.writeVideoSample(buffer, info)
                }
                encoder.releaseOutputBuffer(index, false)
                if (eos) {
                    val expectedLastPtsUs = (expectedFrames - 1).coerceAtLeast(0) * 1_000_000L / fps
                    val enoughFrames = outputFrames * 100L >= expectedFrames * 99L
                    val enoughDuration = lastPtsUs * 100L >= expectedLastPtsUs * 98L
                    Log.i(VIDEO_EXPORT_TAG, "output EOS frames=$outputFrames expected=$expectedFrames lastPtsUs=$lastPtsUs expectedLastPtsUs=$expectedLastPtsUs")
                    return enoughFrames && enoughDuration
                }
            }
        }
    }
    return false
}

private const val MIN_CLIP_MS = 1_000L
private const val AUDIO_PROGRESS_SHARE = 0.08f
private const val DRAIN_TIMEOUT_US = 20_000L
private const val FRAME_CLOCK_ORIGIN_NS = 1_000_000_000L
