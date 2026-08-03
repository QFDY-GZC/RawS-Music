package com.rawsmusic.module.player

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns Android Bluetooth route observation and A2DP codec inspection.
 *
 * PlayerController keeps playback policy; this class owns AudioManager polling,
 * BluetoothProfile proxy lifetime, HFP-only detection, and route-change cleanup.
 */
internal class AndroidBluetoothOutputController(
    context: Context,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    internal data class Callbacks(
        val currentTrackLatencyMs: () -> Int,
        val stopScoWithoutRouteRebuild: () -> Unit,
        val onBluetoothRouteDisconnected: () -> Unit,
    )

    companion object {
        private const val TAG = "AndroidBluetoothOutput"
        private const val POLL_INTERVAL_MS = 3_000L
        private const val PROFILE_PROXY_TIMEOUT_SECONDS = 1L
        private const val TYPE_BLE_HEADSET_COMPAT = 26
    }

    private val appContext = context.applicationContext
    private val _isBluetoothOutput = MutableStateFlow(false)
    val isBluetoothOutputFlow: StateFlow<Boolean> = _isBluetoothOutput.asStateFlow()
    val isBluetoothOutput: Boolean get() = _isBluetoothOutput.value

    private val _hfpOnlyDeviceDetected = MutableStateFlow(false)
    val hfpOnlyDeviceDetected: StateFlow<Boolean> = _hfpOnlyDeviceDetected.asStateFlow()

    @Volatile
    private var lastDetectedCodecType: Int = -1
    private var monitorJob: Job? = null
    private var cachedA2dpProxy: BluetoothProfile? = null
    private var a2dpProxyListener: BluetoothProfile.ServiceListener? = null

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.IO) {
            AppLogger.d(TAG, "Bluetooth output monitor started")
            while (isActive) {
                runCatching { checkBluetoothOutput() }
                    .onFailure { error ->
                        AppLogger.w(TAG, "Bluetooth route check failed", error)
                    }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun refreshNow() {
        if (monitorJob?.isActive != true) start()
        scope.launch(Dispatchers.IO) {
            runCatching { checkBluetoothOutput() }
                .onFailure { error ->
                    AppLogger.w(TAG, "Immediate Bluetooth route check failed", error)
                }
        }
    }

    fun getLatencyInfo(): String {
        if (!isBluetoothOutput) return ""
        val codecName = codecTypeName(lastDetectedCodecType)
        val trackLatency = callbacks.currentTrackLatencyMs()
        return if (lastDetectedCodecType >= 0) codecName else "BT ${trackLatency}ms"
    }

    fun release() {
        monitorJob?.cancel()
        monitorJob = null
        releaseA2dpProxy()
        _isBluetoothOutput.value = false
        _hfpOnlyDeviceDetected.value = false
        lastDetectedCodecType = -1
    }

    private suspend fun checkBluetoothOutput() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val bluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == TYPE_BLE_HEADSET_COMPAT
            }
        } else {
            null
        }
        val bluetoothConnected = bluetoothDevice != null

        if (bluetoothDevice != null) {
            AppLogger.d(
                TAG,
                "Bluetooth output found: type=${bluetoothDevice.type} name=${bluetoothDevice.productName}",
            )
        }
        if (bluetoothConnected == _isBluetoothOutput.value) return

        _isBluetoothOutput.value = bluetoothConnected
        if (bluetoothConnected) {
            val codecType = detectBluetoothCodecType()
            lastDetectedCodecType = codecType
            AppLogger.i(
                TAG,
                "Bluetooth connected: codec=$codecType/${codecTypeName(codecType)} " +
                    "trackLatencyMs=${callbacks.currentTrackLatencyMs()}",
            )
            if (AppPreferences.Player.bluetoothScoMode == 1) {
                val hfpOnly = AudioOutputManager.isBluetoothHfpOnlyDevice(appContext)
                _hfpOnlyDeviceDetected.value = hfpOnly
                if (hfpOnly) {
                    AppLogger.i(TAG, "HFP-only output detected; SCO will be used on next playback")
                }
            }
        } else {
            lastDetectedCodecType = -1
            _hfpOnlyDeviceDetected.value = false
            releaseA2dpProxy()
            callbacks.stopScoWithoutRouteRebuild()
            callbacks.onBluetoothRouteDisconnected()
            AppLogger.i(TAG, "Bluetooth disconnected; restored media output route")
        }
    }

    private fun releaseA2dpProxy() {
        val proxy = cachedA2dpProxy
        if (proxy != null) {
            runCatching {
                BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            }.onFailure { error ->
                AppLogger.w(TAG, "Closing A2DP profile proxy failed", error)
            }
        }
        cachedA2dpProxy = null
        a2dpProxyListener = null
    }

    private suspend fun detectBluetoothCodecType(): Int = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            AppLogger.w(TAG, "BluetoothAdapter unavailable while detecting codec")
            return@withContext -1
        }

        var a2dp = cachedA2dpProxy
        if (a2dp == null) {
            val profileProxy = arrayOfNulls<BluetoothProfile>(1)
            val latch = CountDownLatch(1)
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    profileProxy[0] = proxy
                    latch.countDown()
                }

                override fun onServiceDisconnected(profile: Int) {
                    latch.countDown()
                }
            }
            if (!adapter.getProfileProxy(appContext, listener, BluetoothProfile.A2DP)) {
                AppLogger.w(TAG, "A2DP profile proxy request was rejected")
                return@withContext -1
            }
            if (!latch.await(PROFILE_PROXY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                AppLogger.w(TAG, "A2DP profile proxy timed out")
                return@withContext -1
            }
            a2dp = profileProxy[0]
            if (a2dp != null) {
                cachedA2dpProxy = a2dp
                a2dpProxyListener = listener
            }
        }

        val connectedDevice = a2dp?.connectedDevices?.firstOrNull() ?: return@withContext -1
        readCodecType(a2dp, connectedDevice)
    }

    private fun readCodecType(a2dp: BluetoothProfile, device: BluetoothDevice): Int {
        return runCatching {
            val codecStatus = a2dp.javaClass
                .getMethod("getCodecStatus", BluetoothDevice::class.java)
                .invoke(a2dp, device)
                ?: return@runCatching -1
            val codecConfig = codecStatus.javaClass
                .getMethod("getCodecConfig")
                .invoke(codecStatus)
                ?: return@runCatching -1
            codecConfig.javaClass
                .getMethod("getCodecType")
                .invoke(codecConfig) as? Int ?: -1
        }.onFailure { error ->
            AppLogger.w(TAG, "Reading A2DP codec status failed", error)
        }.getOrDefault(-1)
    }

    private fun codecTypeName(codecType: Int): String = when (codecType) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        5, 1000 -> "LHDC"
        6 -> "LC3"
        7 -> "aptX Adaptive"
        8 -> "LHDC V5"
        else -> "Unknown"
    }
}
