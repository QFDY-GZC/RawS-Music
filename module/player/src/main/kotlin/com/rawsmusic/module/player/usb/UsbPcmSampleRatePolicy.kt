package com.rawsmusic.module.player.usb

/**
 * Chooses the PCM rate for non-strict USB-exclusive playback.
 *
 * Device-advertised rates are authoritative when present. This is especially
 * important for UAC1 fixed-rate endpoints: asking a 48 kHz-only alt to consume
 * 44.1 kHz packets can keep USB byte counters perfectly healthy while producing
 * wrong-speed / distorted audio at the DAC.
 */
internal object UsbPcmSampleRatePolicy {
    private val legacyFallbackRates = listOf(
        44_100, 48_000, 88_200, 96_000, 176_400, 192_000,
    )

    data class Decision(
        val selectedRate: Int,
        val requestedRate: Int,
        val advertisedRates: List<Int>,
        val requestedRateRejected: Boolean,
    )

    fun choose(
        sourceRate: Int,
        requestedRate: Int,
        advertisedRates: List<Int>,
    ): Decision {
        val deviceRates = advertisedRates
            .asSequence()
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toList()
        val rates = deviceRates.ifEmpty { legacyFallbackRates }

        if (requestedRate > 0) {
            if (deviceRates.isEmpty() || requestedRate in deviceRates) {
                return Decision(
                    selectedRate = requestedRate,
                    requestedRate = requestedRate,
                    advertisedRates = deviceRates,
                    requestedRateRejected = false,
                )
            }
            return Decision(
                selectedRate = nearestRate(requestedRate, deviceRates),
                requestedRate = requestedRate,
                advertisedRates = deviceRates,
                requestedRateRejected = true,
            )
        }

        val target = if (sourceRate > 0) sourceRate else 48_000
        val selected = rates.firstOrNull { it >= target } ?: rates.last()
        return Decision(
            selectedRate = selected,
            requestedRate = requestedRate,
            advertisedRates = deviceRates,
            requestedRateRejected = false,
        )
    }

    private fun nearestRate(target: Int, rates: List<Int>): Int =
        rates.minWithOrNull(
            compareBy<Int> { kotlin.math.abs(it.toLong() - target.toLong()) }
                .thenBy { it },
        ) ?: target
}
