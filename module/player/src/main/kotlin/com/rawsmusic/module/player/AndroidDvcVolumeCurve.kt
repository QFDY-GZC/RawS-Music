package com.rawsmusic.module.player

import kotlin.math.pow

internal object AndroidDvcVolumeCurve {
    const val MAX_STEPS = 60
    private const val MIN_DB = -60f

    data class Plan(
        val systemStep: Int,
        val nativeGain: Float,
        val targetDb: Float,
        val systemDb: Float,
    )

    fun linearToGain(linear: Float): Float {
        val volume = linear.coerceIn(0f, 1f)
        if (volume <= 0.0001f) return 0f
        if (volume >= 0.9999f) return 1f
        return dbToGain(linearToDb(volume))
    }

    fun linearToDb(linear: Float): Float =
        MIN_DB * (1f - linear.coerceIn(0f, 1f))

    fun dbToLinear(db: Float): Float =
        ((db.coerceIn(MIN_DB, 0f) - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)

    fun linearToStep(linear: Float): Int =
        (linear.coerceIn(0f, 1f) * MAX_STEPS).toInt().coerceIn(0, MAX_STEPS)

    fun stepToLinear(step: Int): Float =
        step.coerceIn(0, MAX_STEPS).toFloat() / MAX_STEPS.toFloat()

    fun plan(
        linear: Float,
        systemMaxStep: Int,
        systemDbAt: (Int) -> Float?,
    ): Plan {
        val maxStep = systemMaxStep.coerceAtLeast(1)
        val volume = linear.coerceIn(0f, 1f)
        if (volume <= 0.0001f) {
            return Plan(systemStep = 0, nativeGain = 0f, targetDb = MIN_DB, systemDb = MIN_DB)
        }

        val targetDb = linearToDb(volume)
        var selectedStep = maxStep
        var selectedDb = systemDbAt(maxStep)?.takeIf(Float::isFinite) ?: 0f
        for (step in 1..maxStep) {
            val stepDb = systemDbAt(step)?.takeIf(Float::isFinite)
                ?: fallbackSystemDb(step, maxStep)
            if (stepDb >= targetDb) {
                selectedStep = step
                selectedDb = stepDb
                break
            }
        }
        val residualDb = (targetDb - selectedDb).coerceAtMost(0f)
        return Plan(
            systemStep = selectedStep,
            nativeGain = dbToGain(residualDb),
            targetDb = targetDb,
            systemDb = selectedDb,
        )
    }

    private fun fallbackSystemDb(step: Int, maxStep: Int): Float =
        MIN_DB * (1f - step.coerceIn(0, maxStep).toFloat() / maxStep.toFloat())

    private fun dbToGain(db: Float): Float =
        10.0.pow(db.toDouble() / 20.0).toFloat().coerceIn(0f, 1f)
}
