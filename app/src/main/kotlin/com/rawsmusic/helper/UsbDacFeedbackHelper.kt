package com.rawsmusic.helper

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsbDacFeedbackHelper(
    private val context: Context
) {
    companion object {
        const val FEEDBACK_EMAIL = "3091734331@qq.com"
        private const val REPORT_DIR = "feedback"
    }

    suspend fun launchPlaybackFeedbackEmail(controller: PlayerController?): Boolean {
        val reportFile = withContext(Dispatchers.IO) {
            createPlaybackReportFile(controller)
        } ?: return withContext(Dispatchers.Main) {
            Toast.makeText(context, "暂无可导出的播放日志", Toast.LENGTH_SHORT).show()
            false
        }

        return withContext(Dispatchers.Main) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    reportFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, buildMailSubject(controller))
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "已附带 RawSMusic USB DAC 播放日志。\n\n请在正文补充：\n1. 复现步骤\n2. 手机机型 / Android 版本\n3. DAC 型号\n4. 是否 USB 独占 / Bit-Perfect / 硬件音量\n"
                    )
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri(reportFile.name, uri)
                }
                context.startActivity(Intent.createChooser(intent, "通过邮箱反馈 USB DAC 日志"))
                true
            } catch (t: Throwable) {
                Toast.makeText(context, "未找到可用的邮箱应用", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun createPlaybackReportFile(controller: PlayerController?): File? {
        val sessionLog = AppLogger.getPlaybackReportContent()?.trim().orEmpty()
        val fullLog = AppLogger.getLogContent()?.trim().orEmpty()
        val effectiveLog = sessionLog.ifBlank { fullLog }
        if (effectiveLog.isBlank()) return null

        val dir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
        val fileName = "RawSMusic_USB_DAC_Report_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }.log"
        val outFile = File(dir, fileName)
        outFile.writeText(buildReportContent(controller, effectiveLog), Charsets.UTF_8)
        return outFile
    }

    private fun buildReportContent(
        controller: PlayerController?,
        logContent: String
    ): String {
        val song = controller?.currentSong?.value
        val usbStatus = controller?.getUsbDeviceStatus()
        val ffmpegDebugLog = FFmpegBridge.getRecentDebugLog()?.trim().orEmpty()
        val positionMs = controller?.position?.value ?: 0L
        val durationMs = controller?.duration?.value ?: 0L
        val playState = controller?.playState?.value?.name ?: "UNKNOWN"
        val isUsbExclusive = controller?.usbExclusiveActive?.value == true

        return buildString {
            appendLine("RawSMusic USB DAC Feedback Report")
            appendLine("ExportedAt=${formatDateTime(Date())}")
            appendLine("AppVersion=${getAppVersionName()}(${getAppVersionCode()})")
            appendLine("Device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("UsbExclusive=$isUsbExclusive")
            appendLine("PlayState=$playState")
            appendLine("Position=${formatDuration(positionMs)} / ${formatDuration(durationMs)}")
            appendLine("CurrentSongTitle=${song?.title.orDash()}")
            appendLine("CurrentSongArtist=${song?.artist.orDash()}")
            appendLine("CurrentSongAlbum=${song?.album.orDash()}")
            appendLine("CurrentSongPath=${song?.path.orDash()}")
            appendLine("CueOffsetMs=${song?.cueOffsetMs ?: 0L}")
            if (usbStatus != null) {
                appendLine()
                appendLine("=== USB DAC STATUS ===")
                appendLine("DeviceName=${usbStatus.deviceName}")
                appendLine("VendorProductId=${usbStatus.vendorProductId}")
                appendLine("ManagerState=${usbStatus.managerState}")
                appendLine("Connected=${usbStatus.connected}")
                appendLine("PermissionGranted=${usbStatus.permissionGranted}")
                appendLine("ExclusiveActive=${usbStatus.exclusiveActive}")
                appendLine("Initialized=${usbStatus.initialized}")
                appendLine("Running=${usbStatus.running}")
                appendLine("BitPerfect=${usbStatus.bitPerfect}")
                appendLine("PlaybackMode=${usbStatus.playbackMode}")
                appendLine("SourceFormat=${usbStatus.sourceFormat}")
                appendLine("TargetFormat=${usbStatus.targetFormat}")
                appendLine("ActualOutputFormat=${usbStatus.actualOutputFormat}")
                appendLine("OutputChain=${usbStatus.outputChain}")
                appendLine("InterfaceInfo=${usbStatus.interfaceInfo}")
                appendLine("EndpointInfo=${usbStatus.endpointInfo}")
                appendLine("BufferInfo=${usbStatus.bufferInfo}")
                appendLine("HardwareVolumeInfo=${usbStatus.hardwareVolumeInfo}")
                appendLine("DsdInfo=${usbStatus.dsdInfo}")
                appendLine("TransportDiagnostics=${usbStatus.transportDiagnostics}")
                appendLine("AudibleDiagnostics=${usbStatus.audibleDiagnostics}")
                appendLine("FeedbackDiagnostics=${usbStatus.feedbackDiagnostics}")
                appendLine("ClockDiagnostics=${usbStatus.clockDiagnostics}")
                appendLine("FeatureUnitDiagnostics=${usbStatus.featureUnitDiagnostics}")
                appendLine("ProfileDiagnostics=${usbStatus.profileDiagnostics}")
                appendLine("RecoveryDiagnostics=${usbStatus.recoveryDiagnostics}")
                appendLine("Uac20PreviewDiagnostics=${usbStatus.uac20PreviewDiagnostics}")
                appendLine("Uac20GrayDecisionDiagnostics=${usbStatus.uac20GrayDecisionDiagnostics}")
                if (usbStatus.nativeStatsRaw.isNotBlank()) {
                    appendLine("NativeStatsRaw=${usbStatus.nativeStatsRaw}")
                }
            }
            appendLine()
            appendLine("=== FFMPEG DEBUG LOG ===")
            appendLine(if (ffmpegDebugLog.isBlank()) "<empty>" else ffmpegDebugLog)
            appendLine()
            appendLine("=== PLAYBACK SESSION LOG ===")
            appendLine(logContent)
        }
    }

    private fun buildMailSubject(controller: PlayerController?): String {
        val song = controller?.currentSong?.value
        val title = song?.title?.takeIf { it.isNotBlank() } ?: "未知曲目"
        return "RawSMusic USB DAC 日志反馈 - $title"
    }

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun getAppVersionCode(): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatDateTime(date: Date): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
    }

    private fun String?.orDash(): String = if (this.isNullOrBlank()) "-" else this
}
