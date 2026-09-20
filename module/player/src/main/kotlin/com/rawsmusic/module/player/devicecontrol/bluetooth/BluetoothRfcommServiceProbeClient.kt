package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connection-only RFCOMM/SDP probe for already-bonded vendor services.
 *
 * Safety contract:
 * - no InputStream reads;
 * - no OutputStream writes;
 * - no unknown protocol payloads;
 * - socket is closed immediately after connect succeeds;
 * - every candidate is bounded by a timeout and there is a small candidate cap.
 */
internal class BluetoothRfcommServiceProbeClient(
    context: Context,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) {
    private val appContext = context.applicationContext

    data class ServiceResult(
        val uuid: UUID,
        val source: String,
        val connectable: Boolean,
        val reason: String? = null,
    )

    sealed interface Result {
        data class Ready(
            val services: List<ServiceResult>,
            val diagnostics: List<String>,
        ) : Result

        data object PermissionRequired : Result
    }

    suspend fun probe(
        device: BluetoothDevice,
        cachedVendorUuids: List<UUID>,
        onProgress: (String) -> Unit = {},
    ): Result {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return Result.PermissionRequired
        }

        val candidates = BluetoothRfcommVendorHints.candidates(cachedVendorUuids)
        val diagnostics = mutableListOf<String>()
        val candidateLine = "bt.rfcomm candidates=${candidates.size}" +
            candidates.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " values=", separator = ",") { "${it.uuid}:${it.source}" }
                .orEmpty()
        diagnostics += candidateLine
        onProgress(candidateLine)

        if (candidates.isEmpty()) {
            return Result.Ready(emptyList(), diagnostics)
        }

        // Android explicitly recommends cancelling discovery before RFCOMM connect because device
        // discovery is heavyweight and can substantially slow SDP/socket establishment.
        if (BluetoothDeviceControlPermissions.hasScanPermission(appContext)) {
            val cancelled = runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
                .getOrNull()
            val line = "bt.rfcomm cancelDiscovery=${cancelled ?: "unavailable"}"
            diagnostics += line
            onProgress(line)
        } else {
            val line = "bt.rfcomm cancelDiscovery=skipped_scan_permission"
            diagnostics += line
            onProgress(line)
        }

        val services = mutableListOf<ServiceResult>()
        for (candidate in candidates) {
            val startLine = "bt.rfcomm probe uuid=${candidate.uuid} source=${candidate.source} timeoutMs=$connectTimeoutMs"
            diagnostics += startLine
            onProgress(startLine)

            val result = probeOne(device, candidate)
            services += result
            val resultLine = buildString {
                append("bt.rfcomm result uuid=").append(result.uuid)
                append(" source=").append(result.source)
                append(" connectable=").append(result.connectable)
                result.reason?.let { append(" reason=").append(it) }
            }
            diagnostics += resultLine
            onProgress(resultLine)
        }

        return Result.Ready(services, diagnostics.distinct())
    }

    private suspend fun probeOne(
        device: BluetoothDevice,
        candidate: BluetoothRfcommVendorHints.Candidate,
    ): ServiceResult = coroutineScope {
        val socket = runCatching { device.createRfcommSocketToServiceRecord(candidate.uuid) }
            .getOrElse { error ->
                return@coroutineScope ServiceResult(
                    uuid = candidate.uuid,
                    source = candidate.source,
                    connectable = false,
                    reason = "socket_create_${safeErrorName(error)}",
                )
            }

        val connect = async(Dispatchers.IO) {
            runCatching { socket.connect() }
        }
        try {
            val outcome = withTimeoutOrNull(connectTimeoutMs) { connect.await() }
            if (outcome == null) {
                // BluetoothSocket.close() is the abort primitive for a blocking connect(). Close
                // before joining the child so cancellation never strands a connect thread.
                closeQuietly(socket)
                connect.cancelAndJoin()
                return@coroutineScope ServiceResult(
                    uuid = candidate.uuid,
                    source = candidate.source,
                    connectable = false,
                    reason = "connect_timeout",
                )
            }

            val error = outcome.exceptionOrNull()
            val connected = error == null && runCatching { socket.isConnected }.getOrDefault(true)
            ServiceResult(
                uuid = candidate.uuid,
                source = candidate.source,
                connectable = connected,
                reason = if (connected) null else "connect_${safeErrorName(error)}",
            )
        } finally {
            closeQuietly(socket)
            if (connect.isActive) connect.cancel()
        }
    }

    private suspend fun closeQuietly(socket: BluetoothSocket) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { socket.close() }
        }
    }

    private fun safeErrorName(error: Throwable?): String =
        error?.javaClass?.simpleName?.takeIf { it.isNotBlank() } ?: "failed"

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 3_500L
    }
}
