package com.rawsmusic.module.player.usb

enum class UsbDsdTransport(val prefValue: Int, val label: String) {
    DOP(0, "DoP"),
    NATIVE(1, "Native DSD"),
    PCM(2, "PCM");

    companion object {
        fun fromPref(value: Int): UsbDsdTransport {
            return entries.firstOrNull { it.prefValue == value } ?: NATIVE
        }
    }
}


/**
 * DSD source direct output and PCM→DSD conversion are intentionally separate.
 *
 * SOURCE_DIRECT: the file is already DSD.  Use the source 1-bit rate to derive
 * the USB container clock.
 *
 * PCM_TO_DSD: RawSMusic converts PCM to DSD in real time.  The DSD clock must
 * follow the PCM input clock family.  For example, DSD256 from 44.1k-family PCM
 * is 11.2896MHz and Native-DSD USB container rate is 352.8kHz; from 48k-family
 * PCM it is 12.288MHz and the container rate is 384kHz.  The old fixed 44.1k
 * calculation made DSD256 fail on devices that correctly expose 384k RAW_DATA.
 */
enum class UsbDsdPlaybackIntent {
    SOURCE_DIRECT,
    PCM_TO_DSD
}

data class UsbDsdModeConfig(
    val multiplier: Int,
    val transport: UsbDsdTransport,
    val deviceSampleRate: Int,
    val deviceBits: Int,
    val deviceSubslot: Int
)

fun dsdBaseRateForPcmSource(sourceRateHz: Int): Int {
    if (sourceRateHz > 0) {
        if (sourceRateHz % 48_000 == 0) return 48_000
        if (sourceRateHz % 44_100 == 0) return 44_100
    }
    return 44_100
}

fun dsdRateHzForMultiplier(multiplier: Int): Int = 44_100 * when (multiplier) {
    64, 128, 256, 512 -> multiplier
    else -> 64
}

fun dsdRateHzForPcmSource(multiplier: Int, sourceRateHz: Int): Int =
    dsdBaseRateForPcmSource(sourceRateHz) * when (multiplier) {
        64, 128, 256, 512 -> multiplier
        else -> 64
    }

fun normalizeDsdSourceRateHz(sourceRateHz: Int): Int {
    return when {
        sourceRateHz >= 2_822_400 -> sourceRateHz
        sourceRateHz >= 2_822_400 / 8 -> sourceRateHz * 8
        else -> 2_822_400
    }
}

/**
 * FFmpeg raw-DSD probing reports the DSD byte clock (for example DSD64 ->
 * 352800, DSD512 -> 2822400) rather than the real 1-bit stream rate.
 */
fun normalizeProbedDsdSourceRateHz(probedRateHz: Int): Int {
    if (probedRateHz <= 0) return 2_822_400
    return when {
        // If the probed value is already beyond DSD1024 byte-clock territory,
        // treat it as a true 1-bit rate and keep it as-is.
        probedRateHz > 6_144_000 -> probedRateHz
        else -> probedRateHz * 8
    }
}

fun dsdMultiplierFromSourceRate(sourceDsdRateHz: Int): Int {
    val normalizedRate = normalizeDsdSourceRateHz(sourceDsdRateHz)
    return when {
        normalizedRate >= 22_579_200 -> 512
        normalizedRate >= 11_289_600 -> 256
        normalizedRate >= 5_644_800 -> 128
        normalizedRate >= 2_822_400 -> 64
        else -> 64
    }
}

private fun deviceRateForTransport(
    multiplier: Int,
    transport: UsbDsdTransport,
    sourceRateHz: Int,
    sourceIsAlreadyDsd: Boolean
): Int {
    val dsdRateHz = if (sourceIsAlreadyDsd) {
        normalizeDsdSourceRateHz(sourceRateHz)
    } else {
        dsdRateHzForPcmSource(multiplier, sourceRateHz)
    }
    return when (transport) {
        UsbDsdTransport.DOP -> dsdRateHz / 16
        UsbDsdTransport.NATIVE -> dsdRateHz / 32
        UsbDsdTransport.PCM -> 0
    }
}

fun buildSupportedDsdSourceDirectModeConfig(
    sourceDsdRateHz: Int,
    requestedTransport: UsbDsdTransport,
    capabilities: UsbDeviceAudioCapabilities?
): UsbDsdModeConfig? {
    val multiplier = dsdMultiplierFromSourceRate(sourceDsdRateHz)
    if (requestedTransport == UsbDsdTransport.PCM) return null
    val caps = capabilities ?: return buildUsbDsdModeConfig(true, multiplier, requestedTransport, sourceDsdRateHz, true)

    val requestedRate = deviceRateForTransport(multiplier, requestedTransport, sourceDsdRateHz, true)
    val requestedSupported = when (requestedTransport) {
        UsbDsdTransport.DOP -> caps.supportsDopDeviceRate(requestedRate)
        UsbDsdTransport.NATIVE -> caps.supportsNativeDsdDeviceRate(requestedRate)
        UsbDsdTransport.PCM -> false
    }
    if (requestedSupported) return buildUsbDsdModeConfig(true, multiplier, requestedTransport, sourceDsdRateHz, true)

    val nativeRate = deviceRateForTransport(multiplier, UsbDsdTransport.NATIVE, sourceDsdRateHz, true)
    if (caps.supportsNativeDsdDeviceRate(nativeRate) || caps.hasAnyNativeDsdDescriptor) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE, sourceDsdRateHz, true)
    }
    val dopRate = deviceRateForTransport(multiplier, UsbDsdTransport.DOP, sourceDsdRateHz, true)
    if (caps.supportsDopDeviceRate(dopRate)) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.DOP, sourceDsdRateHz, true)
    }
    return null
}

fun buildSupportedPcmToDsdModeConfig(
    enabled: Boolean,
    multiplier: Int,
    requestedTransport: UsbDsdTransport = UsbDsdTransport.NATIVE,
    capabilities: UsbDeviceAudioCapabilities?,
    sourceSampleRate: Int = 44_100
): UsbDsdModeConfig? {
    if (!enabled) return null
    val requested = if (requestedTransport == UsbDsdTransport.PCM) UsbDsdTransport.NATIVE else requestedTransport
    val caps = capabilities ?: return buildUsbDsdModeConfig(true, multiplier, requested, sourceSampleRate, false)

    val requestedRate = deviceRateForTransport(multiplier, requested, sourceSampleRate, false)
    val requestedSupported = when (requested) {
        UsbDsdTransport.DOP -> caps.supportsDopDeviceRate(requestedRate)
        UsbDsdTransport.NATIVE -> caps.supportsNativeDsdDeviceRate(requestedRate)
        UsbDsdTransport.PCM -> false
    }
    if (requestedSupported) return buildUsbDsdModeConfig(true, multiplier, requested, sourceSampleRate, false)

    // DSD256 DoP needs 705.6/768kHz PCM; many dongles top out at 352.8/384k PCM
    // but support RAW_DATA Native DSD.  Fall forward to Native rather than
    // silently disabling PCM→DSD.
    val nativeRate = deviceRateForTransport(multiplier, UsbDsdTransport.NATIVE, sourceSampleRate, false)
    if (caps.supportsNativeDsdDeviceRate(nativeRate) || caps.hasAnyNativeDsdDescriptor) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE, sourceSampleRate, false)
    }
    val dopRate = deviceRateForTransport(multiplier, UsbDsdTransport.DOP, sourceSampleRate, false)
    if (caps.supportsDopDeviceRate(dopRate)) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.DOP, sourceSampleRate, false)
    }
    return null
}

fun buildUsbDsdModeConfig(
    enabled: Boolean,
    multiplier: Int,
    transport: UsbDsdTransport,
    sourceRateHz: Int = 44_100,
    sourceIsAlreadyDsd: Boolean = false
): UsbDsdModeConfig? {
    if (!enabled) return null
    val deviceRate = deviceRateForTransport(multiplier, transport, sourceRateHz, sourceIsAlreadyDsd)
    return if (transport == UsbDsdTransport.DOP) {
        UsbDsdModeConfig(
            multiplier = multiplier,
            transport = UsbDsdTransport.DOP,
            deviceSampleRate = deviceRate,
            deviceBits = 24,
            deviceSubslot = 3
        )
    } else {
        UsbDsdModeConfig(
            multiplier = multiplier,
            transport = UsbDsdTransport.NATIVE,
            deviceSampleRate = deviceRate,
            deviceBits = 32,
            deviceSubslot = 4
        )
    }
}

fun buildUsbDsdModeConfig(
    enabled: Boolean,
    multiplier: Int,
    dopEnabled: Boolean
): UsbDsdModeConfig? = buildUsbDsdModeConfig(
    enabled = enabled,
    multiplier = multiplier,
    transport = if (dopEnabled) UsbDsdTransport.DOP else UsbDsdTransport.NATIVE
)

fun buildSupportedUsbDsdModeConfig(
    enabled: Boolean,
    multiplier: Int,
    transport: UsbDsdTransport,
    capabilities: UsbDeviceAudioCapabilities?
): UsbDsdModeConfig? {
    if (!enabled || transport == UsbDsdTransport.PCM) return null
    val caps = capabilities ?: return buildUsbDsdModeConfig(true, multiplier, transport)
    val rate = deviceRateForTransport(multiplier, transport, 44_100, false)
    val ok = when (transport) {
        UsbDsdTransport.DOP -> caps.supportsDopDeviceRate(rate)
        UsbDsdTransport.NATIVE -> caps.supportsNativeDsdDeviceRate(rate)
        UsbDsdTransport.PCM -> false
    }
    if (ok) return buildUsbDsdModeConfig(true, multiplier, transport)
    val nativeRate = deviceRateForTransport(multiplier, UsbDsdTransport.NATIVE, 44_100, false)
    if (caps.supportsNativeDsdDeviceRate(nativeRate) || caps.hasAnyNativeDsdDescriptor) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE)
    }
    return null
}

fun isLikelyDsdSource(
    path: String?,
    probedBits: Int,
    probedSampleRate: Int
): Boolean {
    if (probedBits == 1) return true
    val normalized = path?.lowercase() ?: return false
    if (
        normalized.endsWith(".dsf") ||
        normalized.endsWith(".dff") ||
        normalized.endsWith(".dsdiff")
    ) {
        return true
    }
    return probedSampleRate >= 2_822_400 && probedBits <= 1
}

fun chooseDsdSourcePcmDecodeRate(sourceDsdRateHz: Int): Int {
    val derived = when {
        sourceDsdRateHz >= 11_289_600 -> 352_800
        sourceDsdRateHz >= 5_644_800 -> 352_800
        sourceDsdRateHz >= 2_822_400 -> 176_400
        else -> 176_400
    }
    return derived.coerceAtLeast(88_200)
}
