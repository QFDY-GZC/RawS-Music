package com.rawsmusic.module.player.dsp

/** Android app_process smoke test: real DSP JNI, no audio device or UI. */
object ReverbJniHarness {
    @JvmStatic fun main(args: Array<String>) {
        val engine=NativeDSPEngine()
        engine.init(48000,2)
        engine.setReverbParams(ReverbSettings(mix=1f,preDelayMs=0f,decaySeconds=2f))
        engine.setReverbEnabled(true)
        check(engine.hasActiveEffects())
        val warm=FloatArray(9600);engine.processFloat(warm,warm.size,2)
        val impulse=FloatArray(48000*3*2);impulse[0]=1f
        engine.processFloat(impulse,impulse.size,2)
        check(impulse.drop(9600).any { kotlin.math.abs(it)>1e-6f })
        check(impulse.all { it.isFinite() })
        engine.resetReverb();val silent=FloatArray(9600);engine.processFloat(silent,silent.size,2)
        check(silent.all { it==0f })
        engine.setReverbEnabled(false);engine.processFloat(silent,silent.size,2)
        check(!engine.hasActiveEffects())
        engine.release()
        println("REVERB_PACKAGED_JNI_ENABLE_PARAMS_IMPULSE_RESET_DISABLE=true")
    }
}
