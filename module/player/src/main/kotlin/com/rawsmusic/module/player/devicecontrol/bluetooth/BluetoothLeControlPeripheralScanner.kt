package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded BLE companion-peripheral lookup for the current audio device.
 *
 * Some dual-mode earbuds expose their media route on the bonded BR/EDR identity while their
 * control GATT server is reachable through a companion LE identity. Discovery is short-lived and
 * time-bounded: first scan by vendor-looking UUID hints, then (only after that returns zero results)
 * use a short unfiltered scan with strict active-route name matching. Ambiguous matches are rejected.
 */
internal class BluetoothLeControlPeripheralScanner(
    context: Context,
    private val timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS,
    private val adapterProvider: () -> BluetoothAdapter? = { BluetoothAdapter.getDefaultAdapter() },
) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data class Found(
            val device: BluetoothDevice,
            val source: String,
            val diagnostics: List<String>,
        ) : Result

        data class Failed(
            val reason: String,
            val diagnostics: List<String>,
        ) : Result

        data object PermissionRequired : Result
    }

    suspend fun resolve(
        routeDevice: BluetoothDevice,
        onProgress: (String) -> Unit = {},
    ): Result {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return Result.PermissionRequired
        }
        if (!BluetoothDeviceControlPermissions.hasScanPermission(appContext)) {
            onProgress("bt.scan permission=missing")
            return Result.PermissionRequired
        }

        val adapter = adapterProvider()
            ?: return Result.Failed("bluetooth_adapter_unavailable", listOf("bt.scan adapter=missing"))
        val scanner = adapter.bluetoothLeScanner
            ?: return Result.Failed("bluetooth_le_scanner_unavailable", listOf("bt.scan scanner=missing"))
        val serviceUuids = routeDevice.cachedVendorServiceUuids()
        if (serviceUuids.isEmpty()) {
            return Result.Failed(
                "bluetooth_control_companion_no_vendor_uuid_hint",
                listOf("bt.scan vendor_uuid_hints=0"),
            )
        }

        resolveAlreadyConnectedGatt(routeDevice, serviceUuids, onProgress)?.let { return it }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val routeAddress = runCatching { routeDevice.address }.getOrNull()
        val routeName = runCatching { routeDevice.name }.getOrNull()?.trim().orEmpty()

        // First use the narrow service-UUID scan for generic devices. OPO/BBK is a known exception:
        // the 079A GATT service may exist after connection without being present in advertising,
        // so a service-UUID filter only adds delay and can hide the actual control peripheral.
        val filtered: ScanWindowResult = if (prefersBluetoothControlCompanionLookup(serviceUuids)) {
            val line = "bt.scan.service_filtered skipped=known_opo_nonadvertised_service"
            onProgress(line)
            ScanWindowResult.Failed("no_candidates", listOf(line))
        } else {
            scanWindow(
                scanner = scanner,
                filters = serviceUuids.map { uuid ->
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
                },
                settings = settings,
                routeAddress = routeAddress,
                routeName = routeName,
                vendorServiceUuids = serviceUuids.toSet(),
                mode = ScanMode.SERVICE_FILTERED,
                timeoutMs = timeoutMs,
                onProgress = onProgress,
            )
        }
        when (filtered) {
            is ScanWindowResult.Found -> return filtered.toPublicResult("scan_service_filtered")
            is ScanWindowResult.Failed -> {
                if (filtered.reason != "no_candidates") {
                    return Result.Failed(filtered.reason, filtered.diagnostics)
                }
            }
        }

        if (routeName.isBlank()) {
            return Result.Failed(
                "bluetooth_control_companion_not_found",
                filtered.diagnostics + "bt.scan.name_fallback skipped=route_name_empty",
            )
        }

        // Known OPO/BBK control devices are a concrete example where the control service can be
        // discoverable after connection without being advertised. Do a short unfiltered scan and
        // filter candidates in-process by the active route's name family. We never select
        // a merely-nearby device: one exact-address or one unique name-family match is required.
        val nameFallback = scanWindow(
            scanner = scanner,
            filters = emptyList(),
            settings = settings,
            routeAddress = routeAddress,
            routeName = routeName,
            vendorServiceUuids = serviceUuids.toSet(),
            mode = ScanMode.NAME_FALLBACK,
            timeoutMs = NAME_FALLBACK_SCAN_TIMEOUT_MS,
            onProgress = onProgress,
        )
        return when (nameFallback) {
            is ScanWindowResult.Found -> Result.Found(
                device = nameFallback.candidate.device,
                source = "scan_name_fallback",
                diagnostics = (filtered.diagnostics + nameFallback.diagnostics +
                    nameFallback.candidate.diagnostic("selected_name_fallback")).distinct(),
            )
            is ScanWindowResult.Failed -> Result.Failed(
                reason = "bluetooth_control_companion_not_found",
                diagnostics = (filtered.diagnostics + nameFallback.diagnostics).distinct(),
            )
        }
    }

    private enum class ScanMode { SERVICE_FILTERED, NAME_FALLBACK }

    private sealed interface ScanWindowResult {
        data class Found(
            val candidate: Candidate,
            val diagnostics: List<String>,
        ) : ScanWindowResult

        data class Failed(
            val reason: String,
            val diagnostics: List<String>,
        ) : ScanWindowResult
    }

    private suspend fun scanWindow(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        filters: List<ScanFilter>,
        settings: ScanSettings,
        routeAddress: String?,
        routeName: String,
        vendorServiceUuids: Set<UUID>,
        mode: ScanMode,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
    ): ScanWindowResult {
        val completion = CompletableDeferred<Unit>()
        val lock = Any()
        val candidates = LinkedHashMap<String, Candidate>()
        val diagnostics = mutableListOf<String>()
        val modeLabel = when (mode) {
            ScanMode.SERVICE_FILTERED -> "service_filtered"
            ScanMode.NAME_FALLBACK -> "name_fallback"
        }

        fun diagnostic(line: String) {
            synchronized(lock) { diagnostics += line }
            onProgress(line)
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val candidate = result.device
                val address = runCatching { candidate.address }.getOrNull() ?: return
                val record = result.scanRecord
                val advertised = record?.serviceUuids.orEmpty().map { it.uuid }.toSet()
                val matched = advertised.intersect(vendorServiceUuids)
                val candidateName = runCatching { candidate.name }.getOrNull()?.trim()
                    .takeUnless { it.isNullOrEmpty() }
                    ?: record?.deviceName?.trim().orEmpty()
                val sameAddress = routeAddress?.equals(address, ignoreCase = true) == true
                val sameName = bluetoothControlNamesMatch(routeName, candidateName)
                val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { result.isConnectable }.getOrDefault(true)
                } else {
                    true
                }
                val primaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { result.primaryPhy }.getOrDefault(0)
                } else {
                    0
                }
                val secondaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { result.secondaryPhy }.getOrDefault(0)
                } else {
                    0
                }
                val deviceType = runCatching { candidate.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                val acceptedIdentity = when (mode) {
                    ScanMode.SERVICE_FILTERED -> matched.isNotEmpty()
                    ScanMode.NAME_FALLBACK -> sameAddress || sameName
                }
                if (!acceptedIdentity) return
                diagnostic(
                    "bt.scan.$modeLabel observed sameAddress=$sameAddress sameName=$sameName " +
                        "connectable=$connectable deviceType=$deviceType rssi=${result.rssi} primaryPhy=$primaryPhy secondaryPhy=$secondaryPhy " +
                        "advertisedVendorServices=${matched.size}",
                )
                if (!connectable) return
                synchronized(lock) {
                    candidates[address.uppercase(Locale.ROOT)] = Candidate(
                        device = candidate,
                        sameAddress = sameAddress,
                        sameName = sameName,
                        connectable = true,
                        deviceType = deviceType,
                        rssi = result.rssi,
                        primaryPhy = primaryPhy,
                        secondaryPhy = secondaryPhy,
                        matchedServiceUuids = matched,
                    )
                }
                // Do not stop a name-fallback scan just because the BR/EDR route identity also
                // emits a connectable BLE advertisement. OPO/BBK devices can expose a separate
                // LE control identity a moment later. Collect the full bounded window and rank
                // candidates after scanning. Service-filtered discovery can still finish early.
                if (mode == ScanMode.SERVICE_FILTERED && !completion.isCompleted) {
                    completion.complete(Unit)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                diagnostic("bt.scan.$modeLabel failed_code=$errorCode")
                if (!completion.isCompleted) completion.complete(Unit)
            }
        }

        diagnostic(
            if (mode == ScanMode.SERVICE_FILTERED) {
                "bt.scan start mode=$modeLabel vendor_uuids=${vendorServiceUuids.joinToString(",")} timeoutMs=$timeoutMs"
            } else {
                "bt.scan start mode=$modeLabel routeName=${redactBluetoothDiagnosticName(routeName)} timeoutMs=$timeoutMs"
            },
        )
        val started = runCatching {
            // Even for an empty filter list, use the explicit-settings overload so the bounded
            // name fallback really runs at LOW_LATENCY instead of silently falling back to the
            // platform's default scan settings.
            scanner.startScan(filters, settings, callback)
            true
        }.getOrElse { error ->
            return ScanWindowResult.Failed(
                "scan_start_failed:${error.javaClass.simpleName}",
                synchronized(lock) { diagnostics.toList() } +
                    "bt.scan.$modeLabel start_exception=${error.javaClass.simpleName}",
            )
        }
        if (!started) {
            return ScanWindowResult.Failed(
                "scan_start_rejected",
                synchronized(lock) { diagnostics.toList() },
            )
        }

        try {
            withTimeoutOrNull(timeoutMs) { completion.await() }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        val snapshot = synchronized(lock) { candidates.values.toList() }
        diagnostic("bt.scan.$modeLabel candidates=${snapshot.size}")
        val baseDiagnostics = synchronized(lock) { diagnostics.toList() }
        if (snapshot.isEmpty()) return ScanWindowResult.Failed("no_connectable_candidates", baseDiagnostics)

        // For name-fallback discovery, a distinct-address same-name LE identity is a stronger
        // control-peripheral signal than the bonded A2DP/dual-mode identity advertising over LE.
        // Never pick by RSSI alone: require uniqueness to avoid binding a nearby same-model pair.
        if (mode == ScanMode.NAME_FALLBACK) {
            val distinctAddressNameMatches = snapshot.filter { !it.sameAddress && it.sameName }
            if (distinctAddressNameMatches.size == 1) {
                return ScanWindowResult.Found(distinctAddressNameMatches.single(), baseDiagnostics)
            }
            if (distinctAddressNameMatches.size > 1) {
                return ScanWindowResult.Failed(
                    "ambiguous_distinct_name_candidates:${distinctAddressNameMatches.size}",
                    baseDiagnostics + distinctAddressNameMatches.mapIndexed { index, candidate ->
                        candidate.diagnostic("distinct_name_candidate_$index")
                    },
                )
            }
        }

        snapshot.singleOrNull { it.sameAddress }?.let {
            return ScanWindowResult.Found(it, baseDiagnostics)
        }
        val nameMatches = snapshot.filter { it.sameName }
        if (nameMatches.size == 1) return ScanWindowResult.Found(nameMatches.single(), baseDiagnostics)
        return ScanWindowResult.Failed(
            "ambiguous_candidates:${snapshot.size}",
            baseDiagnostics + snapshot.mapIndexed { index, candidate ->
                candidate.diagnostic("candidate_$index")
            },
        )
    }

    private fun ScanWindowResult.Found.toPublicResult(source: String): Result.Found = Result.Found(
        device = candidate.device,
        source = source,
        diagnostics = diagnostics + candidate.diagnostic("selected_$source"),
    )

    private fun resolveAlreadyConnectedGatt(
        routeDevice: BluetoothDevice,
        serviceUuids: List<UUID>,
        onProgress: (String) -> Unit,
    ): Result.Found? {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val connected = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }
            .getOrDefault(emptyList())
        onProgress("bt.gatt_connected candidates=${connected.size}")
        if (connected.isEmpty()) return null

        val routeAddress = runCatching { routeDevice.address }.getOrNull()
        val routeName = runCatching { routeDevice.name }.getOrNull()?.trim().orEmpty()
        val candidates = connected.map { candidate ->
            val address = runCatching { candidate.address }.getOrNull()
            val name = runCatching { candidate.name }.getOrNull()?.trim().orEmpty()
            ConnectedCandidate(
                device = candidate,
                sameAddress = routeAddress != null && routeAddress.equals(address, ignoreCase = true),
                sameName = bluetoothControlNamesMatch(routeName, name),
                matchingServices = candidate.cachedVendorServiceUuids().intersect(serviceUuids.toSet()),
            )
        }
        candidates.singleOrNull { it.sameAddress }?.let {
            val line = it.diagnostic("selected_same_address")
            onProgress(line)
            return Result.Found(it.device, "connected_gatt_same_address", listOf(line))
        }
        candidates.filter { it.sameName }.singleOrNull()?.let {
            val line = it.diagnostic("selected_unique_name")
            onProgress(line)
            return Result.Found(it.device, "connected_gatt_unique_name", listOf(line))
        }
        candidates.filter { it.matchingServices.isNotEmpty() }.singleOrNull()?.let {
            val line = it.diagnostic("selected_unique_vendor_service")
            onProgress(line)
            return Result.Found(it.device, "connected_gatt_unique_vendor_service", listOf(line))
        }
        return null
    }

    private data class ConnectedCandidate(
        val device: BluetoothDevice,
        val sameAddress: Boolean,
        val sameName: Boolean,
        val matchingServices: Set<UUID>,
    ) {
        fun diagnostic(prefix: String): String =
            "bt.gatt_connected $prefix sameAddress=$sameAddress sameName=$sameName services=${matchingServices.joinToString(",")}" 
    }

    private data class Candidate(
        val device: BluetoothDevice,
        val sameAddress: Boolean,
        val sameName: Boolean,
        val connectable: Boolean,
        val deviceType: Int,
        val rssi: Int,
        val primaryPhy: Int,
        val secondaryPhy: Int,
        val matchedServiceUuids: Set<UUID>,
    ) {
        fun diagnostic(prefix: String): String =
            "bt.scan $prefix sameAddress=$sameAddress sameName=$sameName connectable=$connectable " +
                "deviceType=$deviceType rssi=$rssi primaryPhy=$primaryPhy secondaryPhy=$secondaryPhy " +
                "services=${matchedServiceUuids.joinToString(",")}"
    }

    companion object {
        private const val DEFAULT_SCAN_TIMEOUT_MS = 3_500L
        private const val NAME_FALLBACK_SCAN_TIMEOUT_MS = 4_500L
    }
}

internal fun bluetoothControlNamesMatch(routeName: String, candidateName: String): Boolean {
    val route = normalizeBluetoothControlName(routeName)
    val candidate = normalizeBluetoothControlName(candidateName)
    if (route.isEmpty() || candidate.isEmpty()) return false
    if (route == candidate) return true
    val shorter = minOf(route.length, candidate.length)
    if (shorter < 8) return false
    return route.startsWith(candidate) || candidate.startsWith(route)
}

private fun normalizeBluetoothControlName(value: String): String =
    value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private fun redactBluetoothDiagnosticName(value: String): String {
    val normalized = value.trim()
    if (normalized.isEmpty()) return "<empty>"
    // Name is already displayed in the Hardware DSP page header; diagnostics only need enough
    // context to distinguish whether a strict name fallback was attempted.
    return if (normalized.length <= 4) "<redacted>" else normalized.take(4) + "…"
}

internal fun BluetoothDevice.cachedVendorServiceUuids(): List<UUID> =
    filterBluetoothVendorServiceHints(
        runCatching { uuids.orEmpty().map { it.uuid } }.getOrDefault(emptyList()),
    )
