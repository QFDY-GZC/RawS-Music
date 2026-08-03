package com.rawsmusic.module.player.usb

/**
 * Pure parser for the native USB runtime key=value diagnostic streams.
 *
 * Native values are whitespace separated, while some historical reason strings may contain
 * spaces. Only recognized key=value tokens are consumed so a verbose value cannot swallow the
 * pacing, clock, or feature-unit fields that follow it.
 */
object UsbRuntimeStatsParser {
    fun parseParts(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return Regex("""(?:^|\s)([A-Za-z][A-Za-z0-9_]*)=([^\s]+)""")
            .findAll(raw)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    fun parseBoolean(value: String?): Boolean? {
        if (value == null) return null
        return when (value.lowercase()) {
            "1", "true", "yes", "y" -> true
            "0", "false", "no", "n" -> false
            else -> null
        }
    }

    fun isAudibleAccepted(raw: String): Boolean =
        raw.isNotBlank() && parseBoolean(parseParts(raw)["audible"]) == true

    fun buildAudibleDiagnostics(raw: String): String {
        if (raw.isBlank()) return "native audible state unavailable"
        val parts = parseParts(raw)
        return "audible=${parts["audible"] ?: "unknown"}, " +
            "completed=${parts["completed"] ?: "0"}/${parts["expected"] ?: "0"}, " +
            "volumeReady=${parts["volumeReady"] ?: parts["audibleVolReady"] ?: "unknown"}, " +
            "hwSafeActive=${parts["hwSafeActive"] ?: "unknown"}, " +
            "firstCompletionMs=${parts["firstCompletionMs"] ?: parts["audibleFirstMs"] ?: "0"}, " +
            "acceptedMs=${parts["acceptedMs"] ?: parts["audibleAcceptedMs"] ?: "0"}, " +
            "ageMs=${parts["ageMs"] ?: "0"}, session=${parts["session"] ?: "0"}"
    }

    fun parseStats(raw: String): UsbStatsSnapshot? {
        return try {
            val parts = parseParts(raw)
            val clockVerifiedRate = parts["clockVerified"]?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            UsbStatsSnapshot(
                appInBytesPerSec = parts["app"]?.toLongOrNull() ?: 0,
                usbOutBytesPerSec = parts["completed"]?.toLongOrNull()
                    ?: parts["usbCompleted"]?.toLongOrNull()
                    ?: parts["usbOut"]?.toLongOrNull()
                    ?: parts["usb"]?.toLongOrNull()
                    ?: 0,
                scheduledUsbBytesPerSec = parts["scheduled"]?.toLongOrNull() ?: 0,
                expectedBytesPerSec = parts["expected"]?.toLongOrNull() ?: 0,
                bufferUsedBytes = parts["buf"]?.substringBefore('/')?.toLongOrNull() ?: 0,
                bufferCapacityBytes = parts["buf"]?.substringAfter('/', "")?.toLongOrNull() ?: 0,
                underrun = parts["underrun"]?.toIntOrNull() ?: 0,
                submitErr = parts["submitErr"]?.toIntOrNull() ?: 0,
                packetErr = parts["pktErr"]?.toIntOrNull() ?: 0,
                xferErr = parts["xferErr"]?.toIntOrNull() ?: 0,
                clockRate = clockVerifiedRate.takeIf { it > 0 }
                    ?: (parts["clock"]?.toIntOrNull() ?: 0),
                targetRate = parts["target"]?.toIntOrNull() ?: 0,
                finalVolume = parts["volume"]?.toFloatOrNull() ?: 0f,
                feedbackEnabled = (parts["feedback"]?.toIntOrNull() ?: 0) != 0,
                sessionId = parts["session"]?.toLongOrNull() ?: 0L,
                feedbackState = parts["fbState"]?.toIntOrNull() ?: 0,
                feedbackValidCount = parts["fbValid"]?.toIntOrNull() ?: 0,
                feedbackInvalidCount = parts["fbInvalid"]?.toIntOrNull() ?: 0,
                feedbackEmptyCount = parts["fbEmpty"]?.toIntOrNull() ?: 0,
                feedbackSampleRateMilli = parts["fbRateMilli"]?.toIntOrNull() ?: 0,
                pacingMode = parts["pacingMode"]
                    ?: when (parts["pacingModeId"]?.toIntOrNull()) {
                        0 -> "NoFeedbackFixed"
                        1 -> "ExplicitFeedback"
                        2 -> "FeedbackDegradedFixed"
                        else -> ""
                    },
                pacingModeId = parts["pacingModeId"]?.toIntOrNull() ?: -1,
                clockSource = parts["clockSrc"] ?: "",
                clockSelector = parts["clockSel"] ?: "",
                clockInterface = parts["clockIface"]?.toIntOrNull() ?: -1,
                clockVerified = parseBoolean(parts["clockVerified"]) ?: (clockVerifiedRate > 0),
                clockVerifiedRate = clockVerifiedRate,
                clockValidKnown = parseBoolean(parts["clockValidKnown"]) == true,
                clockValid = parseBoolean(parts["clockValid"]) == true,
                featureUnitPolicy = parts["fuPolicy"] ?: "",
                featureUnitPath = parts["fuPath"] ?: "",
                featureUnitResult = parts["fuResult"]?.toIntOrNull() ?: 0,
                featureUnitRangeVerified = parseBoolean(parts["fuRange"]) == true,
                featureUnitReadbackVerified = parseBoolean(parts["fuReadback"]) == true,
                featureUnitReason = parts["fuReason"] ?: "",
                featureUnitDescriptorMaster = parseBoolean(parts["fuDescM"]) == true,
                featureUnitDescriptorLeft = parseBoolean(parts["fuDescL"]) == true,
                featureUnitDescriptorRight = parseBoolean(parts["fuDescR"]) == true,
                featureUnitEffectiveMaster = parseBoolean(parts["fuEffM"]) == true,
                featureUnitEffectiveLeft = parseBoolean(parts["fuEffL"]) == true,
                featureUnitEffectiveRight = parseBoolean(parts["fuEffR"]) == true,
                featureUnitSingleChannel = parts["fuSingleCh"]?.toIntOrNull() ?: 0,
                raw = raw,
            )
        } catch (_: Exception) {
            null
        }
    }
}
