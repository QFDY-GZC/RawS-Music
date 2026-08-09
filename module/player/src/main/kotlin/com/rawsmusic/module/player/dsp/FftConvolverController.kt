package com.rawsmusic.module.player.dsp

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Runtime controller for the native FFT convolver.
 *
 * Native/JNI code remains in its own files. WAV parsing is delegated to
 * [FftConvolverIrLoader], while this class owns persisted parameters, current IR
 * identity, resampling, and reconnecting the IR after a DSP engine rebuild.
 */
class FftConvolverController(private var nativeEngine: NativeDSPEngine) {
    companion object {
        private const val TAG = "FftConvolverController"
        private const val MAX_NATIVE_IR_FRAMES = 32768
        private const val PERSIST_DEBOUNCE_MS = 300L
    }

    enum class LoadState {
        EMPTY,
        LOADING,
        READY,
        ERROR
    }

    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { persistParameters() }

    private val _isEnabled = MutableStateFlow(AppPreferences.FftConvolver.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _wet = MutableStateFlow(AppPreferences.FftConvolver.wet)
    val wet: StateFlow<Float> = _wet.asStateFlow()

    private val _dry = MutableStateFlow(AppPreferences.FftConvolver.dry)
    val dry: StateFlow<Float> = _dry.asStateFlow()

    private val _gainDb = MutableStateFlow(AppPreferences.FftConvolver.gainDb)
    val gainDb: StateFlow<Float> = _gainDb.asStateFlow()

    private val _preDelayMs = MutableStateFlow(AppPreferences.FftConvolver.preDelayMs)
    val preDelayMs: StateFlow<Float> = _preDelayMs.asStateFlow()

    private val _latencyFrames = MutableStateFlow(0)
    val latencyFrames: StateFlow<Int> = _latencyFrames.asStateFlow()

    private val _outputSampleRate = MutableStateFlow(nativeEngine.sampleRate)
    val outputSampleRate: StateFlow<Int> = _outputSampleRate.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState.EMPTY)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _loadError = MutableStateFlow("")
    val loadError: StateFlow<String> = _loadError.asStateFlow()

    private val _irDisplayName = MutableStateFlow(AppPreferences.FftConvolver.irName)
    val irDisplayName: StateFlow<String> = _irDisplayName.asStateFlow()

    private val _irSourceSampleRate = MutableStateFlow(AppPreferences.FftConvolver.irSampleRate)
    val irSourceSampleRate: StateFlow<Int> = _irSourceSampleRate.asStateFlow()

    private val _irChannels = MutableStateFlow(AppPreferences.FftConvolver.irChannels)
    val irChannels: StateFlow<Int> = _irChannels.asStateFlow()

    private val _irFrames = MutableStateFlow(AppPreferences.FftConvolver.irFrames)
    val irFrames: StateFlow<Int> = _irFrames.asStateFlow()

    private var sourceIr: FftConvolverIrLoader.IrData? = null
    private var preparedIr: FloatArray? = null
    private var preparedFrames = 0
    private var preparedChannels = 0
    private var preparedSampleRate = 0

    init {
        syncParametersToNative()
        if (AppPreferences.FftConvolver.irUri.isBlank()) {
            _loadState.value = LoadState.EMPTY
        }
    }

    fun connectEngine(engine: NativeDSPEngine) {
        nativeEngine = engine
        _outputSampleRate.value = engine.sampleRate
        val loaded = sourceIr?.let { loadSourceIntoNative(it) } ?: false
        if (!loaded) {
            syncParametersToNative()
            NativeFftConvolverBridge.setEnabled(nativeEngine, false)
            _isReady.value = false
            _latencyFrames.value = NativeFftConvolverBridge.latencyFrames(nativeEngine)
        }
        Log.d(TAG, "Reconnected to DSP engine, ready=${_isReady.value}, enabled=${_isEnabled.value}")
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        AppPreferences.FftConvolver.isEnabled = enabled
        if (nativeEngine.isInitialized()) {
            NativeFftConvolverBridge.setEnabled(nativeEngine, enabled && _isReady.value)
        }
    }

    fun setWetDry(wet: Float, dry: Float) {
        _wet.value = sanitizeMix(wet, 1f)
        _dry.value = sanitizeMix(dry, 0f)
        syncParametersToNative()
        persistDebounced()
    }

    fun setGainDb(gainDb: Float) {
        _gainDb.value = sanitizeGain(gainDb)
        syncParametersToNative()
        persistDebounced()
    }

    fun setPreDelayMs(preDelayMs: Float) {
        _preDelayMs.value = sanitizePreDelay(preDelayMs)
        syncParametersToNative()
        persistDebounced()
    }

    fun setMix(
        wet: Float,
        dry: Float,
        gainDb: Float = _gainDb.value,
        preDelayMs: Float = _preDelayMs.value
    ) {
        _wet.value = sanitizeMix(wet, 1f)
        _dry.value = sanitizeMix(dry, 0f)
        _gainDb.value = sanitizeGain(gainDb)
        _preDelayMs.value = sanitizePreDelay(preDelayMs)
        syncParametersToNative()
        persistDebounced()
    }

    /** Load a WAV document selected by the user and remember its persistable URI. */
    suspend fun loadIrFromUri(context: Context, uri: Uri, displayName: String): Boolean {
        _loadState.value = LoadState.LOADING
        _loadError.value = ""

        val wasReady = _isReady.value
        val result = FftConvolverIrLoader.load(context.applicationContext, uri, displayName)
        val data = result.getOrElse { error ->
            _isReady.value = wasReady
            _loadState.value = LoadState.ERROR
            _loadError.value = error.message.orEmpty()
            if (!wasReady && nativeEngine.isInitialized()) {
                NativeFftConvolverBridge.setEnabled(nativeEngine, false)
            }
            Log.e(TAG, "Unable to decode IR", error)
            return false
        }

        return withContext(Dispatchers.Default) {
            applySourceIr(data, persistIdentity = true)
        }
    }

    /** Restore a previously selected persistable document after process restart. */
    suspend fun restorePersistedIr(context: Context): Boolean {
        val storedUri = AppPreferences.FftConvolver.irUri
        if (storedUri.isBlank() || sourceIr != null || _loadState.value == LoadState.LOADING) {
            return sourceIr != null
        }

        _loadState.value = LoadState.LOADING
        _loadError.value = ""
        val uri = runCatching { Uri.parse(storedUri) }.getOrNull()
        if (uri == null) {
            _loadState.value = LoadState.ERROR
            _loadError.value = "Saved IR document is unavailable"
            return false
        }

        val result = FftConvolverIrLoader.load(
            context.applicationContext,
            uri,
            AppPreferences.FftConvolver.irName
        )
        val data = result.getOrElse { error ->
            _isReady.value = false
            _loadState.value = LoadState.ERROR
            _loadError.value = error.message.orEmpty()
            if (nativeEngine.isInitialized()) {
                NativeFftConvolverBridge.setEnabled(nativeEngine, false)
            }
            Log.e(TAG, "Unable to restore persisted IR", error)
            return false
        }
        return withContext(Dispatchers.Default) {
            applySourceIr(data, persistIdentity = false)
        }
    }

    fun needsPersistedIrRestore(): Boolean =
        sourceIr == null &&
            AppPreferences.FftConvolver.irUri.isNotBlank() &&
            _loadState.value != LoadState.LOADING

    /**
     * Load an already decoded/resampled interleaved float IR. This remains useful
     * for tests and future AutoEQ/IR generators that do not start from a WAV URI.
     */
    fun loadIr(ir: FloatArray, frames: Int, channels: Int): Boolean {
        if (channels !in 1..2) return false
        val safeFrames = frames.coerceIn(1, MAX_NATIVE_IR_FRAMES)
        if (ir.size < safeFrames * channels) return false
        val source = FftConvolverIrLoader.IrData(
            samples = ir.copyOf(safeFrames * channels),
            frames = safeFrames,
            channels = channels,
            sampleRate = nativeEngine.sampleRate.coerceAtLeast(8_000),
            displayName = "Memory IR",
            uri = ""
        )
        return applySourceIr(source, persistIdentity = false)
    }

    fun clearIr() {
        sourceIr = null
        preparedIr = null
        preparedFrames = 0
        preparedChannels = 0
        preparedSampleRate = 0
        _isReady.value = false
        _loadState.value = LoadState.EMPTY
        _loadError.value = ""
        _irDisplayName.value = ""
        _irSourceSampleRate.value = 0
        _irChannels.value = 0
        _irFrames.value = 0
        _latencyFrames.value = 0

        AppPreferences.FftConvolver.irUri = ""
        AppPreferences.FftConvolver.irName = ""
        AppPreferences.FftConvolver.irSampleRate = 0
        AppPreferences.FftConvolver.irChannels = 0
        AppPreferences.FftConvolver.irFrames = 0

        if (nativeEngine.isInitialized()) {
            NativeFftConvolverBridge.setEnabled(nativeEngine, false)
            NativeFftConvolverBridge.clearIr(nativeEngine)
        }
    }

    private fun applySourceIr(
        data: FftConvolverIrLoader.IrData,
        persistIdentity: Boolean
    ): Boolean {
        sourceIr = data
        preparedIr = null
        preparedFrames = 0
        preparedChannels = 0
        preparedSampleRate = 0

        _irDisplayName.value = data.displayName
        _irSourceSampleRate.value = data.sampleRate
        _irChannels.value = data.channels
        _irFrames.value = data.frames

        if (persistIdentity && data.uri.isNotBlank()) {
            AppPreferences.FftConvolver.irUri = data.uri
        }
        AppPreferences.FftConvolver.irName = data.displayName
        AppPreferences.FftConvolver.irSampleRate = data.sampleRate
        AppPreferences.FftConvolver.irChannels = data.channels
        AppPreferences.FftConvolver.irFrames = data.frames

        val loaded = if (nativeEngine.isInitialized()) {
            loadSourceIntoNative(data)
        } else {
            _isReady.value = false
            false
        }

        _loadState.value = if (nativeEngine.isInitialized() && !loaded) {
            _loadError.value = "Native convolver rejected this IR"
            LoadState.ERROR
        } else {
            _loadError.value = ""
            LoadState.READY
        }
        return loaded || !nativeEngine.isInitialized()
    }

    private fun loadSourceIntoNative(data: FftConvolverIrLoader.IrData): Boolean {
        if (!nativeEngine.isInitialized()) return false
        val targetRate = nativeEngine.sampleRate.coerceAtLeast(8_000)
        _outputSampleRate.value = targetRate

        val prepared = if (
            preparedIr != null &&
            preparedSampleRate == targetRate &&
            preparedChannels == data.channels
        ) {
            preparedIr!!
        } else {
            resampleAndLimit(data, targetRate).also { result ->
                preparedIr = result.samples
                preparedFrames = result.frames
                preparedChannels = data.channels
                preparedSampleRate = targetRate
            }.samples
        }

        val frames = (if (preparedFrames > 0) {
            preparedFrames
        } else {
            prepared.size / data.channels
        }).coerceAtMost(MAX_NATIVE_IR_FRAMES)
        val ok = NativeFftConvolverBridge.loadIr(
            nativeEngine,
            prepared,
            frames,
            data.channels
        )
        _isReady.value = ok
        syncParametersToNative()
        NativeFftConvolverBridge.setEnabled(nativeEngine, _isEnabled.value && ok)
        _latencyFrames.value = NativeFftConvolverBridge.latencyFrames(nativeEngine)
        if (ok) {
            _loadState.value = LoadState.READY
            _loadError.value = ""
        }
        Log.d(
            TAG,
            "IR native load=$ok source=${data.sampleRate}Hz/${data.frames}f " +
                "target=${targetRate}Hz/${frames}f channels=${data.channels} latency=${_latencyFrames.value}"
        )
        return ok
    }

    private data class PreparedIr(val samples: FloatArray, val frames: Int)

    private fun resampleAndLimit(
        data: FftConvolverIrLoader.IrData,
        targetRate: Int
    ): PreparedIr {
        val sourceFrames = data.frames.coerceAtMost(data.samples.size / data.channels)
        if (sourceFrames <= 0) return PreparedIr(FloatArray(0), 0)

        val targetFrames = (
            sourceFrames.toDouble() * targetRate.toDouble() / data.sampleRate.toDouble()
        ).roundToInt().coerceIn(1, MAX_NATIVE_IR_FRAMES)

        if (data.sampleRate == targetRate) {
            val frames = minOf(sourceFrames, MAX_NATIVE_IR_FRAMES)
            return PreparedIr(data.samples.copyOf(frames * data.channels), frames)
        }

        val output = FloatArray(targetFrames * data.channels)
        val sourcePerTarget = data.sampleRate.toDouble() / targetRate.toDouble()
        for (frame in 0 until targetFrames) {
            val position = frame * sourcePerTarget
            val leftFrame = floor(position).toInt().coerceIn(0, sourceFrames - 1)
            val rightFrame = (leftFrame + 1).coerceAtMost(sourceFrames - 1)
            val fraction = (position - leftFrame).toFloat()
            for (channel in 0 until data.channels) {
                val a = data.samples[leftFrame * data.channels + channel]
                val b = data.samples[rightFrame * data.channels + channel]
                output[frame * data.channels + channel] = a + (b - a) * fraction
            }
        }
        return PreparedIr(output, targetFrames)
    }

    private fun syncParametersToNative() {
        if (!nativeEngine.isInitialized()) return
        NativeFftConvolverBridge.setMix(
            nativeEngine,
            _wet.value,
            _dry.value,
            _gainDb.value,
            _preDelayMs.value
        )
        _latencyFrames.value = NativeFftConvolverBridge.latencyFrames(nativeEngine)
    }

    private fun persistDebounced() {
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun persistParameters() {
        AppPreferences.FftConvolver.wet = _wet.value
        AppPreferences.FftConvolver.dry = _dry.value
        AppPreferences.FftConvolver.gainDb = _gainDb.value
        AppPreferences.FftConvolver.preDelayMs = _preDelayMs.value
    }

    private fun sanitizeMix(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 2f) else fallback

    private fun sanitizeGain(value: Float): Float =
        if (value.isFinite()) value.coerceIn(-24f, 24f) else 0f

    private fun sanitizePreDelay(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 500f) else 0f
}
