package com.rawsmusic.helper

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.R
import com.rawsmusic.core.common.model.isDsdSourceFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.AudioOutputManager
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.dsp.PEQFilter
import java.io.File
import kotlin.math.roundToInt

/**
 * 音频信息胶囊助手 - 管理播放页顶部的音频信息胶囊显示
 * 包含：采样率/位深/码率显示、输出设备信息、Hi-Res徽章、音频详情弹窗
 */
class AudioInfoCapsuleHelper(
    private val context: Context,
    private val getPlayerController: () -> PlayerController?,
    private val onVisibilityChanged: (Boolean) -> Unit = {},
    private val onNavigateDestination: (Int) -> Unit = {}
) {
    /** 胶囊显示状态：0=音频参数, 1=输出设备, 2=输出参数, 3=队列位置, 4=当前歌词 */
    var capsuleState = 0
        private set

    private var pendingHiresShow = false
    private var cachedBluetoothCodec: String? = null
    private var bluetoothCodecFetching = false
    private var popupData by mutableStateOf<AudioInfoPopupData?>(null)

    var isPopupShowing by mutableStateOf(false)
        private set

    var capsuleText by mutableStateOf("")
        private set

    /** 当前歌词文本，由外部设置 */
    var currentLyricText: String = ""

    /** 设置胶囊点击和长按监听 */
    fun setup() {
        updateText()
    }

    fun showInfoPopup() {
        showPopup()
    }

    fun cycleCapsule() {
        capsuleState = (capsuleState + 1) % 5
        updateText()
    }

    fun dismissPopup() {
        if (!isPopupShowing) return
        isPopupShowing = false
        popupData = null
        onVisibilityChanged(false)
    }

    fun popupDataForCompose(): AudioInfoPopupData? = popupData

    fun navigateFromCompose(destinationId: Int) {
        onNavigateDestination(destinationId)
    }

    /** 更新Hi-Res徽章显示 */
    fun updateHiresBadge(isTransitioning: Boolean, isPlayerScene: Boolean) {
        val pc = getPlayerController() ?: return
        val song = pc.currentSong.value
        val isHires = song?.isHiRes == true
        val shouldShow = isPlayerScene && isHires
        pendingHiresShow = shouldShow

        if (isTransitioning) return

        // Hi-Res badge is rendered by the Compose player surface.
    }

    /** 刷新待显示的Hi-Res徽章（场景切换完成后调用） */
    fun flushPendingHires() {
        // Hi-Res badge is rendered by the Compose player surface.
    }

    /** 更新胶囊文本内容 */
    fun updateText() {
        val pc = getPlayerController()
        val song = pc?.currentSong?.value
        capsuleText = when (capsuleState) {
            1 -> getOutputDeviceInfo()
            2 -> outputFormatText(pc, song)
            3 -> queuePositionText(pc)
            4 -> currentLyricText.takeIf { it.isNotBlank() && !isMusicSymbolOnly(it) }
                ?: sourceFormatText(song)
            else -> sourceFormatText(song)
        }
    }

    private fun sourceFormatText(song: com.rawsmusic.core.common.model.AudioFile?): String {
        val bits = when {
            song?.isDsdSourceFile() == true -> "1 BIT"
            song?.bitsPerSample?.let { it > 0 } == true -> "${song.bitsPerSample} BIT"
            song != null && com.rawsmusic.module.scanner.AudioBitDepthResolver.isLossyDisplayFormat(song.format) -> "LOSSY"
            else -> null
        }
        val sampleRate = song?.sampleRate?.takeIf { it > 0 }?.let { formatSampleRate(it).uppercase() }
        val bitRate = song?.bitRate?.takeIf { it > 0 }?.let {
            com.rawsmusic.core.common.utils.BitrateNormalizer.formatKbps(it, song.duration, song.fileSize).uppercase()
        }
        val format = when {
            song?.isDsdSourceFile() == true -> "DSD"
            else -> song?.format?.takeIf { it.isNotBlank() }?.uppercase()
        }
        return listOfNotNull(bits, sampleRate, bitRate, format)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("  ")
            ?: "LOCAL AUDIO"
    }

    private fun outputFormatText(
        pc: PlayerController?,
        song: com.rawsmusic.core.common.model.AudioFile?
    ): String {
        if (pc?.isUsbExclusiveActive() == true) {
            val status = runCatching { pc.getUsbDeviceStatus() }.getOrNull()
            val dsd = status?.actualOutputFormat
                ?.takeIf { it.startsWith("DSD", ignoreCase = true) }
            if (!dsd.isNullOrBlank()) return dsd.uppercase()
        }
        val usbSr = pc?.getUsbOutputSampleRate() ?: 0
        val ffmpegPlayer = pc?.ffmpegPlayerRef
        val outputSr = if (usbSr > 0) usbSr else (ffmpegPlayer?.wavSampleRate ?: 0)
        val outputBits = ffmpegPlayer?.wavBitsPerSample ?: 0
        val bits = outputBits.takeIf { it > 0 }?.let { "$it BIT" }
        val sample = outputSr.takeIf { it > 0 }?.let { "${it / 1000} KHZ" }
        return listOfNotNull(bits, sample)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("  ")
            ?: sourceFormatText(song)
    }

    private fun queuePositionText(pc: PlayerController?): String {
        val queue = pc?.queue?.value
        return if (queue != null && queue.songs.isNotEmpty()) {
            "QUEUE  ${queue.currentIndex + 1}/${queue.songs.size}"
        } else {
            "QUEUE"
        }
    }

    fun isMusicSymbolOnly(text: String): Boolean {
        val content = text.trim()
        if (content.isEmpty()) return true
        return content.all { char ->
            char.isWhitespace() ||
                char in setOf('♪', '♫', '♬', '♩', '♭', '♯', '♮', '☆', '★', '·', '.', '。', '…') ||
                Character.UnicodeBlock.of(char) == Character.UnicodeBlock.MUSICAL_SYMBOLS
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun getOutputDeviceInfo(): String {
        val pc = getPlayerController()
        val isUsbExclusive = pc?.isUsbExclusiveActive() == true
        val usbDeviceName = pc?.getUsbDeviceName()

        if (isUsbExclusive && usbDeviceName != null) {
            return "USB DAC ($usbDeviceName)"
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val activeDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        } ?: devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val result = when (activeDevice?.type) {
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                val name = activeDevice.productName?.toString() ?: "Bluetooth"
                val codec = cachedBluetoothCodec ?: ""
                if (codec.isNotBlank()) "$name $codec" else name
            }
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> {
                val name = activeDevice.productName?.toString() ?: "USB DAC"
                "USB DAC ($name)"
            }
            else -> "内置扬声器"
        }
        if (activeDevice?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && cachedBluetoothCodec == null && !bluetoothCodecFetching) {
            fetchBluetoothCodecAsync()
        }
        return result
    }

    private fun getDeviceIconRes(): Int {
        val pc = getPlayerController()
        val isUsbExclusive = pc?.isUsbExclusiveActive() == true
        if (isUsbExclusive) return R.drawable.ic_usb

        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val btDevice = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (btDevice != null) return R.drawable.ic_bluetooth_connect

        val wiredDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (wiredDevice != null) return R.drawable.ic_headphone

        val usbHeadset = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET }
        if (usbHeadset != null) return R.drawable.ic_usb

        return R.drawable.ic_volume_up
    }

    @Suppress("MissingPermission")
    private fun fetchBluetoothCodecAsync() {
        bluetoothCodecFetching = true
        Thread {
            val codec = detectBluetoothCodecSync()
            cachedBluetoothCodec = codec
            bluetoothCodecFetching = false
            (context as? android.app.Activity)?.runOnUiThread { updateText() }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun detectBluetoothCodecSync(): String {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return ""
            val profileProxy = arrayOfNulls<android.bluetooth.BluetoothProfile>(1)
            val lock = java.util.concurrent.CountDownLatch(1)

            adapter.getProfileProxy(context, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    profileProxy[0] = proxy
                    lock.countDown()
                }
                override fun onServiceDisconnected(profile: Int) {
                    lock.countDown()
                }
            }, android.bluetooth.BluetoothProfile.A2DP)

            if (!lock.await(3, java.util.concurrent.TimeUnit.SECONDS)) return ""

            val a2dp = profileProxy[0] ?: return ""
            val connectedDevices = a2dp.connectedDevices
            if (connectedDevices.isNullOrEmpty()) {
                try { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, a2dp) } catch (_: Exception) {}
                return ""
            }

            val codecStatus = try {
                val method = a2dp.javaClass.getMethod("getCodecStatus", android.bluetooth.BluetoothDevice::class.java)
                method.invoke(a2dp, connectedDevices[0])
            } catch (_: Exception) { null }

            try { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, a2dp) } catch (_: Exception) {}

            if (codecStatus == null) return ""

            val codecConfig = try {
                val method = codecStatus.javaClass.getMethod("getCodecConfig")
                method.invoke(codecStatus)
            } catch (_: Exception) { null } ?: return ""

            val codecType = try {
                val method = codecConfig.javaClass.getMethod("getCodecType")
                method.invoke(codecConfig) as? Int ?: -1
            } catch (_: Exception) { -1 }

            when (codecType) {
                0 -> "SBC"
                1 -> "AAC"
                2 -> "aptX"
                3 -> "aptX HD"
                4 -> "LDAC"
                5 -> try {
                    val method = codecConfig.javaClass.getMethod("getCodecSpecific1")
                    val val1 = method.invoke(codecConfig) as? Long ?: 0L
                    if (val1 == 0L) "LHDC" else "LHDC V${val1 / 1000}"
                } catch (_: Exception) { "LHDC" }
                6 -> "LC3"
                7 -> "aptX Adaptive"
                8 -> "LHDC V5"
                1000 -> "LHDC"
                else -> "BT($codecType)"
            }
        } catch (_: Exception) { "" }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun showPopup() {
        val pc = getPlayerController() ?: return
        val song = pc.currentSong.value ?: return
        val queue = pc.queue.value
        val fmt = song.encodingFormat.ifBlank { song.format.ifBlank { song.extension } }.uppercase()
        val srcSr = if (song.sampleRate > 0) song.sampleRate else 0
        val srcBd = if (song.bitsPerSample > 0) song.bitsPerSample else 0
        val usbSr = pc.getUsbOutputSampleRate() ?: 0
        val ffmpegPlayer = pc.ffmpegPlayerRef
        val ffmpegOutputSr = if (usbSr > 0) usbSr else (ffmpegPlayer?.wavSampleRate ?: 0)
        val ffmpegOutputBd = ffmpegPlayer?.wavBitsPerSample ?: 0
        val isUsbExclusive = pc.isUsbExclusiveActive() == true
        val actualOutputMode = AudioOutputManager.getCurrentOutputMode(context)
        val outputApi = if (isUsbExclusive) "USB DAC 独占" else AudioOutputManager.getOutputModeLabel(actualOutputMode)
        val outputSettingsDest = if (isUsbExclusive) R.id.nav_usb_dac_settings else R.id.nav_audio_settings
        val usbStatus = if (isUsbExclusive) runCatching { pc.getUsbDeviceStatus() }.getOrNull() else null
        val srChanged = ffmpegOutputSr > 0 && srcSr > 0 && srcSr != ffmpegOutputSr
        val bdChanged = ffmpegOutputBd > 0 && srcBd > 0 && srcBd != ffmpegOutputBd
        val targetSr = if (isUsbExclusive) AppPreferences.Player.usbTargetSampleRate else AppPreferences.Player.targetSampleRate
        val targetBits = if (isUsbExclusive) AppPreferences.Player.usbTargetBitDepth else AppPreferences.Player.targetBitDepth
        val queuePos = if (queue != null && queue.songs.isNotEmpty()) "${queue.currentIndex + 1}/${queue.songs.size}" else "未知"
        val actualLatencyMs = ffmpegPlayer?.latencyMs?.toFloat() ?: 0f
        val actualBufFrames = ffmpegPlayer?.bufferSizeInFrames ?: 0
        val estimatedLatencyMs = if (actualLatencyMs > 0f) {
            actualLatencyMs
        } else {
            val bufFrames = try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
            } catch (_: Exception) { 256 }
            bufFrames * 2 * 1000f / (if (ffmpegOutputSr > 0) ffmpegOutputSr.toFloat() else 48000f)
        }

        val mediaLines = listOf(
            InfoLine("所有歌曲 - $queuePos", R.id.nav_songs, true),
            InfoLine(song.displayName.ifBlank { "当前音轨" }),
            InfoLine(song.artist.ifBlank { "未知艺术家" })
        )
        val trackLines = listOf(
            InfoLine("${fmt.ifBlank { "未知格式" }}  ${formatBitDepth(srcBd, fmt)}  ${formatSampleRate(srcSr)}  ${formatBitrate(song.bitRate)}"),
            InfoLine("${formatChannelCount(song.channelCount)}  /  ${formatDuration(song.duration)}"),
            InfoLine(if (AppPreferences.Player.gaplessPlaybackEnabled) "无间隙播放：开启" else "无间隙播放：关闭"),
            InfoLine(File(song.path).name.ifBlank { "未知文件" })
        )
        val decoderLines = listOf(
            InfoLine("FFmpeg Decoder"),
            InfoLine("编码格式：${song.encodingFormat.ifBlank { fmt.ifBlank { "未知" } }}"),
            InfoLine("源文件：${formatFileSize(song.fileSize)}")
        )
        val resampleLines = buildResampleLines(
            fmt = fmt,
            srcSr = srcSr,
            srcBd = srcBd,
            ffmpegOutputSr = ffmpegOutputSr,
            ffmpegOutputBd = ffmpegOutputBd,
            targetSr = targetSr,
            targetBits = targetBits,
            outputSettingsDest = outputSettingsDest,
            usbStatus = usbStatus
        )
        val signalLines = buildSignalProcessingLines(ffmpegOutputSr, ffmpegOutputBd)
        val outputLatencyDisplay = pc.getBluetoothLatencyInfo()?.takeIf { it.isNotBlank() }?.let {
            "${"%.0f".format(estimatedLatencyMs)} ms [$it]"
        } ?: "${"%.0f".format(estimatedLatencyMs)} ms / $actualBufFrames frames"
        val actualOutputText = usbStatus?.actualOutputFormat?.takeIf { it.isNotBlank() && it != "未开始输出" }
            ?: "${formatOutputBitDepth(ffmpegOutputBd, targetBits)} / ${formatSampleRate(ffmpegOutputSr)}"
        val outputLines = listOf(
            InfoLine(outputApi, outputSettingsDest, true),
            InfoLine("实际输出：$actualOutputText"),
            InfoLine("独占：${if (isUsbExclusive) "已启用" else "未启用"}"),
            InfoLine("完美比特：${bitPerfectStatus(isUsbExclusive, srChanged, bdChanged, usbStatus)}"),
            InfoLine("输出延迟：$outputLatencyDisplay")
        )
        val deviceInfo = resolveDeviceInfo(pc, isUsbExclusive, ffmpegOutputSr, ffmpegOutputBd, targetBits, usbStatus)
        val deviceLines = listOf(
            InfoLine(deviceInfo.name, outputSettingsDest, isUsbExclusive),
            InfoLine("架构：${deviceInfo.route}"),
            InfoLine("当前格式：${deviceInfo.format}"),
            InfoLine("音量链路：${deviceInfo.volumePath}")
        )

        popupData = AudioInfoPopupData(
            sections = listOf(
                TimelineSection(R.drawable.ic_music_2_fill, "媒体库", mediaLines),
                TimelineSection(R.drawable.ic_file_info_fill, "音轨", trackLines),
                TimelineSection(R.drawable.ic_sound_module_fill, "解码器", decoderLines),
                TimelineSection(R.drawable.ic_speed_fill, "重采样", resampleLines),
                TimelineSection(R.drawable.ic_equalizer_bars, "信号处理", signalLines),
                TimelineSection(R.drawable.ic_volume_up, "输出", outputLines),
                TimelineSection(getDeviceIconRes(), "设备", deviceLines)
            ),
            navigateToSettings = { onNavigateDestination(outputSettingsDest) },
            outputSettingsDest = outputSettingsDest
        )
        isPopupShowing = true
        onVisibilityChanged(true)

        AppLogger.d(
            "AudioOutput",
            "popup: outputMode=$actualOutputMode, outputSr=$ffmpegOutputSr, outputBd=$ffmpegOutputBd, " +
                "srcSr=$srcSr, srcBd=$srcBd, latencyMs=$actualLatencyMs, bufFrames=$actualBufFrames, usbExclusive=$isUsbExclusive"
        )
    }

    private fun buildSignalProcessingLines(outputSr: Int, outputBits: Int): List<InfoLine> {
        val lines = mutableListOf<InfoLine>()
        val peqFilters = readPeqFilters()
        val activePeqFilters = peqFilters.count { it.enabled }
        val peqName = AppPreferences.PEQ.presetName.ifBlank { "自定义" }
        val peqState = if (AppPreferences.PEQ.isEnabled) {
            "参量均衡器：$peqName，$activePeqFilters 个滤波器，Preamp ${formatDb(AppPreferences.PEQ.preamp)}"
        } else {
            "参量均衡器：关闭"
        }
        lines += InfoLine("处理格式：${formatOutputBitDepth(outputBits, AppPreferences.Player.targetBitDepth)} / ${formatSampleRate(outputSr)}")
        lines += InfoLine("均衡器", isLabel = true)
        lines += InfoLine(peqState, R.id.nav_peq, true)
        lines += InfoLine("音效", isLabel = true)
        val enabledEffects = mutableListOf<String>()
        if (AppPreferences.BassBoost.isEnabled) enabledEffects += "低音 ${formatDb(AppPreferences.BassBoost.gainDB)}"
        if (AppPreferences.TrebleBoost.isEnabled) enabledEffects += "高音 ${formatDb(AppPreferences.TrebleBoost.gainDB)}"
        if (AppPreferences.Compressor.isEnabled) enabledEffects += "压限器"
        if (AppPreferences.Surround360.isEnabled) enabledEffects += "360 环绕"
        if (AppPreferences.Panoramic360.isEnabled) enabledEffects += "360 全景"
        if (AppPreferences.Equalizer.virtualizer > 0) enabledEffects += "立体声扩展 ${AppPreferences.Equalizer.virtualizer / 10}%"
        if (AppPreferences.Equalizer.crossfeedEnabled) enabledEffects += "互馈 ${AppPreferences.Equalizer.crossfeedAttenuation / 10f}dB"
        lines += InfoLine(if (enabledEffects.isEmpty()) "关闭" else enabledEffects.joinToString(" / "), R.id.nav_audio_effects, true)
        lines += InfoLine("音调", isLabel = true)
        lines += InfoLine(
            "低音 ${formatDb(AppPreferences.BassBoost.gainDB)} @ ${formatFrequency(AppPreferences.BassBoost.frequency)}，" +
                "高音 ${formatDb(AppPreferences.TrebleBoost.gainDB)} @ ${formatFrequency(AppPreferences.TrebleBoost.frequency)}",
            R.id.nav_bass_treble_boost,
            true
        )
        lines += InfoLine("增益", isLabel = true)
        val totalPositiveGain = listOf(
            AppPreferences.PEQ.preamp,
            if (AppPreferences.BassBoost.isEnabled) AppPreferences.BassBoost.gainDB else 0f,
            if (AppPreferences.TrebleBoost.isEnabled) AppPreferences.TrebleBoost.gainDB else 0f,
            if (AppPreferences.Compressor.isEnabled) AppPreferences.Compressor.makeupGainDB else 0f
        ).filter { it > 0f }.sum()
        lines += InfoLine("叠加正增益 ${formatDb(totalPositiveGain)}，回放增益 ${if (AppPreferences.Player.replayGainEnabled) "开启" else "关闭"}")
        lines += InfoLine("音量控制", isLabel = true)
        lines += InfoLine("软件音量 ${"%.0f".format(AppPreferences.Player.volume * 100f)}%，硬件 Feature Unit ${if (AppPreferences.Player.hardwareFeatureUnitEnabled) "开启" else "关闭"}")
        lines += InfoLine("交叉淡入淡出", isLabel = true)
        lines += InfoLine(if (AppPreferences.Player.crossfadeDuration > 0) "${AppPreferences.Player.crossfadeDuration} 秒" else "关闭")
        lines += InfoLine("缓冲区", isLabel = true)
        lines += InfoLine("播放器实时缓冲，输出端按设备水位自适应")
        lines += InfoLine("延迟", isLabel = true)
        lines += InfoLine("歌词已移除设备延迟补偿，仅保留手动偏移")
        return lines
    }

    private fun readPeqFilters(): List<PEQFilter> {
        val json = AppPreferences.PEQ.filtersJson
        if (json.isBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<PEQFilter>>() {}.type
            Gson().fromJson<List<PEQFilter>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun resolveDeviceInfo(
        pc: PlayerController,
        isUsbExclusive: Boolean,
        outputSr: Int,
        outputBits: Int,
        targetBits: Int,
        usbStatus: PlayerController.UsbDeviceStatus? = null
    ): DeviceInfoLine {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val usbDeviceName = pc.getUsbDeviceName()
        val btDevice = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val wiredDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE
        }
        return when {
            isUsbExclusive && usbDeviceName != null -> DeviceInfoLine(
                name = "$usbDeviceName (DAC)",
                route = "USB Audio Class / Raw USB",
                format = usbStatus?.actualOutputFormat?.takeIf { it.isNotBlank() && it != "未开始输出" }
                    ?: "${formatOutputBitDepth(outputBits, targetBits)} / ${formatSampleRate(outputSr)}",
                volumePath = if (AppPreferences.Player.hardwareFeatureUnitEnabled) "USB 硬件音量" else "应用软件音量"
            )
            btDevice != null -> DeviceInfoLine(
                name = btDevice.productName?.toString() ?: "蓝牙设备",
                route = "Android AudioTrack / Bluetooth A2DP",
                format = "系统协商 / ${cachedBluetoothCodec ?: "Codec 检测中"}",
                volumePath = "系统蓝牙音量"
            )
            wiredDevice != null -> DeviceInfoLine(
                name = wiredDevice.productName?.toString() ?: "有线音频设备",
                route = if (wiredDevice.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                    wiredDevice.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE) {
                    "Android USB Audio"
                } else {
                    "Android Wired Output"
                },
                format = "${formatOutputBitDepth(outputBits, targetBits)} / ${formatSampleRate(outputSr)}",
                volumePath = "系统音量"
            )
            else -> DeviceInfoLine(
                name = "内置扬声器",
                route = "Android Mixer",
                format = "${formatOutputBitDepth(outputBits, targetBits)} / ${formatSampleRate(outputSr)}",
                volumePath = "系统音量"
            )
        }
    }

    private fun bitPerfectStatus(isUsbExclusive: Boolean, srChanged: Boolean, bdChanged: Boolean): String {
        return bitPerfectStatus(isUsbExclusive, srChanged, bdChanged, null)
    }

    private fun bitPerfectStatus(
        isUsbExclusive: Boolean,
        srChanged: Boolean,
        bdChanged: Boolean,
        usbStatus: PlayerController.UsbDeviceStatus?
    ): String {
        if (!isUsbExclusive) return "不适用"
        if (usbStatus?.dsdActive == true) {
            return if (usbStatus.dsdSourceDirect) "DSD源直通" else "关闭，当前为 PCM→DSD"
        }
        if (!AppPreferences.Player.bitPerfectEnabled) return "关闭"
        return if (!srChanged && !bdChanged) "开启，当前直通" else "开启，但当前发生格式转换"
    }

    private fun buildResampleLines(
        fmt: String,
        srcSr: Int,
        srcBd: Int,
        ffmpegOutputSr: Int,
        ffmpegOutputBd: Int,
        targetSr: Int,
        targetBits: Int,
        outputSettingsDest: Int,
        usbStatus: PlayerController.UsbDeviceStatus?
    ): List<InfoLine> {
        if (usbStatus?.dsdActive == true) {
            val dsdTarget = usbStatus.actualOutputFormat
                .takeIf { it.isNotBlank() && it != "未开始输出" }
                ?: usbStatus.targetFormat
            val conversionLine = if (usbStatus.dsdSourceDirect) {
                "转换：DSD源直通"
            } else {
                "转换：${formatSampleRate(srcSr)} / ${formatBitDepth(srcBd, fmt)} → $dsdTarget"
            }
            return listOf(
                InfoLine(conversionLine, outputSettingsDest, true),
                InfoLine("链路：${usbStatus.outputChain}"),
                InfoLine("目标：$dsdTarget")
            )
        }

        val srChanged = ffmpegOutputSr > 0 && srcSr > 0 && srcSr != ffmpegOutputSr
        val bdChanged = ffmpegOutputBd > 0 && srcBd > 0 && srcBd != ffmpegOutputBd
        return listOf(
            InfoLine(
                if (srChanged) "${formatSampleRate(srcSr)} → ${formatSampleRate(ffmpegOutputSr)}"
                else "采样率：直通 ${formatSampleRate(srcSr)}",
                outputSettingsDest,
                true
            ),
            InfoLine(
                if (bdChanged) "${formatBitDepth(srcBd, fmt)} → ${formatOutputBitDepth(ffmpegOutputBd, targetBits)}"
                else "位深：直通 ${formatBitDepth(srcBd, fmt)}",
                outputSettingsDest,
                true
            ),
            InfoLine("目标：${AudioOutputManager.SAMPLE_RATE_LABELS[targetSr] ?: "自动"} / ${AudioOutputManager.BIT_DEPTH_LABELS[targetBits] ?: "自动"}")
        )
    }

    private fun formatSampleRate(rate: Int): String {
        if (rate <= 0) return "未知"
        val khz = rate / 1000.0
        return if (khz == khz.toLong().toDouble()) "${khz.toLong()} kHz" else "${"%.1f".format(khz)} kHz"
    }

    private fun formatBitDepth(bits: Int, format: String = ""): String {
        if (bits <= 0) {
            if (com.rawsmusic.module.scanner.AudioBitDepthResolver.isLossyDisplayFormat(format)) {
                return "有损编码"
            }
            return "未知"
        }
        val isFloat = format.contains("FLOAT", true)
        val isDsd = bits == 1 ||
            format.contains("DSD", true) ||
            format.contains("DSF", true) ||
            format.contains("DFF", true)
        return when {
            isDsd -> "1 bit (DSD)"
            isFloat && bits == 32 -> "Float32"
            isFloat -> "$bits bit Float"
            else -> "$bits bit"
        }
    }

    private fun formatOutputBitDepth(bits: Int, targetBits: Int): String {
        val targetLabel = AudioOutputManager.BIT_DEPTH_LABELS[targetBits]
        if (targetBits == AudioOutputManager.BIT_DEPTH_FLOAT32) return "Float32"
        if (targetBits == AudioOutputManager.BIT_DEPTH_32_8_24) return "32 (8.24)"
        return targetLabel ?: formatBitDepth(bits)
    }

    private fun formatBitrate(bitRate: Int): String {
        return com.rawsmusic.core.common.utils.BitrateNormalizer.formatKbps(bitRate)
    }

    private fun formatChannelCount(channels: Int): String {
        return when (channels) {
            1 -> "单声道"
            2 -> "立体声"
            in 3..Int.MAX_VALUE -> "${channels}ch"
            else -> "未知声道"
        }
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "未知时长"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "未知大小"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 100) "${mb.roundToInt()} MB" else "%.1f MB".format(mb)
    }

    private fun formatDb(value: Float): String {
        return "%+.1f dB".format(value)
    }

    private fun formatFrequency(value: Float): String {
        return if (value >= 1000f) "%.1f kHz".format(value / 1000f) else "${value.roundToInt()} Hz"
    }

    private data class DeviceInfoLine(
        val name: String,
        val route: String,
        val format: String,
        val volumePath: String
    )
}

data class InfoLine(
    val text: String,
    val destinationId: Int? = null,
    val underline: Boolean = false,
    val isLabel: Boolean = false
)

data class TimelineSection(
    val iconRes: Int,
    val title: String,
    val lines: List<InfoLine>
)

data class AudioInfoPopupData(
    val sections: List<TimelineSection>,
    val navigateToSettings: () -> Unit,
    val outputSettingsDest: Int
)

@Composable
fun AudioInfoCapsuleOverlay(
    helper: AudioInfoCapsuleHelper,
    modifier: Modifier = Modifier
) {
    val data = helper.popupDataForCompose() ?: return
    AnimatedVisibility(
        visible = helper.isPopupShowing,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(0x66000000))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { helper.dismissPopup() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = helper.isPopupShowing,
                enter = fadeIn(tween(250)) + slideInVertically(tween(300), initialOffsetY = { it / 8 }) + scaleIn(tween(300), initialScale = 0.9f),
                exit = fadeOut(tween(180)) + slideOutVertically(tween(200), targetOffsetY = { it / 10 }) + scaleOut(tween(200), targetScale = 0.95f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(560.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(ComposeColor(0xFF2D2D2D))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(ComposeColor.White.copy(alpha = 0.7f)),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "音频信息",
                            color = ComposeColor.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        )
                        Image(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(ComposeColor.White.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    helper.dismissPopup()
                                    data.navigateToSettings()
                                }
                                .padding(7.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(end = 4.dp, bottom = 18.dp)
                    ) {
                        data.sections.forEachIndexed { index, section ->
                            AudioInfoSection(
                                section = section,
                                showConnector = index != data.sections.lastIndex,
                                onNavigate = { destination ->
                                    helper.dismissPopup()
                                    helper.navigateFromCompose(destination)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioInfoSection(
    section: TimelineSection,
    showConnector: Boolean,
    onNavigate: (Int) -> Unit
) {
    var contentHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val iconSizeDp = 21.dp
    val iconGapDp = 8.dp
    val connectorColor = ComposeColor.White.copy(alpha = 0.22f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(48.dp)
        ) {
            Image(
                painter = painterResource(section.iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ComposeColor.White.copy(alpha = 0.7f)),
                modifier = Modifier.size(iconSizeDp)
            )
            if (showConnector) {
                val iconSizePx = with(density) { iconSizeDp.toPx() }
                val gapPx = with(density) { iconGapDp.toPx() }
                val connectorHeightPx = (contentHeightPx - iconSizePx - gapPx).coerceAtLeast(0f)
                val arrowSizePx = with(density) { 4.dp.toPx() }
                val strokePx = with(density) { 1.dp.toPx() }

                Canvas(
                    modifier = Modifier
                        .width(16.dp)
                        .height(with(density) { connectorHeightPx.toDp() })
                ) {
                    val cx = size.width / 2f
                    val lineEndY = size.height - arrowSizePx * 1.8f

                    // Vertical line
                    drawLine(
                        color = connectorColor,
                        start = Offset(cx, 0f),
                        end = Offset(cx, lineEndY),
                        strokeWidth = strokePx
                    )

                    // Arrow head (filled triangle)
                    val arrowPath = Path().apply {
                        moveTo(cx - arrowSizePx, lineEndY)
                        lineTo(cx, size.height)
                        lineTo(cx + arrowSizePx, lineEndY)
                        close()
                    }
                    drawPath(arrowPath, color = connectorColor)
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, top = 3.dp, bottom = 14.dp)
                .onSizeChanged { contentHeightPx = it.height }
        ) {
            Text(
                text = section.title,
                color = ComposeColor.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            section.lines.forEach { line ->
                val clickable = line.destinationId != null
                Text(
                    text = line.text,
                    color = when {
                        clickable -> ComposeColor.White
                        line.isLabel -> ComposeColor.White.copy(alpha = 0.4f)
                        else -> ComposeColor.White.copy(alpha = 0.7f)
                    },
                    fontSize = if (line.isLabel) 12.sp else 13.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = clickable) {
                            line.destinationId?.let(onNavigate)
                        }
                        .padding(top = if (line.isLabel) 5.dp else 1.dp, bottom = 1.dp)
                )
            }
        }
    }
}
