package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Serialized one-shot OPO writer for commands whose exact HeyMelody 16.9.1 semantics are proven.
 * Every write performs SET ACK validation followed by authoritative GET readback before success.
 * No REGISTER/token is sent.
 */
internal class BluetoothOpoRfcommControlClient(
    context: Context,
    private val connectTimeoutMs: Long = 3_500L,
    private val responseTimeoutMs: Long = 1_800L,
) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data class Applied(
            val currentAncProtocolIndex: Int? = null,
            val currentEqId: Int? = null,
            val customEqEntry: BluetoothOpoRfcommProtocol.EqInfoEntry? = null,
            val featureSwitchId: Int? = null,
            val featureSwitchStatus: Int? = null,
            val headsetSpatialType: Int? = null,
            val promptVolumeValue: Int? = null,
            val diagnostics: List<String>,
        ) : Result

        data class Rejected(val reason: String, val diagnostics: List<String> = emptyList()) : Result
        data object PermissionRequired : Result
    }

    suspend fun setAncMode(device: BluetoothDevice, protocolIndex: Int, serviceUuid: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID): Result {
        if (protocolIndex !in 0 until 32) return Result.Rejected("invalid_anc_protocol_index")
        val expected = protocolIndex
        return execute(
            device = device,
            serviceUuid = serviceUuid,
            label = "anc",
            setCommand = BluetoothOpoRfcommProtocol.CMD_SET_ANC,
            setPayload = BluetoothOpoRfcommProtocol.buildAncModePayload(protocolIndex),
            readCommand = BluetoothOpoRfcommProtocol.CMD_QUERY_ANC,
            readPayload = byteArrayOf(0x01, 0x01),
            readResponseCommand = BluetoothOpoRfcommProtocol.CMD_ANC_RESP,
            decodeReadback = { frame ->
                val actual = BluetoothOpoRfcommProtocol.parseCurrentAncProtocolIndex(frame.payload)
                    ?: return@execute null
                if (actual != expected) null else Readback(ancProtocolIndex = actual)
            },
        )
    }

    suspend fun setEqPreset(device: BluetoothDevice, eqId: Int, serviceUuid: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID): Result {
        if (eqId !in 0..0xff) return Result.Rejected("invalid_eq_preset")
        val expected = eqId
        return execute(
            device = device,
            serviceUuid = serviceUuid,
            label = "eq_preset",
            setCommand = BluetoothOpoRfcommProtocol.CMD_SET_EQ_PRESET,
            setPayload = BluetoothOpoRfcommProtocol.buildEqPresetPayload(eqId),
            readCommand = BluetoothOpoRfcommProtocol.CMD_QUERY_EQ_CURRENT,
            readPayload = byteArrayOf(),
            readResponseCommand = BluetoothOpoRfcommProtocol.CMD_EQ_CURRENT_RESP,
            decodeReadback = { frame ->
                val actual = BluetoothOpoRfcommProtocol.parseCurrentEqId(frame.payload)
                    ?: return@execute null
                if (actual != expected) null else Readback(eqId = actual)
            },
        )
    }


    /**
     * HeyMelody custom-EQ lifecycle. action=1 creates a device-side entry with eqId=0; the
     * authoritative 0x8122 readback supplies the assigned ID. action=2 updates and applies an
     * existing entry. If CREATE returns an unselected entry, follow with the same official
     * action=2 flow so the newly created curve becomes the active hardware EQ.
     */
    suspend fun setCustomEq(
        device: BluetoothDevice,
        action: Int,
        minGainDb: Int,
        maxGainDb: Int,
        eqId: Int,
        name: String,
        frequenciesHz: List<Int>,
        gainsDb: List<Int>,
        serviceUuid: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID,
    ): Result {
        if (action !in 1..2) return Result.Rejected("invalid_custom_eq_action")
        if (frequenciesHz.isEmpty() || frequenciesHz.size != gainsDb.size) {
            return Result.Rejected("invalid_custom_eq_bands")
        }
        val first = setCustomEqOnce(
            device = device,
            action = action,
            minGainDb = minGainDb,
            maxGainDb = maxGainDb,
            eqId = if (action == 1) 0 else eqId,
            name = name,
            frequenciesHz = frequenciesHz,
            gainsDb = gainsDb,
            serviceUuid = serviceUuid,
        )
        if (action != 1 || first !is Result.Applied) return first
        val created = first.customEqEntry ?: return Result.Rejected("custom_eq_create_readback_missing", first.diagnostics)
        if (created.isSelected) return first
        val applied = setCustomEqOnce(
            device = device,
            action = 2,
            minGainDb = created.minGainDb,
            maxGainDb = created.maxGainDb,
            eqId = created.eqId,
            name = created.name.ifEmpty { name },
            frequenciesHz = created.frequenciesHz,
            gainsDb = created.gainsDb,
            serviceUuid = serviceUuid,
        )
        return when (applied) {
            is Result.Applied -> applied.copy(diagnostics = first.diagnostics + applied.diagnostics +
                "bt.opo.custom_eq createThenApply=true assignedEqId=${created.eqId}")
            is Result.Rejected -> applied.copy(diagnostics = first.diagnostics + applied.diagnostics)
            Result.PermissionRequired -> Result.PermissionRequired
        }
    }

    private suspend fun setCustomEqOnce(
        device: BluetoothDevice,
        action: Int,
        minGainDb: Int,
        maxGainDb: Int,
        eqId: Int,
        name: String,
        frequenciesHz: List<Int>,
        gainsDb: List<Int>,
        serviceUuid: UUID,
    ): Result {
        val expectedName = name.trim()
        return execute(
            device = device,
            serviceUuid = serviceUuid,
            label = "custom_eq_action_$action",
            setCommand = BluetoothOpoRfcommProtocol.CMD_SET_EQ_DETAIL,
            setPayload = BluetoothOpoRfcommProtocol.buildEqDetailPayload(
                action = action,
                minGainDb = minGainDb,
                maxGainDb = maxGainDb,
                eqId = eqId,
                name = expectedName,
                frequenciesHz = frequenciesHz,
                gainsDb = gainsDb,
            ),
            readCommand = BluetoothOpoRfcommProtocol.CMD_QUERY_EQ_ALL,
            readPayload = byteArrayOf(0x01, 0x05),
            readResponseCommand = BluetoothOpoRfcommProtocol.CMD_EQ_ALL_RESP,
            readTimeoutMs = 3_500L,
            decodeReadback = { frame ->
                val decoded = BluetoothOpoRfcommProtocol.parseEqAllPayload(frame.payload)
                if (decoded.error != null) return@execute null
                val candidates = decoded.entries.filter { entry ->
                    entry.frequenciesHz == frequenciesHz &&
                        entry.gainsDb == gainsDb &&
                        entry.minGainDb == minGainDb &&
                        entry.maxGainDb == maxGainDb
                }
                val matched = if (action == 1) {
                    candidates.lastOrNull { it.name.trim() == expectedName } ?: candidates.lastOrNull()
                } else {
                    candidates.firstOrNull { it.eqId == eqId }
                } ?: return@execute null
                if (action == 2 && !matched.isSelected) return@execute null
                Readback(eqId = matched.eqId, customEqEntry = matched)
            },
        )
    }

    suspend fun setFeatureSwitch(
        device: BluetoothDevice,
        featureId: Int,
        enabled: Boolean,
        serviceUuid: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID,
    ): Result {
        if (featureId !in 0..0xff) return Result.Rejected("invalid_feature_switch_id")
        val expected = if (enabled) 1 else 0
        return execute(
            device = device,
            serviceUuid = serviceUuid,
            label = "feature_switch_$featureId",
            setCommand = BluetoothOpoRfcommProtocol.CMD_SET_FEATURE_SWITCH,
            setPayload = BluetoothOpoRfcommProtocol.buildFeatureSwitchPayload(featureId, enabled),
            readCommand = BluetoothOpoRfcommProtocol.CMD_QUERY_FEATURE_SWITCH,
            readPayload = BluetoothOpoRfcommProtocol.buildFeatureSwitchQueryPayload(listOf(featureId)),
            readResponseCommand = BluetoothOpoRfcommProtocol.CMD_FEATURE_SWITCH_RESP,
            decodeReadback = { frame ->
                val actual = BluetoothOpoRfcommProtocol.parseFeatureSwitchStatuses(frame.payload)[featureId]
                    ?: return@execute null
                if (actual != expected) null else Readback(featureSwitchId = featureId, featureSwitchStatus = actual)
            },
        )
    }

    suspend fun setHeadsetSpatialType(
        device: BluetoothDevice,
        type: Int,
        serviceUuid: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID,
    ): Result {
        if (type !in 0..0xff) return Result.Rejected("invalid_headset_spatial_type")
        return execute(
            device = device,
            serviceUuid = serviceUuid,
            label = "headset_spatial_type",
            setCommand = BluetoothOpoRfcommProtocol.CMD_SET_HEADSET_SPATIAL,
            setPayload = BluetoothOpoRfcommProtocol.buildHeadsetSpatialPayload(type),
            readCommand = BluetoothOpoRfcommProtocol.CMD_QUERY_HEADSET_SPATIAL,
            readPayload = byteArrayOf(),
            readResponseCommand = BluetoothOpoRfcommProtocol.CMD_HEADSET_SPATIAL_RESP,
            decodeReadback = { frame ->
                val actual = BluetoothOpoRfcommProtocol.parseHeadsetSpatialType(frame.payload)
                    ?: return@execute null
                if (actual != type) null else Readback(headsetSpatialType = actual)
            },
        )
    }


    suspend fun setPromptVolume(
        device: BluetoothDevice,
        value: Int,
        serviceUuid: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID,
    ): Result {
        if (value !in 0..0xff) return Result.Rejected("invalid_prompt_volume")
        return execute(
            device = device,
            serviceUuid = serviceUuid,
            label = "prompt_volume",
            setCommand = BluetoothOpoRfcommProtocol.CMD_SET_PROMPT_VOLUME,
            setPayload = BluetoothOpoRfcommProtocol.buildPromptVolumePayload(value),
            readCommand = BluetoothOpoRfcommProtocol.CMD_QUERY_PROMPT_VOLUME,
            readPayload = byteArrayOf(),
            readResponseCommand = BluetoothOpoRfcommProtocol.CMD_PROMPT_VOLUME_RESP,
            decodeReadback = { frame ->
                val actual = BluetoothOpoRfcommProtocol.parsePromptVolumeValue(frame.payload)
                    ?: return@execute null
                if (actual != value) null else Readback(promptVolumeValue = actual)
            },
        )
    }

    private data class Readback(
        val ancProtocolIndex: Int? = null,
        val eqId: Int? = null,
        val customEqEntry: BluetoothOpoRfcommProtocol.EqInfoEntry? = null,
        val featureSwitchId: Int? = null,
        val featureSwitchStatus: Int? = null,
        val headsetSpatialType: Int? = null,
        val promptVolumeValue: Int? = null,
    )

    private suspend fun execute(
        device: BluetoothDevice,
        serviceUuid: UUID,
        label: String,
        setCommand: Int,
        setPayload: ByteArray,
        readCommand: Int,
        readPayload: ByteArray,
        readResponseCommand: Int,
        readTimeoutMs: Long = responseTimeoutMs,
        decodeReadback: (BluetoothOpoRfcommProtocol.Frame) -> Readback?,
    ): Result = coroutineScope {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return@coroutineScope Result.PermissionRequired
        }

        val diagnostics = mutableListOf<String>()
        fun diag(value: String) { diagnostics += value }
        diag("bt.opo.write label=$label service=$serviceUuid register=false")

        val socket = runCatching {
            device.createRfcommSocketToServiceRecord(serviceUuid)
        }.getOrElse { error ->
            return@coroutineScope Result.Rejected("rfcomm_socket_create_${safeErrorName(error)}", diagnostics)
        }
        val connectJob = async(Dispatchers.IO) { runCatching { socket.connect() } }
        var readerJob: kotlinx.coroutines.Job? = null
        try {
            val connect = withTimeoutOrNull(connectTimeoutMs) { connectJob.await() }
            if (connect == null) {
                closeQuietly(socket)
                connectJob.cancelAndJoin()
                return@coroutineScope Result.Rejected("rfcomm_connect_timeout", diagnostics)
            }
            connect.exceptionOrNull()?.let { error ->
                return@coroutineScope Result.Rejected("rfcomm_connect_${safeErrorName(error)}", diagnostics)
            }
            diag("bt.opo.write connected=true label=$label")

            val chunks = Channel<ByteArray>(Channel.UNLIMITED)
            readerJob = launch(Dispatchers.IO) {
                val buffer = ByteArray(512)
                try {
                    while (isActive) {
                        val count = socket.inputStream.read(buffer)
                        if (count <= 0) break
                        chunks.trySend(buffer.copyOf(count))
                    }
                } catch (_: Throwable) {
                    // BluetoothSocket.close() aborts blocking reads during normal cleanup.
                } finally {
                    chunks.close()
                }
            }
            val decoder = BluetoothOpoRfcommProtocol.FrameDecoder()

            if (writePacket(socket, BluetoothOpoRfcommProtocol.HELLO).isFailure) {
                return@coroutineScope Result.Rejected("hello_write_failed", diagnostics)
            }
            val hello = waitForFrame(chunks, decoder, responseTimeoutMs, BluetoothOpoRfcommProtocol::isHelloAck)
                ?: return@coroutineScope Result.Rejected("hello_ack_timeout", diagnostics)
            diag("bt.opo.write helloAck=true cmd=0x${hello.command?.toString(16)}")

            val setSequence = 0x40
            val setPacket = BluetoothOpoRfcommProtocol.buildPacket(setCommand, setSequence, setPayload)
            if (writePacket(socket, setPacket).isFailure) {
                return@coroutineScope Result.Rejected("set_write_failed", diagnostics)
            }
            val setResponseCommand = setCommand or 0x8000
            val ack = waitForFrame(chunks, decoder, responseTimeoutMs) { frame ->
                frame.command == setResponseCommand && frame.sequence == setSequence
            } ?: return@coroutineScope Result.Rejected("set_ack_timeout", diagnostics)
            val status = ack.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
            if (status != 0) {
                diag("bt.opo.write setAck=false cmd=0x${setResponseCommand.toString(16)} status=$status")
                return@coroutineScope Result.Rejected("set_status_$status", diagnostics)
            }
            diag("bt.opo.write setAck=true cmd=0x${setResponseCommand.toString(16)}")

            val readSequence = 0x41
            val readPacket = BluetoothOpoRfcommProtocol.buildPacket(readCommand, readSequence, readPayload)
            if (writePacket(socket, readPacket).isFailure) {
                return@coroutineScope Result.Rejected("readback_write_failed", diagnostics)
            }
            val readFrame = waitForFrame(chunks, decoder, readTimeoutMs) { frame ->
                frame.command == readResponseCommand && frame.sequence == readSequence
            } ?: return@coroutineScope Result.Rejected("readback_timeout", diagnostics)
            val readback = decodeReadback(readFrame)
                ?: return@coroutineScope Result.Rejected("readback_mismatch", diagnostics)
            diag("bt.opo.write readback=true cmd=0x${readResponseCommand.toString(16)}")
            Result.Applied(
                currentAncProtocolIndex = readback.ancProtocolIndex,
                currentEqId = readback.eqId,
                customEqEntry = readback.customEqEntry,
                featureSwitchId = readback.featureSwitchId,
                featureSwitchStatus = readback.featureSwitchStatus,
                headsetSpatialType = readback.headsetSpatialType,
                promptVolumeValue = readback.promptVolumeValue,
                diagnostics = diagnostics,
            )
        } finally {
            closeQuietly(socket)
            readerJob?.cancel()
            if (connectJob.isActive) connectJob.cancel()
        }
    }

    private suspend fun waitForFrame(
        chunks: Channel<ByteArray>,
        decoder: BluetoothOpoRfcommProtocol.FrameDecoder,
        timeoutMs: Long,
        predicate: (BluetoothOpoRfcommProtocol.Frame) -> Boolean,
    ): BluetoothOpoRfcommProtocol.Frame? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingMs <= 0) return null
            val chunk = withTimeoutOrNull(remainingMs) { chunks.receiveCatching().getOrNull() } ?: return null
            decoder.feed(chunk).firstOrNull(predicate)?.let { return it }
        }
    }

    private suspend fun writePacket(socket: BluetoothSocket, packet: ByteArray): kotlin.Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                socket.outputStream.write(packet)
                socket.outputStream.flush()
            }
        }

    private fun closeQuietly(socket: BluetoothSocket?) {
        runCatching { socket?.close() }
    }

    private fun safeErrorName(error: Throwable?): String = error?.javaClass?.simpleName ?: "unknown"
}
