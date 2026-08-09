package com.rawsmusic.module.player.usb

/** Immutable inputs used when rebuilding the native USB policy after a user setting change. */
internal data class UsbPolicyRestartSourceInputs(
    val sourcePath: String?,
    val metadataSampleRate: Int,
    val metadataBitsPerSample: Int,
    val metadataChannels: Int,
    val sourceLooksLikeDsd: Boolean,
    val probedSampleRate: Int,
    val probedBitsPerSample: Int,
    val probedChannels: Int,
    val runtimeSampleRate: Int,
    val runtimeBitsPerSample: Int,
    val runtimeChannels: Int,
)

internal data class UsbPolicyRestartSource(
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val sourcePath: String?,
    val inferredDsd: Boolean,
)

/** Resolves one stable source format without retaining PlayerController or native engine state. */
internal object UsbPolicyRestartSourceResolver {
    fun resolve(inputs: UsbPolicyRestartSourceInputs): UsbPolicyRestartSource {
        val inferredDsd =
            inputs.sourceLooksLikeDsd ||
                inputs.metadataBitsPerSample == 1 ||
                inputs.probedBitsPerSample == 1 ||
                inputs.metadataSampleRate >= DSD64_RATE_HZ ||
                inputs.probedSampleRate >= DSD64_RATE_HZ

        val sampleRate = firstPositive(
            inputs.metadataSampleRate,
            inputs.probedSampleRate,
            inputs.runtimeSampleRate,
            DEFAULT_SAMPLE_RATE,
        )
        val bitsPerSample = if (inferredDsd) {
            firstPositive(inputs.metadataBitsPerSample, inputs.probedBitsPerSample, 1)
        } else {
            firstPositive(
                inputs.metadataBitsPerSample,
                inputs.probedBitsPerSample,
                inputs.runtimeBitsPerSample,
                DEFAULT_BITS_PER_SAMPLE,
            )
        }
        val channels = firstPositive(
            inputs.metadataChannels,
            inputs.probedChannels,
            inputs.runtimeChannels,
            DEFAULT_CHANNELS,
        )
        return UsbPolicyRestartSource(
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            sourcePath = inputs.sourcePath,
            inferredDsd = inferredDsd,
        )
    }

    private fun firstPositive(vararg values: Int): Int = values.first { it > 0 }

    private const val DSD64_RATE_HZ = 2_822_400
    private const val DEFAULT_SAMPLE_RATE = 48_000
    private const val DEFAULT_BITS_PER_SAMPLE = 16
    private const val DEFAULT_CHANNELS = 2
}
