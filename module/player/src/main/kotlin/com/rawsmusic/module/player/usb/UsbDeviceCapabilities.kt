package com.rawsmusic.module.player.usb

data class UsbPcmFormatCapability(
    val sampleRate: Int,
    val channels: Int,
    val validBits: Int,
    val subslotBytes: Int,
    val interfaceNumber: Int,
    val altSetting: Int,
    val outEndpoint: Int,
    val feedbackEndpoint: Int,
    val isPcm: Boolean = true,
    val isRawData: Boolean = false,
    val outSync: Int = 0,
    val outUsage: Int = 0,
    val feedbackUsage: Int = 0,
    val serviceIntervalsPerSecond: Int = 0,
    val nominalBytesPerInterval: Int = 0,
    val nominalBytesPerTransfer: Int = 0,
    val maxPacketBytes: Int = 0,
    val capacityRatioPermille: Int = 0,
    val profileRiskFlags: Int = 0
)

data class UsbDeviceAudioCapabilities(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val formats: List<UsbPcmFormatCapability>
) {
    val hasAnyNativeDsdDescriptor: Boolean
        get() = nativeDsdFormats.any {
            it.channels >= 2 && it.subslotBytes >= 4
        }

    val pcmFormats: List<UsbPcmFormatCapability>
        get() = formats.filter { it.isPcm && !it.isRawData }

    val nativeDsdFormats: List<UsbPcmFormatCapability>
        get() = formats.filter { it.isRawData }

    val supportedSampleRates: List<Int>
        get() = pcmFormats
            .map { it.sampleRate }
            .filter { it > 0 }
            .distinct()
            .sorted()

    val supportedBitDepths: List<Int>
        get() = pcmFormats
            .map { it.validBits }
            .filter { it > 0 }
            .distinct()
            .sorted()

    val supportedPcmModes: List<UsbPcmOutputMode>
        get() {
            val modes = mutableSetOf<UsbPcmOutputMode>()
            for (f in pcmFormats) {
                when {
                    f.validBits == 16 && f.subslotBytes == 2 -> modes += UsbPcmOutputMode.PCM_16
                    f.validBits == 24 && f.subslotBytes == 3 -> modes += UsbPcmOutputMode.PCM_24_PACKED
                    f.validBits >= 24 && f.subslotBytes == 4 -> modes += UsbPcmOutputMode.PCM_24_IN_32
                    f.validBits == 32 && f.subslotBytes == 4 -> modes += UsbPcmOutputMode.PCM_32
                }
            }
            return buildList {
                add(UsbPcmOutputMode.AUTO)
                addAll(modes.sortedBy { it.id })
            }
        }

    private val knownRates: Set<Int>
        get() = formats.map { it.sampleRate }.filter { it > 0 }.toSet()

    private fun maxKnownPcmRate(): Int = supportedSampleRates.maxOrNull() ?: 0

    /**
     * DoP uses a normal 24-bit / 3-byte PCM alt-setting.  The device rate is
     * DSD bit rate / 16, and for PCM→DSD it depends on the input clock family
     * (44.1k or 48k).  Keep the old multiplier helper as a compatibility
     * wrapper, but make the real check device-rate aware.
     */
    fun supportsDopDeviceRate(deviceRate: Int): Boolean {
        if (deviceRate <= 0) return false
        return pcmFormats.any {
            it.channels >= 2 &&
                it.validBits >= 24 &&
                it.subslotBytes == 3 &&
                (it.sampleRate == deviceRate ||
                    it.sampleRate <= 0 ||
                    (deviceRate in supportedSampleRates))
        }
    }

    fun supportsDop(multiplier: Int): Boolean =
        supportsDopDeviceRate(dsdRateHzForMultiplier(multiplier) / 16)

    /**
     * Native DSD is descriptor-driven.  Many UAC2 DACs expose a RAW_DATA alt
     * but report its clock through the same Clock Source as PCM, or only after
     * the raw alt is selected.  Do not reject DSD256 simply because the current
     * capability snapshot has 352.8/384k PCM rates but no exact RAW sample-rate
     * entry.  Native init remains authoritative and will reject at runtime if
     * the descriptor/clock really cannot be used.
     */
    fun supportsNativeDsdDeviceRate(deviceRate: Int): Boolean {
        if (deviceRate <= 0) return false
        val raw = nativeDsdFormats.filter {
            it.channels >= 2 &&
                (it.validBits >= 24 || it.validBits == 1) &&
                it.subslotBytes >= 4
        }
        if (raw.isEmpty()) return false
        if (raw.any { it.sampleRate == deviceRate || it.sampleRate <= 0 }) return true
        // If RAW_DATA exists and the device clock table contains the desired
        // native container rate, treat it as supported.  This is the common
        // UAC2 RAW-data descriptor shape seen on small USB dongles.
        if (deviceRate in supportedSampleRates) return true
        // Last resort: a RAW_DATA alt with enough endpoint capacity and no
        // explicit RAW clock list is still a valid runtime attempt.
        return raw.any { it.maxPacketBytes <= 0 || it.capacityRatioPermille >= 1000 }
    }

    fun supportsNativeDsd(multiplier: Int): Boolean =
        supportsNativeDsdDeviceRate(dsdRateHzForMultiplier(multiplier) / 32)

    fun supportsAnyDsd(multiplier: Int): Boolean = supportsNativeDsd(multiplier) || supportsDop(multiplier)

    fun preferredDsdTransport(multiplier: Int, requested: UsbDsdTransport): UsbDsdTransport? {
        if (requested == UsbDsdTransport.PCM) return UsbDsdTransport.PCM
        val requestedSupported = when (requested) {
            UsbDsdTransport.DOP -> supportsDop(multiplier)
            UsbDsdTransport.NATIVE -> supportsNativeDsd(multiplier)
            UsbDsdTransport.PCM -> true
        }
        if (requestedSupported) return requested
        if (supportsNativeDsd(multiplier)) return UsbDsdTransport.NATIVE
        if (supportsDop(multiplier)) return UsbDsdTransport.DOP
        return null
    }

    val supportedDsdRates: List<Int>
        get() = listOf(64, 128, 256, 512).filter { supportsAnyDsd(it) }
}

fun parseUsbCapabilitiesJson(json: String): UsbDeviceAudioCapabilities? {
    if (json.isBlank()) return null
    return try {
        val root = org.json.JSONObject(json)
        val arr = root.getJSONArray("formats")
        val formats = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    UsbPcmFormatCapability(
                        sampleRate = o.optInt("sampleRate", 0),
                        channels = o.optInt("channels", 0),
                        validBits = o.optInt("validBits", 0),
                        subslotBytes = o.optInt("subslotBytes", 0),
                        interfaceNumber = o.optInt("iface", -1),
                        altSetting = o.optInt("alt", 0),
                        outEndpoint = o.optInt("outEp", 0),
                        feedbackEndpoint = o.optInt("fbEp", 0),
                        isPcm = o.optBoolean("pcm", true),
                        isRawData = o.optBoolean("rawData", false),
                        outSync = o.optInt("outSync", 0),
                        outUsage = o.optInt("outUsage", 0),
                        feedbackUsage = o.optInt("fbUsage", 0),
                        serviceIntervalsPerSecond = o.optInt("serviceIntervalsPerSecond", 0),
                        nominalBytesPerInterval = o.optInt("nominalBytesPerInterval", 0),
                        nominalBytesPerTransfer = o.optInt("nominalBytesPerTransfer", 0),
                        maxPacketBytes = o.optInt("maxPacketBytes", 0),
                        capacityRatioPermille = o.optInt("capacityRatioPermille", 0),
                        profileRiskFlags = o.optInt("profileRiskFlags", 0)
                    )
                )
            }
        }
        UsbDeviceAudioCapabilities(
            deviceName = root.optString("deviceName", "USB DAC"),
            vendorId = root.optInt("vendorId", 0),
            productId = root.optInt("productId", 0),
            formats = formats
        )
    } catch (e: Exception) {
        null
    }
}
