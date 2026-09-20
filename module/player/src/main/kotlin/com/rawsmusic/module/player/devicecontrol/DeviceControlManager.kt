package com.rawsmusic.module.player.devicecontrol

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unified owner for the current physical audio device. UI consumes only [snapshot] and never
 * chooses between USB standard/vendor or Bluetooth standard/vendor transports.
 */
class DeviceControlManager(
    private val scope: CoroutineScope,
    private val backends: List<DeviceControlBackend>,
) : Closeable {
    private val generationCounter = AtomicLong(0L)
    private val lifecycleMutex = Mutex()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()

    private val _snapshot = MutableStateFlow(DeviceControlSnapshot(0L, null))
    val snapshot: StateFlow<DeviceControlSnapshot> = _snapshot.asStateFlow()

    @Volatile private var activeDevice: DeviceControlDevice? = null
    private var sessions: List<DeviceControlSession> = emptyList()
    private var collectors: List<Job> = emptyList()
    @Volatile private var closed = false

    suspend fun bind(device: DeviceControlDevice?) = lifecycleMutex.withLock {
        if (closed) return@withLock
        // Device-control backends are allowed to use blocking platform/native I/O (USB JNI/libusb,
        // RFCOMM/GATT setup). PlayerController is Main.immediate-owned, so never inherit the
        // caller dispatcher here: a permission result or route reconciliation must not turn a
        // multi-second hardware probe into an input-dispatch ANR.
        withContext(Dispatchers.IO) { closeSessionsLocked() }
        val generation = generationCounter.incrementAndGet()
        activeDevice = device
        if (device == null) {
            _snapshot.value = DeviceControlSnapshot(generation, null)
            return@withLock
        }

        _snapshot.value = DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = DeviceControlSnapshot.ProbeState.PROBING,
        )

        val opened = withContext(Dispatchers.IO) {
            buildList {
                backends.forEach { backend ->
                    val session = runCatching { backend.open(device, generation) }.getOrNull()
                    if (session != null) add(session)
                }
            }
        }
        synchronized(stateLock) { sessions = opened }
        collectors = opened.map { session ->
            scope.launch {
                session.snapshot.collectLatest { recompute(generation, device) }
            }
        }

        if (opened.isEmpty()) {
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.UNSUPPORTED,
                message = "no_device_control_backend",
            )
            return@withLock
        }

        withContext(Dispatchers.IO) {
            opened.forEach { session -> runCatching { session.probe() } }
        }
        recompute(generation, device)
    }

    suspend fun refresh() = lifecycleMutex.withLock {
        if (closed) return@withLock
        val device = activeDevice ?: return@withLock
        val generation = _snapshot.value.generation
        val activeSessions = synchronized(stateLock) { sessions.toList() }
        withContext(Dispatchers.IO) {
            activeSessions.forEach { session -> runCatching { session.refresh() } }
        }
        recompute(generation, device)
    }

    suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult {
        data class Dispatch(
            val owner: DeviceControlSession,
            val device: DeviceControlDevice,
            val generation: Long,
        )

        val dispatch = lifecycleMutex.withLock {
            if (closed) return@withLock null to DeviceControlWriteResult.Rejected("device_control_manager_closed")
            val owners = synchronized(stateLock) { sessions.toList() }
                .filter { it.snapshot.value.resolveControl(request.controlId) != null }
            if (owners.isEmpty()) {
                return@withLock null to DeviceControlWriteResult.Rejected("unknown_control:${request.controlId.value}")
            }
            if (owners.size != 1) {
                return@withLock null to DeviceControlWriteResult.Rejected("ambiguous_control_owner:${request.controlId.value}")
            }
            val device = activeDevice
                ?: return@withLock null to DeviceControlWriteResult.Rejected("no_active_device")
            Dispatch(owners.single(), device, _snapshot.value.generation) to null
        }
        val immediate = dispatch.second
        if (immediate != null) return immediate
        val selected = dispatch.first ?: return DeviceControlWriteResult.Rejected("device_control_dispatch_missing")

        // Do not hold the manager lifecycle lock while waiting for hardware. Session-local writers
        // can now accept concurrent slider updates and coalesce by controlId. A route switch may
        // close the selected session concurrently; its generation/session gate then rejects safely.
        // Same dispatcher boundary as bind/probe/refresh: some vendor writers perform a
        // synchronous SET -> GET/readback before their first suspension point. Never let a
        // Compose/Main caller inherit that blocking native I/O.
        val result = withContext(Dispatchers.IO) {
            selected.owner.write(request)
        }
        lifecycleMutex.withLock {
            if (!closed && activeDevice == selected.device && _snapshot.value.generation == selected.generation) {
                recompute(selected.generation, selected.device)
            }
        }
        return when (result) {
            is DeviceControlWriteResult.Applied -> DeviceControlWriteResult.Applied(_snapshot.value)
            is DeviceControlWriteResult.Rejected -> result
            is DeviceControlWriteResult.Failed -> result
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // close() cannot suspend. Use an independent short-lived IO scope so PlayerController's
        // immediately-following scope.cancel() cannot abort GATT/USB session cleanup.
        collectors.forEach { it.cancel() }
        collectors = emptyList()
        val closing = synchronized(stateLock) { sessions.also { sessions = emptyList() } }
        activeDevice = null
        val generation = generationCounter.incrementAndGet()
        _snapshot.value = DeviceControlSnapshot(generation, null, message = "closed")
        closeScope.launch {
            closing.forEach { runCatching { it.close() } }
            closeScope.cancel()
        }
    }

    private suspend fun closeSessionsLocked() {
        collectors.forEach { it.cancel() }
        collectors = emptyList()
        val closing = synchronized(stateLock) { sessions.also { sessions = emptyList() } }
        closing.forEach { runCatching { it.close() } }
    }

    private fun recompute(generation: Long, device: DeviceControlDevice) {
        if (closed || activeDevice != device || _snapshot.value.generation != generation) return
        val states = synchronized(stateLock) { sessions.toList() }.map { it.snapshot.value }
            .filter { it.generation == generation }
        val capabilities = states.flatMap { it.capabilities }
            .distinctBy { it.id.value }
        val duplicateIds = states.flatMap { it.capabilities }
            .groupingBy { it.id.value }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        val probeState = when {
            capabilities.isNotEmpty() -> DeviceControlSnapshot.ProbeState.READY
            // A missing permission is an actionable gate and must not be hidden behind another
            // backend that happens to still be probing/reconnecting in parallel.
            states.any { it.probeState == DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED } ->
                DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED
            states.any { it.probeState == DeviceControlSnapshot.ProbeState.PROBING } ->
                DeviceControlSnapshot.ProbeState.PROBING
            states.any { it.probeState == DeviceControlSnapshot.ProbeState.RECONNECTING } ->
                DeviceControlSnapshot.ProbeState.RECONNECTING
            states.any { it.probeState == DeviceControlSnapshot.ProbeState.DISCONNECTED } ->
                DeviceControlSnapshot.ProbeState.DISCONNECTED
            states.isNotEmpty() && states.all { it.probeState == DeviceControlSnapshot.ProbeState.UNSUPPORTED } ->
                DeviceControlSnapshot.ProbeState.UNSUPPORTED
            states.any { it.probeState == DeviceControlSnapshot.ProbeState.ERROR } ->
                DeviceControlSnapshot.ProbeState.ERROR
            else -> DeviceControlSnapshot.ProbeState.UNSUPPORTED
        }
        val messages = buildList {
            states.mapNotNullTo(this) { it.message?.takeIf(String::isNotBlank) }
            if (duplicateIds.isNotEmpty()) add("duplicate_capability_ids=${duplicateIds.sorted().joinToString(",")}")
        }
        _snapshot.value = DeviceControlSnapshot(
            generation = generation,
            device = device,
            capabilities = capabilities,
            probeState = probeState,
            message = messages.distinct().takeIf { it.isNotEmpty() }?.joinToString("; "),
            diagnostics = states.flatMap { it.diagnostics }.distinct(),
        )
    }
}
