package com.rawsmusic.ai.variant

import android.content.Context
import android.util.Log
import com.rawsmusic.ai.instrument.AiInstalledInstrumentPack
import com.rawsmusic.ai.melody.AiPerformanceMidiStore
import com.rawsmusic.ai.melody.AiPerformanceTrack
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.separation.AiRealtimeOnnxPcmProcessor
import com.rawsmusic.separation.AiRealtimeSeparatedBlockTransformer
import com.rawsmusic.separation.AiRealtimeSeparationController
import com.rawsmusic.separation.AiSeparationLiveStreamState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns AI performance synthesis. Stem separation is always shared with the normal separation service. */
class AiRealtimePerformanceController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val generation = AtomicLong(0L)
    private val midiStore = AiPerformanceMidiStore.get(appContext)
    private val prewarmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS-AI-Performance-Warmup").apply { isDaemon = true }
    }

    @Volatile private var activeSongKey: String = ""
    @Volatile private var activeMode: AiMediaVariantMode? = null
    @Volatile private var enabledRealtimeByUs = false
    @Volatile private var transformer: AiRealtimeSeparatedBlockTransformer? = null

    fun isActiveFor(song: AudioFile): Boolean = activeSongKey == songKey(song) && activeMode != null
    fun modeFor(song: AudioFile): AiMediaVariantMode? = activeMode.takeIf { isActiveFor(song) }
    fun isMidiActiveFor(song: AudioFile): Boolean = isActiveFor(song) && transformer is AiMidiPianoPerformance

    /**
     * Preferred path: analysis -> per-instrument cached .mid -> event-driven sampled playback.
     * Existing stem files supply the vocal/instrumental split, so playback does not rerun separation or melody analysis.
     */
    suspend fun activatePreparedMidi(
        song: AudioFile,
        mode: AiMediaVariantMode,
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            require(isSupportedSource(song)) { "CUE 子轨暂不支持 MID AI 演奏" }
            logSource("activate_prepared_midi", song)
            val player = requireNotNull(PlayerController.getInstanceOrNull()) { "播放器当前不可用" }
            val current = requireNotNull(player.currentSong.value) { "当前没有正在播放的歌曲" }
            require(songKey(current) == songKey(song)) { "歌曲已切换，未启用 MID AI 演奏" }
            require(!player.usbExclusiveActive.value) { "USB 独占输出请使用已生成的离线 AI 演奏版本" }

            val packKey = packKey(pack)
            val existing = transformer
            if (existing is AiMidiPianoPerformance &&
                activeSongKey == songKey(song) && existing.instrumentPackKey == packKey
            ) {
                existing.setMode(mode)
                activeMode = mode
                AiRealtimeOnnxPcmProcessor.reset("midi_performance_mode_changed")
                return@runCatching Unit
            }

            val token = generation.incrementAndGet()
            val artifact = midiStore.prepare(performance, pack.manifest.instrument)
            val created = AiMidiPianoPerformance(artifact, pack, mode)
            if (token != generation.get()) {
                created.close()
                error("MID AI 演奏请求已被替代")
            }
            install(song, mode, created)
            Unit
        }.onFailure { error ->
            Log.e(TAG, "AI_PERF_ACTIVATE_FAILED path=prepared_midi ${sourceSummary(song)}", error)
            deactivateInternal(restoreRealtime = true)
        }
    }

    /**
     * First-use live path: reuse the normal process-local realtime separator itself.
     * No foreground/offline separation job is created here. The same PCM owner that powers
     * realtime vocal separation produces the vocal block consumed by the instrument transformer.
     */
    suspend fun activateLiveSeparation(
        song: AudioFile,
        mode: AiMediaVariantMode,
        pack: AiInstalledInstrumentPack,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            require(isSupportedSource(song)) { "CUE 子轨暂不支持实时 AI 演奏" }
            logSource("activate_live_separation", song)
            val player = requireNotNull(PlayerController.getInstanceOrNull()) { "播放器当前不可用" }
            val current = requireNotNull(player.currentSong.value) { "当前没有正在播放的歌曲" }
            require(songKey(current) == songKey(song)) { "歌曲已切换" }
            require(!player.usbExclusiveActive.value) { "USB 独占输出请使用离线 AI 演奏" }

            val desiredPackKey = packKey(pack)
            val existing = transformer
            if (existing is AiRealtimePianoPerformance &&
                activeSongKey == songKey(song) && existing.instrumentPackKey == desiredPackKey
            ) {
                existing.setMode(mode)
                activeMode = mode
                AiRealtimeOnnxPcmProcessor.setSharedLiveStream(null, "")
                AiRealtimeOnnxPcmProcessor.reset("live_performance_mode_changed")
                return@runCatching Unit
            }

            val token = generation.incrementAndGet()
            val created = AiRealtimePianoPerformance.create(appContext, pack, mode).getOrThrow()
            if (token != generation.get()) {
                created.close()
                error("AI 演奏请求已更新")
            }
            if (activeSongKey == songKey(song) && existing != null &&
                AiRealtimeSeparationController.state.value.enabled
            ) {
                // Instrument changes must not tear down/reopen the MDX session. Swap only the
                // post-separation transformer and keep the realtime separator warm.
                transformer = created
                activeMode = mode
                AiRealtimeOnnxPcmProcessor.setSharedLiveStream(null, "")
                AiRealtimeOnnxPcmProcessor.setSeparatedBlockTransformer(created)
            } else {
                install(song, mode, created)
            }
            // RMVPE session open is intentionally off the activation path. It warms in parallel
            // with MDX model loading/input buffering and is synchronized with the first transform.
            prewarmExecutor.execute { created.prewarm() }
            Unit
        }.onFailure { error ->
            Log.e(TAG, "AI_PERF_ACTIVATE_FAILED path=live_separation ${sourceSummary(song)}", error)
            deactivateInternal(restoreRealtime = true)
        }
    }

    /**
     * Existing offline/shared separation path. If the normal separation service is already running,
     * consume its growing vocal stem instead of running the realtime separator in parallel.
     */
    suspend fun activateSharedSeparation(
        song: AudioFile,
        mode: AiMediaVariantMode,
        stream: AiSeparationLiveStreamState,
        pack: AiInstalledInstrumentPack,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            require(isSupportedSource(song)) { "CUE 子轨暂不支持实时 AI 演奏" }
            logSource("activate_shared_separation", song)
            require(stream.active && stream.ready) { "分离音轨尚未就绪" }
            val player = requireNotNull(PlayerController.getInstanceOrNull()) { "播放器当前不可用" }
            val current = requireNotNull(player.currentSong.value) { "当前没有正在播放的歌曲" }
            require(songKey(current) == songKey(song)) { "歌曲已切换" }
            require(!player.usbExclusiveActive.value) { "USB 独占输出请使用离线 AI 演奏" }

            val desiredPackKey = packKey(pack)
            val existing = transformer
            if (existing is AiRealtimePianoPerformance &&
                activeSongKey == songKey(song) && existing.instrumentPackKey == desiredPackKey
            ) {
                existing.setMode(mode)
                activeMode = mode
                AiRealtimeOnnxPcmProcessor.setSharedLiveStream(stream, songKey(song))
                AiRealtimeOnnxPcmProcessor.reset("shared_performance_mode_changed")
                return@runCatching Unit
            }

            val token = generation.incrementAndGet()
            val created = AiRealtimePianoPerformance.create(appContext, pack, mode).getOrThrow()
            if (token != generation.get()) {
                created.close()
                error("AI 演奏请求已更新")
            }
            if (activeSongKey == songKey(song) && existing != null &&
                AiRealtimeSeparationController.state.value.enabled
            ) {
                // Shared growing stems already own separation. Keep that owner and replace only
                // the instrument transformer so selecting another instrument is immediate.
                transformer = created
                activeMode = mode
                AiRealtimeOnnxPcmProcessor.setSeparatedBlockTransformer(created)
                AiRealtimeOnnxPcmProcessor.setSharedLiveStream(stream, songKey(song))
            } else {
                installShared(song, mode, created, stream)
            }
            prewarmExecutor.execute { created.prewarm() }
            Unit
        }.onFailure { error ->
            Log.e(TAG, "AI_PERF_ACTIVATE_FAILED path=shared_separation ${sourceSummary(song)}", error)
            deactivateInternal(restoreRealtime = true)
        }
    }

    fun deactivate() {
        generation.incrementAndGet()
        deactivateInternal(restoreRealtime = true)
    }

    private fun isSupportedSource(song: AudioFile): Boolean =
        song.cueTrackIndex <= 0 && song.cueOffsetMs <= 0L && song.cueEndMs <= 0L

    private fun logSource(path: String, song: AudioFile) {
        Log.i(TAG, "AI_PERF_SOURCE path=$path ${sourceSummary(song)}")
    }

    private fun sourceSummary(song: AudioFile): String =
        "title=${song.displayName} ext=${song.path.substringAfterLast('.', "").lowercase()} " +
            "cueTrack=${song.cueTrackIndex} cueOffsetMs=${song.cueOffsetMs} cueEndMs=${song.cueEndMs} " +
            "source=${song.path}"

    private fun install(
        song: AudioFile,
        mode: AiMediaVariantMode,
        created: AiRealtimeSeparatedBlockTransformer,
    ) {
        // Do not disable/re-enable the realtime separator when AI Performance changes its
        // post-separation renderer (for example live RMVPE -> prepared MID after a stem result
        // becomes available). setSeparatedBlockTransformer() already closes the previous
        // transformer after in-flight work returns, so a full deactivate would only create an
        // audible hole and flip desiredEnabled=false in the ONNX owner.
        val realtimeWasEnabled = AiRealtimeSeparationController.state.value.enabled
        val retainedOwnership = enabledRealtimeByUs
        val previousMode = activeMode
        transformer = created
        activeSongKey = songKey(song)
        activeMode = mode
        enabledRealtimeByUs = retainedOwnership || !realtimeWasEnabled
        AiRealtimeOnnxPcmProcessor.setSharedLiveStream(null, "")
        AiRealtimeOnnxPcmProcessor.setSeparatedBlockTransformer(created)
        Log.i(
            TAG,
            "AI_PERF_HANDOFF path=realtime previous=$previousMode next=$mode " +
                "realtimeWasEnabled=$realtimeWasEnabled owner=$enabledRealtimeByUs " +
                "song=${Integer.toHexString(activeSongKey.hashCode())}",
        )
        // Re-assert the desired state without ever sending false first. This also atomically
        // refreshes a just-completed cached dependency before the prepared MIDI path begins.
        AiRealtimeSeparationController.setEnabled(appContext, true).getOrThrow()
    }

    private fun installShared(
        song: AudioFile,
        mode: AiMediaVariantMode,
        created: AiRealtimeSeparatedBlockTransformer,
        stream: AiSeparationLiveStreamState,
    ) {
        val realtimeWasEnabled = AiRealtimeSeparationController.state.value.enabled
        val retainedOwnership = enabledRealtimeByUs
        val previousMode = activeMode
        transformer = created
        activeSongKey = songKey(song)
        activeMode = mode
        enabledRealtimeByUs = retainedOwnership || !realtimeWasEnabled
        AiRealtimeOnnxPcmProcessor.setSeparatedBlockTransformer(created)
        AiRealtimeOnnxPcmProcessor.setSharedLiveStream(stream, songKey(song))
        Log.i(
            TAG,
            "AI_PERF_HANDOFF path=shared previous=$previousMode next=$mode " +
                "realtimeWasEnabled=$realtimeWasEnabled owner=$enabledRealtimeByUs " +
                "song=${Integer.toHexString(activeSongKey.hashCode())}",
        )
        AiRealtimeSeparationController.setEnabled(appContext, true).getOrThrow()
    }

    private fun deactivateInternal(restoreRealtime: Boolean) {
        val owned = enabledRealtimeByUs
        enabledRealtimeByUs = false
        activeSongKey = ""
        activeMode = null
        transformer = null
        AiRealtimeOnnxPcmProcessor.setSharedLiveStream(null, "")
        AiRealtimeOnnxPcmProcessor.setSeparatedBlockTransformer(null)
        if (restoreRealtime && owned) {
            AiRealtimeSeparationController.disable()
        }
    }

    private fun packKey(pack: AiInstalledInstrumentPack): String =
        "${pack.manifest.id}:${pack.manifest.version}:${pack.manifest.contentFingerprint()}"

    private fun songKey(song: AudioFile): String =
        "${song.path}|${song.cueOffsetMs}|${song.cueTrackIndex}"

    companion object {
        private const val TAG = "AiRealtimePerf"
        @Volatile private var instance: AiRealtimePerformanceController? = null

        fun get(context: Context): AiRealtimePerformanceController = instance ?: synchronized(this) {
            instance ?: AiRealtimePerformanceController(context.applicationContext).also { instance = it }
        }
    }
}
