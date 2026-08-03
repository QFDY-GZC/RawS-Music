package com.rawsmusic.module.player

import android.content.Context
import android.util.Log
import com.rawsmusic.module.player.dsp.BassBoostController
import com.rawsmusic.module.player.dsp.CompressorController
import com.rawsmusic.module.player.dsp.DynamicEqController
import com.rawsmusic.module.player.dsp.ExperimentalGainController
import com.rawsmusic.module.player.dsp.FftConvolverController
import com.rawsmusic.module.player.dsp.GraphicEQController
import com.rawsmusic.module.player.dsp.LoudnessBalanceController
import com.rawsmusic.module.player.dsp.MonoBassController
import com.rawsmusic.module.player.dsp.MoogLadderController
import com.rawsmusic.module.player.dsp.NativeDSPEngine
import com.rawsmusic.module.player.dsp.Panoramic360Controller
import com.rawsmusic.module.player.dsp.ParametricEQController
import com.rawsmusic.module.player.dsp.SpeakerOutputElasticityController
import com.rawsmusic.module.player.dsp.Surround360Controller
import com.rawsmusic.module.player.dsp.TrebleBoostController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the lazily-created DSP UI controllers and reconnects them whenever FFmpeg rebuilds the
 * native DSP engine. PlayerController keeps its existing public properties and methods as a thin
 * facade, while controller lifetime, mutual exclusion, and persisted IR restoration live here.
 */
internal class PlaybackDspControllerRegistry(
    context: Context,
    private val scope: CoroutineScope,
    private val engineProvider: () -> NativeDSPEngine?
) {
    private val context = context.applicationContext

    private var peq: ParametricEQController? = null
    private var graphicEq: GraphicEQController? = null
    private var experimentalGain: ExperimentalGainController? = null
    private var loudnessBalance: LoudnessBalanceController? = null
    private var monoBass: MonoBassController? = null
    private var dynamicEq: DynamicEqController? = null
    private var moogLadder: MoogLadderController? = null
    private var fftConvolver: FftConvolverController? = null
    private var compressor: CompressorController? = null
    private var bassBoost: BassBoostController? = null
    private var trebleBoost: TrebleBoostController? = null
    private var speakerOutputElasticity: SpeakerOutputElasticityController? = null
    private var surround360: Surround360Controller? = null
    private var panoramic360: Panoramic360Controller? = null
    private var fftConvolverRestoreJob: Job? = null

    val peqController: ParametricEQController
        get() = peq ?: ParametricEQController(engineOrStub("PEQ", logMissing = true)).also { peq = it }

    val graphicEqController: GraphicEQController
        get() = graphicEq ?: GraphicEQController(engineOrStub("GraphicEQ", logMissing = false)).also {
            graphicEq = it
            linkEqualizers()
        }

    val experimentalGainController: ExperimentalGainController
        get() = experimentalGain ?: ExperimentalGainController(engineOrStub("ExperimentalGain", logMissing = false)).also {
            experimentalGain = it
        }

    val loudnessBalanceController: LoudnessBalanceController
        get() = loudnessBalance ?: LoudnessBalanceController(engineOrStub("LoudnessBalance", logMissing = false)).also {
            loudnessBalance = it
        }

    val monoBassController: MonoBassController
        get() = monoBass ?: MonoBassController(engineOrStub("MonoBass", logMissing = false)).also { monoBass = it }

    val dynamicEqController: DynamicEqController
        get() = dynamicEq ?: DynamicEqController(engineOrStub("DynamicEQ", logMissing = false)).also { dynamicEq = it }

    val moogLadderController: MoogLadderController
        get() = moogLadder ?: MoogLadderController(engineOrStub("MoogLadder", logMissing = false)).also { moogLadder = it }

    val fftConvolverController: FftConvolverController
        get() = fftConvolver ?: FftConvolverController(engineOrStub("FFTConvolver", logMissing = true)).also {
            fftConvolver = it
        }

    val compressorController: CompressorController
        get() = compressor ?: CompressorController(engineOrStub("Compressor", logMissing = true)).also { compressor = it }

    val bassBoostController: BassBoostController
        get() = bassBoost ?: BassBoostController(engineOrStub("BassBoost", logMissing = true)).also { bassBoost = it }

    val trebleBoostController: TrebleBoostController
        get() = trebleBoost ?: TrebleBoostController(engineOrStub("TrebleBoost", logMissing = true)).also { trebleBoost = it }

    val speakerOutputElasticityController: SpeakerOutputElasticityController
        get() = speakerOutputElasticity
            ?: SpeakerOutputElasticityController(engineOrStub("SpeakerOutputElasticity", logMissing = true)).also {
                speakerOutputElasticity = it
            }

    val surround360Controller: Surround360Controller
        get() = surround360 ?: Surround360Controller(engineOrStub("Surround360", logMissing = true)).also { surround360 = it }

    val panoramic360Controller: Panoramic360Controller
        get() = panoramic360 ?: Panoramic360Controller(engineOrStub("Panoramic360", logMissing = true)).also { panoramic360 = it }

    fun ensurePeqConnected() {
        initializedEngine()?.let { engine ->
            val controller = peq ?: ParametricEQController(engine).also { peq = it }
            controller.connectEngine(engine)
            linkEqualizers()
        }
    }

    fun ensureGraphicEqConnected() {
        initializedEngine()?.let { engine ->
            val controller = graphicEq ?: GraphicEQController(engine).also { graphicEq = it }
            controller.connectEngine(engine)
            linkEqualizers()
        }
    }

    fun ensureExperimentalGainConnected() {
        initializedEngine()?.let { engine ->
            val controller = experimentalGain ?: ExperimentalGainController(engine).also { experimentalGain = it }
            controller.connectEngine(engine)
        }
    }

    fun ensureLoudnessBalanceConnected() {
        initializedEngine()?.let { engine ->
            (loudnessBalance ?: LoudnessBalanceController(engine).also { loudnessBalance = it })
                .connectEngine(engine)
        }
    }

    fun ensureMonoBassConnected() {
        initializedEngine()?.let { engine ->
            (monoBass ?: MonoBassController(engine).also { monoBass = it }).connectEngine(engine)
        }
    }

    fun ensureDynamicEqConnected() {
        initializedEngine()?.let { engine ->
            (dynamicEq ?: DynamicEqController(engine).also { dynamicEq = it }).connectEngine(engine)
        }
    }

    fun ensureMoogLadderConnected() {
        initializedEngine()?.let { engine ->
            (moogLadder ?: MoogLadderController(engine).also { moogLadder = it }).connectEngine(engine)
        }
    }

    fun ensureFftConvolverConnected() {
        val engine = initializedEngine() ?: return
        val controller = fftConvolver ?: FftConvolverController(engine).also { fftConvolver = it }
        controller.connectEngine(engine)

        if (controller.needsPersistedIrRestore()) {
            fftConvolverRestoreJob?.cancel()
            fftConvolverRestoreJob = scope.launch(Dispatchers.IO) {
                controller.restorePersistedIr(context)
            }
        }
    }

    fun ensureCompressorConnected() {
        initializedEngine()?.let { engine ->
            if (compressor == null) compressor = CompressorController(engine)
            else compressor?.connectEngine(engine)
        }
    }

    fun ensureBassBoostConnected() {
        initializedEngine()?.let { engine ->
            if (bassBoost == null) bassBoost = BassBoostController(engine)
            else bassBoost?.connectEngine(engine)
        }
    }

    fun ensureTrebleBoostConnected() {
        initializedEngine()?.let { engine ->
            if (trebleBoost == null) trebleBoost = TrebleBoostController(engine)
            else trebleBoost?.connectEngine(engine)
        }
    }

    fun ensureSpeakerOutputElasticityConnected() {
        initializedEngine()?.let { engine ->
            if (speakerOutputElasticity == null) {
                speakerOutputElasticity = SpeakerOutputElasticityController(engine)
            } else {
                speakerOutputElasticity?.connectEngine(engine)
            }
        }
    }

    fun ensureSurround360Connected() {
        initializedEngine()?.let { engine ->
            if (surround360 == null) surround360 = Surround360Controller(engine)
            else surround360?.connectEngine(engine)
        }
    }

    fun ensurePanoramic360Connected() {
        initializedEngine()?.let { engine ->
            if (panoramic360 == null) panoramic360 = Panoramic360Controller(engine)
            else panoramic360?.connectEngine(engine)
        }
    }

    private fun linkEqualizers() {
        val currentPeq = peq
        val currentGraphic = graphicEq
        currentPeq?.onExclusiveEnable = { currentGraphic?.disableForMutualExclusion() }
        currentGraphic?.onExclusiveEnable = { currentPeq?.disableForMutualExclusion() }

        if (currentPeq?.isEnabled?.value == true && currentGraphic?.isEnabled?.value == true) {
            currentGraphic.disableForMutualExclusion()
            initializedEngine()?.let(currentPeq::connectEngine)
        }
    }

    private fun initializedEngine(): NativeDSPEngine? = engineProvider()?.takeIf { it.isInitialized() }

    private fun engineOrStub(label: String, logMissing: Boolean): NativeDSPEngine {
        return engineProvider() ?: NativeDSPEngine().also {
            if (logMissing) Log.w(TAG, "DSP engine not available for $label, creating stub")
        }
    }

    private companion object {
        const val TAG = "PlaybackDspRegistry"
    }
}
