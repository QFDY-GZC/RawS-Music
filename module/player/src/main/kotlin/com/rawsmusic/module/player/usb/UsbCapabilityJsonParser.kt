package com.rawsmusic.module.player.usb

/** Converts the native capability snapshot into the Kotlin capability model. */
internal object UsbCapabilityJsonParser {
    fun parse(json: String): UsbDeviceAudioCapabilities? {
        if (json.isBlank()) return null
        return try {
            val root = org.json.JSONObject(json)
            val arr = root.getJSONArray("formats")
            val formats = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val protocol = o.optInt("protocol", 2)
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
                            profileRiskFlags = o.optInt("profileRiskFlags", 0),
                            protocol = protocol,
                            uac1SamplingFrequencyControl = o.optBoolean("uac1SamplingFreqControl", false),
                            exactRateProvable = if (o.has("exactRateProvable")) {
                                o.optBoolean("exactRateProvable", false)
                            } else {
                                protocol != 1
                            },
                        ),
                    )
                }
            }
            UsbDeviceAudioCapabilities(
                deviceName = root.optString("deviceName", "USB DAC"),
                vendorId = root.optInt("vendorId", 0),
                productId = root.optInt("productId", 0),
                formats = formats,
            )
        } catch (_: Exception) {
            null
        }
    }
}

fun parseUsbCapabilitiesJson(json: String): UsbDeviceAudioCapabilities? =
    UsbCapabilityJsonParser.parse(json)
