package com.rawsmusic.module.player.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import android.provider.Settings
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.File
import java.security.MessageDigest

/**
 * Device-scoped USB Feature Unit volume persistence.
 *
 * UAC volume ranges and resolutions are device specific. Persist the exact raw value accepted by
 * the DAC instead of reusing a global UI percentage across unrelated devices.
 */
object UsbHardwareVolumeStore {
    private const val TAG = "UsbHardwareVolumeStore"
    private const val KEY_PREFIX = "usb_hw_volume_v2_"
    private const val KEY_UNCLEAN_ACTIVE = "usb_hw_volume_unclean_active"
    private const val KEY_UNCLEAN_DEVICE = "usb_hw_volume_unclean_device"
    private const val KEY_RECOVERY_BLOCKED = "usb_hw_volume_recovery_blocked"
    private const val KEY_RECOVERY_BLOCK_VERSION = "usb_hw_volume_recovery_block_version"
    private const val RECOVERY_BLOCK_VERSION = 2
    private const val KEY_ACTIVE_BOOT_ID = "usb_hw_volume_active_boot_id"
    private const val KEY_ACTIVE_BOOT_COUNT = "usb_hw_volume_active_boot_count"
    private const val KEY_ACTIVE_ELAPSED_MS = "usb_hw_volume_active_elapsed_ms"

    data class StoredVolume(
        val raw: Int,
        val minRaw: Int,
        val maxRaw: Int,
        val resRaw: Int,
    ) {
        fun isCompatible(min: Int, max: Int): Boolean =
            min < max && raw in min..max && minRaw < maxRaw
    }

    fun deviceKey(device: UsbDevice): String {
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty().trim()
        val product = device.productName.orEmpty().trim()
        val manufacturer = device.manufacturerName.orEmpty().trim()
        val stableSuffix = when {
            serial.isNotBlank() -> "serial:$serial"
            product.isNotBlank() || manufacturer.isNotBlank() -> "name:$manufacturer|$product"
            else -> "device:${device.deviceName}"
        }
        val identity = "%04x:%04x:%s".format(device.vendorId, device.productId, stableSuffix)
        return sha256(identity).take(24)
    }

    /**
     * Stable alias for DACs that expose no serial number. Android can change deviceName when the
     * same DAC is reattached to another USB bus address, so it must never be the only persistence
     * key for hardware volume.
     */
    private fun stableDeviceKey(device: UsbDevice): String {
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty().trim()
        val product = device.productName.orEmpty().trim()
        val manufacturer = device.manufacturerName.orEmpty().trim()
        val identitySuffix = when {
            serial.isNotBlank() -> "serial:$serial"
            manufacturer.isNotBlank() || product.isNotBlank() -> "name:$manufacturer|$product"
            else -> "vidpid-only"
        }
        return sha256("${device.vendorId.toString(16)}:${device.productId.toString(16)}:$identitySuffix")
            .take(24)
    }

    private fun persistenceKeys(device: UsbDevice): List<String> =
        listOf(deviceKey(device), stableDeviceKey(device)).distinct()

    private fun readAtKey(storage: com.tencent.mmkv.MMKV, key: String): StoredVolume? {
        val base = KEY_PREFIX + key
        if (!storage.containsKey("${base}_raw")) return null
        return StoredVolume(
            raw = storage.decodeInt("${base}_raw", Int.MIN_VALUE),
            minRaw = storage.decodeInt("${base}_min", Int.MIN_VALUE),
            maxRaw = storage.decodeInt("${base}_max", Int.MAX_VALUE),
            resRaw = storage.decodeInt("${base}_res", 1).coerceAtLeast(1),
        ).takeIf { it.raw != Int.MIN_VALUE }
    }

    fun read(device: UsbDevice): StoredVolume? {
        val storage = AppPreferences.storage
        val keys = persistenceKeys(device)
        val stored = keys.firstNotNullOfOrNull { readAtKey(storage, it) }
        if (stored == null) {
            AppLogger.w(TAG, "No persisted hardware volume: keys=${keys.joinToString()} device=${device.deviceName}")
        } else {
            AppLogger.i(TAG, "Read persisted hardware volume: raw=${stored.raw} keys=${keys.joinToString()}")
        }
        return stored
    }

    fun write(device: UsbDevice, raw: Int, minRaw: Int, maxRaw: Int, resRaw: Int, reason: String) {
        if (minRaw >= maxRaw || raw !in minRaw..maxRaw) {
            AppLogger.w(
                TAG,
                "Ignore invalid hardware volume persistence: raw=$raw range=$minRaw..$maxRaw reason=$reason",
            )
            return
        }
        val storage = AppPreferences.storage
        for (key in persistenceKeys(device)) {
            val base = KEY_PREFIX + key
            storage.encode("${base}_raw", raw)
            storage.encode("${base}_min", minRaw)
            storage.encode("${base}_max", maxRaw)
            storage.encode("${base}_res", resRaw.coerceAtLeast(1))
        }
        AppPreferences.sync()
        AppLogger.i(
            TAG,
            "Persisted device hardware volume: keys=${persistenceKeys(device).joinToString()} raw=$raw " +
                "range=$minRaw..$maxRaw res=${resRaw.coerceAtLeast(1)} reason=$reason",
        )
    }

    private data class BootIdentity(
        val bootId: String,
        val bootCount: Int,
        val elapsedRealtimeMs: Long,
    )

    /**
     * Mark a hardware-volume USB session as active until a clean teardown is observed.
     *
     * The boot identity is persisted with the marker. A normal process/task removal within the
     * same Android boot must not be treated as a kernel/host-controller reboot on the next cold
     * launch; only a marker that crosses a real system boot arms the persistent recovery block.
     */
    fun markSessionActive(context: Context, device: UsbDevice?) {
        val boot = readBootIdentity(context)
        AppPreferences.storage.encode(KEY_UNCLEAN_ACTIVE, true)
        AppPreferences.storage.encode(KEY_UNCLEAN_DEVICE, device?.let(::deviceKey).orEmpty())
        AppPreferences.storage.encode(KEY_ACTIVE_BOOT_ID, boot.bootId)
        AppPreferences.storage.encode(KEY_ACTIVE_BOOT_COUNT, boot.bootCount)
        AppPreferences.storage.encode(KEY_ACTIVE_ELAPSED_MS, boot.elapsedRealtimeMs)
        AppPreferences.sync()
    }

    fun markSessionClean(reason: String) {
        if (AppPreferences.storage.decodeBool(KEY_UNCLEAN_ACTIVE, false)) {
            AppLogger.i(TAG, "Hardware USB session marked clean: reason=$reason")
        }
        clearActiveMarker()
        AppPreferences.sync()
    }

    /**
     * Returns true only when an active hardware-volume marker crossed a real Android system boot.
     *
     * A task swipe, force-stop, low-memory process reclaim, app update, or ordinary process crash
     * leaves the same kernel boot identity. Those cases consume the stale marker without disabling
     * USB exclusive on the next cold launch. A different boot identity still arms the persistent
     * interlock, preserving the protection for the observed kernel/USB-host reboot failure.
     */
    fun consumeUncleanSessionMarker(context: Context): Boolean {
        val active = AppPreferences.storage.decodeBool(KEY_UNCLEAN_ACTIVE, false)
        if (!active) return false

        val deviceKey = AppPreferences.storage.decodeString(KEY_UNCLEAN_DEVICE, "").orEmpty()
        val storedBootId = AppPreferences.storage.decodeString(KEY_ACTIVE_BOOT_ID, "").orEmpty()
        val storedBootCount = AppPreferences.storage.decodeInt(KEY_ACTIVE_BOOT_COUNT, -1)
        val storedElapsedMs = AppPreferences.storage.decodeLong(KEY_ACTIVE_ELAPSED_MS, -1L)
        val current = readBootIdentity(context)

        val crossedSystemBoot = when {
            storedBootId.isNotBlank() && current.bootId.isNotBlank() ->
                storedBootId != current.bootId
            storedBootCount >= 0 && current.bootCount >= 0 ->
                storedBootCount != current.bootCount
            storedElapsedMs >= 0L ->
                current.elapsedRealtimeMs + 60_000L < storedElapsedMs
            else -> false // Legacy marker: bias toward restoring playback, not a false permanent block.
        }

        if (crossedSystemBoot) {
            AppLogger.e(
                TAG,
                "Hardware USB session crossed a system boot; deviceKey=$deviceKey " +
                    "storedBootId=${storedBootId.take(12)} currentBootId=${current.bootId.take(12)} " +
                    "storedBootCount=$storedBootCount currentBootCount=${current.bootCount}",
            )
            AppPreferences.storage.encode(KEY_RECOVERY_BLOCKED, true)
            AppPreferences.storage.encode(KEY_RECOVERY_BLOCK_VERSION, RECOVERY_BLOCK_VERSION)
        } else {
            AppLogger.w(
                TAG,
                "Discarding same-boot stale hardware USB marker after app process restart; " +
                    "deviceKey=$deviceKey bootCount=${current.bootCount}",
            )
        }

        clearActiveMarker()
        AppPreferences.sync()
        return crossedSystemBoot
    }


    private fun clearActiveMarker() {
        AppPreferences.storage.encode(KEY_UNCLEAN_ACTIVE, false)
        AppPreferences.storage.removeValueForKey(KEY_UNCLEAN_DEVICE)
        AppPreferences.storage.removeValueForKey(KEY_ACTIVE_BOOT_ID)
        AppPreferences.storage.removeValueForKey(KEY_ACTIVE_BOOT_COUNT)
        AppPreferences.storage.removeValueForKey(KEY_ACTIVE_ELAPSED_MS)
    }

    private fun readBootIdentity(context: Context): BootIdentity {
        val bootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrDefault("")
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(-1)
        return BootIdentity(
            bootId = bootId,
            bootCount = bootCount,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    }

    fun isRecoveryBlocked(): Boolean {
        val blocked = AppPreferences.storage.decodeBool(KEY_RECOVERY_BLOCKED, false)
        if (!blocked) return false
        val version = AppPreferences.storage.decodeInt(KEY_RECOVERY_BLOCK_VERSION, 0)
        if (version < RECOVERY_BLOCK_VERSION) {
            // Earlier builds treated every app-process death as a system reboot. Migrate that
            // ambiguous legacy latch once; new markers carry boot identity and remain protected.
            AppLogger.w(TAG, "Clearing legacy USB recovery block without boot identity metadata")
            AppPreferences.storage.encode(KEY_RECOVERY_BLOCKED, false)
            AppPreferences.storage.encode(KEY_RECOVERY_BLOCK_VERSION, RECOVERY_BLOCK_VERSION)
            AppPreferences.sync()
            return false
        }
        return true
    }

    fun clearRecoveryBlock(reason: String) {
        if (isRecoveryBlocked()) {
            AppLogger.w(TAG, "USB hardware recovery interlock cleared by explicit user action: $reason")
        }
        AppPreferences.storage.encode(KEY_RECOVERY_BLOCKED, false)
        AppPreferences.storage.encode(KEY_RECOVERY_BLOCK_VERSION, RECOVERY_BLOCK_VERSION)
        AppPreferences.sync()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
