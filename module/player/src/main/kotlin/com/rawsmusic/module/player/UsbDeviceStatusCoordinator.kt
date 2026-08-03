package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbDsdModeConfig
import com.rawsmusic.module.player.usb.UsbExclusiveManager
import com.rawsmusic.module.player.usb.UsbLearnedPolicyStore
import com.rawsmusic.module.player.usb.UsbStatsSnapshot
import com.rawsmusic.module.player.usb.UsbRuntimeStatsParser
import com.rawsmusic.module.player.usb.UsbDeviceStatusTextFormatter
import com.rawsmusic.module.player.usb.dsdRateHzForMultiplier

/**
 * Builds the diagnostic snapshot shown by the USB DAC settings screen.
 *
 * The controller owns playback state and USB lifecycle; this class only assembles a read-only
 * status model so diagnostic formatting cannot expand the transport controller further.
 */
internal class UsbDeviceStatusCoordinator(
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val engine: UsbAudioEngine,
        val manager: UsbExclusiveManager,
        val player: FfmpegAudioPlayer,
        val currentSong: () -> AudioFile?,
        val sourceIsDsd: () -> Boolean,
        val sourceDsdRate: () -> Int,
        val effectiveDsdMode: () -> UsbDsdModeConfig?,
        val effectiveDsdRate: () -> Int,
        val devicePolicyKey: () -> String?,
        val exclusiveActive: () -> Boolean,
        val playbackModeName: () -> String,
        val recoveryDiagnostics: () -> String,
    )

    private fun describeDescriptorVolumeHint(volume: UsbExclusiveManager.VolumeInfo?): String {
        return when {
            volume == null -> "DescriptorHint: 未发现 Feature Unit 或尚未扫描"
            volume.hasMasterVolume -> "DescriptorHint: Feature Unit ${volume.entityId}, Master=true"
            volume.hasLeftVolume || volume.hasRightVolume ->
                "DescriptorHint: Feature Unit ${volume.entityId}, L=${volume.hasLeftVolume} R=${volume.hasRightVolume} (仅描述符快速扫描)"
            else -> "DescriptorHint: Feature Unit ${volume.entityId}, 无音量控制"
        }
    }

    private fun describeNativeVolumeController(stats: UsbStatsSnapshot?, nativePolicy: String): String {
        val parts = UsbRuntimeStatsParser.parseParts(nativePolicy)
        val state = stats?.featureUnitPolicy?.takeIf { it.isNotBlank() } ?: parts["state"] ?: "unknown"
        val path = stats?.featureUnitPath?.takeIf { it.isNotBlank() } ?: parts["path"] ?: "none"
        val reason = stats?.featureUnitReason?.takeIf { it.isNotBlank() } ?: parts["reason"] ?: "unknown"
        val range = stats?.featureUnitRangeVerified ?: (UsbRuntimeStatsParser.parseBoolean(parts["range"]) == true)
        val readback = stats?.featureUnitReadbackVerified ?: (UsbRuntimeStatsParser.parseBoolean(parts["readback"]) == true)
        val enabled = UsbRuntimeStatsParser.parseBoolean(parts["enabled"])
        val nativeDescM = stats?.featureUnitDescriptorMaster ?: (UsbRuntimeStatsParser.parseBoolean(parts["descM"]) == true)
        val nativeDescL = stats?.featureUnitDescriptorLeft ?: (UsbRuntimeStatsParser.parseBoolean(parts["descL"]) == true)
        val nativeDescR = stats?.featureUnitDescriptorRight ?: (UsbRuntimeStatsParser.parseBoolean(parts["descR"]) == true)
        val effM = stats?.featureUnitEffectiveMaster ?: (UsbRuntimeStatsParser.parseBoolean(parts["effM"]) == true)
        val effL = stats?.featureUnitEffectiveLeft ?: (UsbRuntimeStatsParser.parseBoolean(parts["effL"]) == true)
        val effR = stats?.featureUnitEffectiveRight ?: (UsbRuntimeStatsParser.parseBoolean(parts["effR"]) == true)
        val singleCh = stats?.featureUnitSingleChannel?.takeIf { it > 0 } ?: parts["singleCh"]?.toIntOrNull() ?: 0
        val enabledText = enabled?.let { ", enabled=$it" } ?: ""
        val effective = when {
            effM -> "Master"
            effL && effR -> "L/R linked"
            effL || effR -> "single ch=$singleCh"
            else -> "none"
        }
        return "NativeVolumeController: state=$state, path=$path, effective=$effective, " +
            "nativeDesc(M=$nativeDescM L=$nativeDescL R=$nativeDescR), " +
            "verified(range=$range readback=$readback)$enabledText, reason=$reason"
    }

    fun build(): PlayerController.UsbDeviceStatus {
        callbacks.engine.refreshRuntimeSnapshotFromNative()
        val runtime = callbacks.engine.getRuntimeFormat()
        val cfg = callbacks.manager.getCurrentConfig()
        val deviceName = callbacks.manager.getCurrentDeviceName() ?: "未检测到 USB DAC"
        val vid = callbacks.manager.getCurrentDeviceVendorId()
        val pid = callbacks.manager.getCurrentDeviceProductId()
        val vidPid = UsbDeviceStatusTextFormatter.formatVendorProductId(vid, pid)
        val initialized = callbacks.engine.isInitialized()
        val running = callbacks.engine.isRunning()
        val sourceSr = callbacks.manager.getCurrentSourceSampleRate()
        val sourceBits = callbacks.manager.getCurrentSourceBits()
        val currentSong = callbacks.currentSong()
        val songBits = currentSong?.bitsPerSample?.takeIf { it > 0 } ?: 0
        val sourceIsDsd = callbacks.sourceIsDsd() || sourceBits == 1
        val displaySourceSr = currentSong?.sampleRate?.takeIf { it > 0 } ?: sourceSr
        val displaySourceBits = when {
            sourceIsDsd -> 1
            songBits > 0 -> songBits
            else -> sourceBits
        }
        val effectiveDsdMode = callbacks.effectiveDsdMode()
        val effectiveDsdRate = callbacks.effectiveDsdRate()
        val dsdMode = effectiveDsdMode
        val dsdBitRateHz = dsdMode?.let { dsdRateHzForMultiplier(it.multiplier) } ?: if (sourceIsDsd) callbacks.sourceDsdRate() else 0
        val actualChannels = runtime.channels.takeIf { it > 0 } ?: callbacks.engine.currentChannels.takeIf { it > 0 } ?: cfg?.channels ?: 2
        val actualBits = runtime.validBits.takeIf { it > 0 } ?: callbacks.engine.currentBits.takeIf { it > 0 } ?: cfg?.bits ?: 0
        val sourceFormat = if (displaySourceSr > 0 && displaySourceBits > 0) {
            if (sourceIsDsd) {
                UsbDeviceStatusTextFormatter.buildDsdFormatText(
                    multiplier = effectiveDsdRate,
                    rateHz = dsdBitRateHz,
                    channels = actualChannels
                )
            } else {
                "${displaySourceSr}Hz / ${displaySourceBits}bit / ${actualChannels}ch"
            }
        } else {
            "等待播放初始化"
        }
        val targetFormat = if (dsdMode != null) {
            UsbDeviceStatusTextFormatter.buildDsdFormatText(
                multiplier = dsdMode.multiplier,
                rateHz = dsdBitRateHz,
                channels = actualChannels
            )
        } else if (cfg != null) {
            "${cfg.sampleRate}Hz / ${cfg.bits}bit / ${cfg.channels}ch / subslot ${cfg.subslot * 8}bit"
        } else {
            val sr = AppPreferences.Player.usbTargetSampleRate
            val bits = AppPreferences.Player.usbTargetBitDepth
            "偏好: ${AudioOutputManager.SAMPLE_RATE_LABELS[sr] ?: "自动"} / ${AudioOutputManager.BIT_DEPTH_LABELS[bits] ?: "自动"}"
        }
        val actualSr = runtime.sampleRate.takeIf { it > 0 }
            ?: callbacks.engine.getOutputSampleRate().takeIf { it > 0 }
            ?: callbacks.player.usbActualOutputSampleRate.takeIf { it > 0 }
            ?: callbacks.engine.currentSampleRate
        val subslot = runtime.subslotBytes.takeIf { it > 0 } ?: callbacks.engine.currentSubslotSize.takeIf { it > 0 } ?: cfg?.subslot ?: 0
        val frameBytes = runtime.frameBytes.takeIf { it > 0 } ?: actualChannels * subslot
        val actualOutput = if (actualSr > 0 && actualChannels > 0 && actualBits > 0) {
            if (dsdMode != null) {
                UsbDeviceStatusTextFormatter.buildDsdFormatText(
                    multiplier = dsdMode.multiplier,
                    rateHz = dsdBitRateHz,
                    channels = actualChannels
                )
            } else {
                buildString {
                    append("${actualSr}Hz / ${actualBits}bit / ${actualChannels}ch")
                    if (subslot > 0) {
                        append(" / subslot ${subslot * 8}bit")
                        append(" / frame ${frameBytes}B")
                    }
                }
            }
        } else {
            "未开始输出"
        }
        val bitPerfect = AppPreferences.Player.bitPerfectEnabled
        val dsdInfo = UsbDeviceStatusTextFormatter.buildDsdInfoText(
            sourceIsDsd = sourceIsDsd,
            dsdMode = dsdMode,
            dsdRateHz = dsdBitRateHz
        )
        val needsPcmAdapter = cfg != null &&
            (
                displaySourceSr > 0 && displaySourceSr != cfg.sampleRate ||
                    displaySourceBits > 0 && displaySourceBits != cfg.bits
                )
        val chain = UsbDeviceStatusTextFormatter.buildOutputChainText(
            sourceIsDsd = sourceIsDsd,
            dsdMode = dsdMode,
            bitPerfect = bitPerfect,
            needsPcmAdapter = needsPcmAdapter
        )
        val nativeIface = runtime.iface.takeIf { it >= 0 } ?: callbacks.engine.currentInterfaceNumber
        val nativeAlt = runtime.alt.takeIf { it > 0 } ?: callbacks.engine.currentAltSetting
        val nativeOutEp = runtime.outEndpoint.takeIf { it > 0 } ?: callbacks.engine.currentOutEndpoint
        val nativeFbEp = runtime.feedbackEndpoint.takeIf { it > 0 } ?: callbacks.engine.currentFeedbackEndpoint
        val iface = nativeIface.takeIf { it >= 0 } ?: cfg?.iface ?: -1
        val alt = nativeAlt.takeIf { it > 0 } ?: cfg?.alt ?: 0
        val outEp = nativeOutEp.takeIf { it > 0 } ?: cfg?.outEp ?: 0
        val fbEp = nativeFbEp.takeIf { it > 0 } ?: cfg?.fbEp ?: 0
        val interfaceText = if (iface >= 0 && alt > 0) "Interface $iface / Alt $alt" else "等待 USB 流初始化"
        val endpointText = if (outEp > 0) {
            "OUT 0x${outEp.toString(16).uppercase().padStart(2, '0')}" +
                if (fbEp > 0) " / Feedback 0x${fbEp.toString(16).uppercase().padStart(2, '0')}" else " / Feedback 无"
        } else {
            "等待端点初始化"
        }
        val bufferUsed = callbacks.engine.getBufferUsedBytes()
        val runtimeBps = if (actualSr > 0 && actualChannels > 0 && subslot > 0) {
            (actualSr.toLong() * actualChannels.toLong() * subslot.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else 0
        val outputBps = runtimeBps.takeIf { it > 0 } ?: callbacks.engine.getOutputBytesPerSecond()
        val bufferMs = if (outputBps > 0) bufferUsed.toLong() * 1000L / outputBps else 0L
        val nativeStatsRaw = runCatching {
            val h = callbacks.engine.currentHandle
            if (h != 0L) callbacks.engine.nativeGetStatsString(h) else ""
        }.getOrDefault("")
        val audibleStateRaw = runCatching {
            val h = callbacks.engine.currentHandle
            if (h != 0L) callbacks.engine.nativeGetAudibleStateString(h) else ""
        }.getOrDefault("")
        val stats = UsbRuntimeStatsParser.parseStats(nativeStatsRaw)
        val nativeFuPolicy = runCatching { callbacks.engine.getHardwareVolumePolicyString() }.getOrDefault("")
        val volume = callbacks.manager.volumeInfo.value
        val descriptorVolumeHint = describeDescriptorVolumeHint(volume)
        val nativeVolumeController = if (nativeFuPolicy.isNotBlank() && nativeFuPolicy != "no-handle") {
            describeNativeVolumeController(stats, nativeFuPolicy)
        } else {
            "NativeVolumeController: unavailable"
        }
        val hardwareVolume = "$descriptorVolumeHint / $nativeVolumeController"
        val outputRatio = if (stats != null && stats.expectedBytesPerSec > 0L) {
            stats.usbOutBytesPerSec.toDouble() / stats.expectedBytesPerSec.toDouble()
        } else 0.0
        val transportDiagnostics = if (stats != null) {
            "appIn=${stats.appInBytesPerSec} B/s, completedOut=${stats.usbOutBytesPerSec} B/s, " +
                "expected=${stats.expectedBytesPerSec} B/s, ratio=${"%.3f".format(outputRatio)}, " +
                "errors underrun=${stats.underrun} submit=${stats.submitErr} packet=${stats.packetErr} xfer=${stats.xferErr}"
        } else {
            "native stats unavailable"
        }
        val audibleDiagnostics = UsbRuntimeStatsParser.buildAudibleDiagnostics(audibleStateRaw)
        val feedbackDiagnostics = if (stats != null) {
            buildString {
                append("enabled=${stats.feedbackEnabled}")
                append(", state=${stats.feedbackStateName}")
                if (stats.pacingModeName.isNotBlank()) append(", pacing=${stats.pacingModeName}")
                if (stats.feedbackValidCount > 0) append(", valid=${stats.feedbackValidCount}")
                if (stats.feedbackSampleRateMilli > 0) append(", rate=${stats.feedbackSampleRateMilli / 1000.0}Hz")
                if (stats.feedbackInvalidCount > 0 || stats.feedbackEmptyCount > 0) {
                    append(", invalid=${stats.feedbackInvalidCount}, empty=${stats.feedbackEmptyCount}")
                }
            }
        } else {
            if (fbEp > 0) "endpoint present, runtime stats unavailable" else "none"
        }
        val clockDiagnostics = if (stats != null) {
            "src=${stats.clockSource.ifBlank { "n/a" }}, sel=${stats.clockSelector.ifBlank { "n/a" }}, " +
                "iface=${stats.clockInterface}, verified=${stats.clockVerifiedRate.takeIf { it > 0 } ?: stats.clockVerified ?: "unknown"}, " +
                "validKnown=${stats.clockValidKnown}, valid=${stats.clockValid}, " +
                "deviceRate=${stats.clockRate}, targetRate=${stats.targetRate}"
        } else {
            "native stats unavailable"
        }
        val featureUnitDiagnostics = if (stats != null) {
            buildString {
                append(descriptorVolumeHint)
                append(", ")
                append(nativeVolumeController)
                if (stats.featureUnitPolicy.isNotBlank()) append(", policy=${stats.featureUnitPolicy}")
                if (stats.featureUnitPath.isNotBlank()) append(", path=${stats.featureUnitPath}")
                append(", result=${stats.featureUnitResult}")
                append(", range=${stats.featureUnitRangeVerified}, readback=${stats.featureUnitReadbackVerified}")
                if (stats.featureUnitReason.isNotBlank()) append(", reason=${stats.featureUnitReason}")
                if (nativeFuPolicy.isNotBlank()) append(", native=$nativeFuPolicy")
            }
        } else {
            buildString {
                append(descriptorVolumeHint)
                append(", ")
                append(nativeVolumeController)
                if (nativeFuPolicy.isNotBlank()) append(" / native=$nativeFuPolicy")
            }
        }
        val deviceKey = callbacks.devicePolicyKey()
        val learnedPolicy = deviceKey?.let { runCatching { UsbLearnedPolicyStore.read(it) }.getOrNull() }
        val learnedRunawayUnproven = UsbLearnedPolicyStore.isRunawayUnprovenFallback(learnedPolicy)
        val profileDiagnostics = buildString {
            append("runtime iface=$iface alt=$alt sr=$actualSr bits=$actualBits subslot=$subslot out=0x${outEp.toString(16)} fb=0x${fbEp.toString(16)}")
            if (learnedPolicy != null) {
                append(", learned lastGoodAlt=${learnedPolicy.lastGoodAlt}")
                append(" lastGoodSr=${learnedPolicy.lastGoodSampleRate}")
                append(" lastGoodBits=${learnedPolicy.lastGoodBitDepth}")
                append(" lastGoodSubslot=${learnedPolicy.lastGoodSubslot}")
                append(" lastGoodFb=0x${learnedPolicy.lastGoodFeedbackEndpoint.toString(16)}")
                append(" flags noFb=${learnedPolicy.noFeedback} noClock=${learnedPolicy.noClockSet} noFU=${learnedPolicy.noFeatureUnit} force1ms=${learnedPolicy.force1msPacket} safeAlt=${learnedPolicy.preferSafeAlt}")
                append(" success=${learnedPolicy.successCount} failure=${learnedPolicy.failureCount}")
                if (learnedRunawayUnproven) append(" ignoredRunawayHints=true")
            } else {
                append(", learned=none")
            }
        }
        val recoveryDiagnostics = callbacks.recoveryDiagnostics()
        return PlayerController.UsbDeviceStatus(
            deviceName = deviceName,
            vendorProductId = vidPid,
            managerState = callbacks.manager.getCurrentState().name,
            connected = callbacks.manager.isDeviceConnected(),
            permissionGranted = callbacks.manager.hasCurrentDevicePermission(),
            exclusiveActive = callbacks.exclusiveActive(),
            initialized = initialized,
            running = running,
            bitPerfect = bitPerfect,
            playbackMode = callbacks.playbackModeName(),
            sourceFormat = sourceFormat,
            targetFormat = targetFormat,
            actualOutputFormat = actualOutput,
            outputChain = chain,
            dsdActive = dsdMode != null,
            dsdSourceDirect = sourceIsDsd && dsdMode != null,
            interfaceInfo = interfaceText,
            endpointInfo = endpointText,
            bufferInfo = if (outputBps > 0) "$bufferUsed B / ${bufferMs} ms / ${outputBps} B/s" else "$bufferUsed B",
            hardwareVolumeInfo = hardwareVolume,
            dsdInfo = dsdInfo,
            transportDiagnostics = transportDiagnostics,
            audibleDiagnostics = audibleDiagnostics,
            feedbackDiagnostics = feedbackDiagnostics,
            clockDiagnostics = clockDiagnostics,
            featureUnitDiagnostics = featureUnitDiagnostics,
            profileDiagnostics = profileDiagnostics,
            recoveryDiagnostics = recoveryDiagnostics,
            nativeStatsRaw = nativeStatsRaw,
        )

    }
}
