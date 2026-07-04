package com.rawsmusic.module.player.usb

enum class UsbDsdTransport(val prefValue: Int, val label: String) {
    DOP(0, "DoP"),
    NATIVE(1, "Native DSD");

    companion object {
        fun fromPref(value: Int): UsbDsdTransport {
            return entries.firstOrNull { it.prefValue == value } ?: NATIVE
        }
    }
}


/**
 * DSD source direct output and PCM->DSD conversion are intentionally separate.
 *
 * - SOURCE_DIRECT: source file is already DSD; choose a USB DSD transport for
 *   the source rate and do not treat the user PCM->DSD switch as required.
 * - PCM_TO_DSD: source is PCM; RawSMusic converts PCM to DSD. For now this is
 *   Native DSD only so DoP remains a compatibility transport for DSD source
 *   bitstreams, not the realtime PCM->DSD path.
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

fun dsdRateHzForMultiplier(multiplier: Int): Int = when (multiplier) {
    64 -> 2_822_400
    128 -> 5_644_800
    256 -> 11_289_600
    512 -> 22_579_200
    else -> 2_822_400
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
 *
 * Keep this separate from [normalizeDsdSourceRateHz]: callers that already have
 * header-level DSF/DFF rates from metadata must not be multiplied again.
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

fun buildSupportedDsdSourceDirectModeConfig(
    sourceDsdRateHz: Int,
    requestedTransport: UsbDsdTransport,
    capabilities: UsbDeviceAudioCapabilities?
): UsbDsdModeConfig? {
    val multiplier = dsdMultiplierFromSourceRate(sourceDsdRateHz)
    val caps = capabilities ?: return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE)
    if (caps.supportsNativeDsd(multiplier) || caps.nativeDsdFormats.isNotEmpty()) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE)
    }
    if (requestedTransport == UsbDsdTransport.DOP && caps.supportsDop(multiplier)) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.DOP)
    }
    return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE)
}

fun buildSupportedPcmToDsdModeConfig(
    enabled: Boolean,
    multiplier: Int,
    capabilities: UsbDeviceAudioCapabilities?
): UsbDsdModeConfig? {
    if (!enabled) return null
    // PCM->DSD is Native DSD only.  DoP is only a container/compatibility
    // transport for existing DSD source material.
    //
    // Be deliberately optimistic when the current capability snapshot has not
    // yet observed any RAW_DATA descriptor. Right after app-data clear, or
    // before the first full native scan/RAW session, Kotlin may only have a
    // partial PCM-format snapshot. In that phase, blocking PCM->DSD here makes
    // the feature appear "unavailable until a real DSD track is played once".
    // Native init / descriptor scoring remains authoritative and will fall
    // back to PCM if the DAC truly lacks Native DSD.
    val caps = capabilities ?: return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE)
    if (caps.supportsNativeDsd(multiplier)) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE)
    }
    if (caps.nativeDsdFormats.isEmpty() && !caps.hasAnyNativeDsdDescriptor) {
        return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE)
    }
    if (!caps.hasAnyNativeDsdDescriptor) return null
    return buildUsbDsdModeConfig(true, multiplier, UsbDsdTransport.NATIVE)
}

fun buildUsbDsdModeConfig(
    enabled: Boolean,
    multiplier: Int,
    transport: UsbDsdTransport
): UsbDsdModeConfig? {
    if (!enabled) return null
    val rate = dsdRateHzForMultiplier(multiplier)
    return if (transport == UsbDsdTransport.DOP) {
        UsbDsdModeConfig(
            multiplier = multiplier,
            transport = UsbDsdTransport.DOP,
            deviceSampleRate = rate / 16,
            deviceBits = 24,
            deviceSubslot = 3
        )
    } else {
        UsbDsdModeConfig(
            multiplier = multiplier,
            transport = UsbDsdTransport.NATIVE,
            deviceSampleRate = rate / 32,
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
    if (!enabled) return null
    val caps = capabilities ?: return buildUsbDsdModeConfig(true, multiplier, transport)
    val resolvedTransport = caps.preferredDsdTransport(multiplier, transport) ?: return null
    return buildUsbDsdModeConfig(true, multiplier, resolvedTransport)
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
