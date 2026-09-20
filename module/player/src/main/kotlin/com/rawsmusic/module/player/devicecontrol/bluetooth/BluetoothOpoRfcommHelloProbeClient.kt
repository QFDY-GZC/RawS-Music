package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * Bounded OPO RFCOMM stream + read-only capability probe.
 *
 * Enco Free4 has a model-scoped fast path: once the physically proven 079A RFCOMM bearer is known,
 * startup no longer waits for a passive window or for the full EQ topology before publishing usable
 * ANC/current-EQ state. The same socket stays alive and enriches Product ID / 0x8122 afterwards.
 *
 * Safety contract:
 * - connect only to an exact HeyMelody-whitelisted OPO RFCOMM service (079A or legacy 1107);
 * - send one known System HELLO;
 * - only after a real command=0x8100 seq=0x23 HELLO ACK, send explicit read-only QUERY packets;
 * - never send REGISTER/token and never send ANC/EQ SET commands.
 */
internal class BluetoothOpoRfcommHelloProbeClient(
    context: Context,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val passiveListenMs: Long = DEFAULT_PASSIVE_LISTEN_MS,
    private val responseTimeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    private val queryResponseMs: Long = DEFAULT_QUERY_RESPONSE_MS,
) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data class Ready(
            val observation: BluetoothOpoRfcommProtocol.StreamObservation?,
            val diagnostics: List<String>,
        ) : Result

        data object PermissionRequired : Result
    }

    private data class ReadWindow(
        val raw: List<ByteArray>,
        val frames: List<BluetoothOpoRfcommProtocol.Frame>,
    )

    suspend fun probe(
        device: BluetoothDevice,
        serviceUuid: UUID = BluetoothOpoRfcommProtocol.SERVICE_UUID,
        fastPath: Boolean = false,
        onProgress: (String) -> Unit = {},
        /** Called when a coherent partial/final observation is ready for UI mapping. */
        onObservation: (BluetoothOpoRfcommProtocol.StreamObservation) -> Unit = {},
    ): Result = coroutineScope {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return@coroutineScope Result.PermissionRequired
        }

        val diagnostics = mutableListOf<String>()
        fun publish(line: String) {
            diagnostics += line
            onProgress(line)
        }

        publish(
            "bt.opo.rfcomm readonly_probe service=${serviceUuid}" +
                " connectTimeoutMs=$connectTimeoutMs passiveListenMs=${if (fastPath) 0 else passiveListenMs}" +
                " helloAckTimeoutMs=$responseTimeoutMs queryResponseBaseMs=$queryResponseMs" +
                " fastPath=$fastPath register=false setters=false",
        )

        val socket = runCatching {
            device.createRfcommSocketToServiceRecord(serviceUuid)
        }.getOrElse { error ->
            publish("bt.opo.rfcomm readonly_probe socket_create_failed=${safeErrorName(error)}")
            return@coroutineScope Result.Ready(null, diagnostics)
        }

        val connectJob = async(Dispatchers.IO) { runCatching { socket.connect() } }
        var readerJob: kotlinx.coroutines.Job? = null
        try {
            val connectOutcome = withTimeoutOrNull(connectTimeoutMs) { connectJob.await() }
            if (connectOutcome == null) {
                closeQuietly(socket)
                connectJob.cancelAndJoin()
                publish("bt.opo.rfcomm readonly_probe connect_timeout")
                return@coroutineScope Result.Ready(null, diagnostics)
            }
            val connectError = connectOutcome.exceptionOrNull()
            if (connectError != null || !runCatching { socket.isConnected }.getOrDefault(true)) {
                publish("bt.opo.rfcomm readonly_probe connect_failed=${safeErrorName(connectError)}")
                return@coroutineScope Result.Ready(null, diagnostics)
            }
            publish("bt.opo.rfcomm readonly_probe connected=true")

            val chunks = Channel<ByteArray>(Channel.UNLIMITED)
            readerJob = launch(Dispatchers.IO) {
                val buffer = ByteArray(MAX_READ_BYTES)
                try {
                    while (isActive) {
                        val count = socket.inputStream.read(buffer)
                        if (count <= 0) break
                        chunks.trySend(buffer.copyOf(count))
                    }
                } catch (_: Throwable) {
                    // Closing BluetoothSocket is the expected abort primitive for InputStream.read().
                } finally {
                    chunks.close()
                }
            }

            val frameDecoder = BluetoothOpoRfcommProtocol.FrameDecoder()

            val passiveRaw = if (fastPath) {
                publish("bt.opo.rfcomm passive_listen skipped=proven_enco_free4_fast_path")
                emptyList()
            } else {
                publish("bt.opo.rfcomm passive_listen start durationMs=$passiveListenMs")
                collectWindow(chunks, passiveListenMs)
            }
            val passiveChunks = passiveRaw.map(BluetoothOpoRfcommProtocol::chunkFingerprint)
            val passiveFrames = feedFrames(frameDecoder, passiveRaw)
            publishChunkDiagnostics("passive", passiveChunks, ::publish)
            publishFrameDiagnostics("passive", passiveFrames, ::publish)

            val helloWrite = writePacket(socket, BluetoothOpoRfcommProtocol.HELLO)
            if (helloWrite.isFailure) {
                publish("bt.opo.rfcomm readonly_probe hello_write_failed=${safeErrorName(helloWrite.exceptionOrNull())}")
                val observation = BluetoothOpoRfcommProtocol.StreamObservation(
                    passiveChunks = passiveChunks,
                    postHelloChunks = emptyList(),
                    passiveFrames = passiveFrames,
                )
                onObservation(observation)
                return@coroutineScope Result.Ready(observation, diagnostics)
            }
            publish("bt.opo.rfcomm readonly_probe hello_write_ok bytes=${BluetoothOpoRfcommProtocol.HELLO.size}")

            // Do not burn the whole old 1800 ms window once the ACK is already in hand. RFCOMM is
            // a stream; consume until the complete HELLO ACK frame is decoded, then move on.
            publish("bt.opo.rfcomm hello_ack_wait start timeoutMs=$responseTimeoutMs")
            val postHello = collectUntilFrame(
                chunks = chunks,
                decoder = frameDecoder,
                durationMs = responseTimeoutMs,
                predicate = BluetoothOpoRfcommProtocol::isHelloAck,
            )
            val postHelloChunks = postHello.raw.map(BluetoothOpoRfcommProtocol::chunkFingerprint)
            val postHelloFrames = postHello.frames
            publishChunkDiagnostics("post_hello", postHelloChunks, ::publish)
            publishFrameDiagnostics("post_hello", postHelloFrames, ::publish)

            val helloAck = postHelloFrames.any(BluetoothOpoRfcommProtocol::isHelloAck)
            publish(
                "bt.opo.rfcomm hello_ack confirmed=$helloAck" +
                    " expectedCmd=0x8100 expectedSeq=0x23" +
                    " bufferedBytes=${frameDecoder.bufferedBytes()}",
            )

            val routedFrames = linkedMapOf<String, MutableList<BluetoothOpoRfcommProtocol.Frame>>()
            BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES.forEach { routedFrames[it.name] = mutableListOf() }
            val attemptedQueries = linkedSetOf<String>()

            fun buildObservation(): BluetoothOpoRfcommProtocol.StreamObservation {
                val observations = BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES
                    .filter { it.name in attemptedQueries }
                    .map { query ->
                        val owned = routedFrames.getValue(query.name).toList()
                        BluetoothOpoRfcommProtocol.QueryObservation(
                            name = query.name,
                            command = query.command,
                            expectedResponseCommand = query.expectedResponseCommand,
                            sequence = query.sequence,
                            frames = owned,
                            matchedResponses = owned.count(query::matches),
                        )
                    }
                return BluetoothOpoRfcommProtocol.StreamObservation(
                    passiveChunks = passiveChunks,
                    postHelloChunks = postHelloChunks,
                    passiveFrames = passiveFrames,
                    postHelloFrames = postHelloFrames,
                    queryObservations = observations,
                )
            }

            suspend fun executeQuery(
                query: BluetoothOpoRfcommProtocol.ReadOnlyQuery,
                timeoutOverrideMs: Long? = null,
            ) {
                val productQuery = BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES.first { it.name == "product_id" }
                val productId = routedFrames.getValue("product_id")
                    .firstOrNull(productQuery::matches)
                    ?.payload
                    ?.let(BluetoothOpoRfcommProtocol::parseProductId)
                val profile = BluetoothOpoDeviceProfiles.resolve(productId)
                val effectiveQuery = when (query.name) {
                    "feature_switches" -> {
                        val ids = profile?.let(BluetoothOpoHeyMelodyFeatureMap::supported)?.map { it.id }.orEmpty()
                        if (ids.isEmpty()) {
                            publish("bt.opo.rfcomm readonly_query name=${query.name} send=false reason=no_verified_profile_features productId=${productId ?: "unknown"}")
                            return
                        }
                        query.copy(payload = BluetoothOpoRfcommProtocol.buildFeatureSwitchQueryPayload(ids))
                    }
                    "headset_spatial" -> {
                        if (profile?.spatialTypes.isNullOrEmpty()) {
                            publish("bt.opo.rfcomm readonly_query name=${query.name} send=false reason=profile_not_supported productId=${productId ?: "unknown"}")
                            return
                        }
                        query
                    }
                    "prompt_volume" -> {
                        if (profile?.isFunctionEnabled("promptVolume") != true) {
                            publish("bt.opo.rfcomm readonly_query name=${query.name} send=false reason=profile_not_supported productId=${productId ?: "unknown"}")
                            return
                        }
                        query
                    }
                    else -> query
                }
                attemptedQueries += effectiveQuery.name
                val effectiveTimeoutMs = timeoutOverrideMs ?: maxOf(effectiveQuery.responseTimeoutMs, queryResponseMs)
                publish(
                    "bt.opo.rfcomm readonly_query name=${effectiveQuery.name} send=true" +
                        " cmd=0x${hexWord(effectiveQuery.command)}" +
                        " expectedResp=0x${hexWord(effectiveQuery.expectedResponseCommand)}" +
                        " seq=0x${hexByte(effectiveQuery.sequence)} timeoutMs=$effectiveTimeoutMs" +
                        " register=false",
                )
                val write = writePacket(socket, effectiveQuery.packet)
                if (write.isFailure) {
                    publish(
                        "bt.opo.rfcomm readonly_query name=${effectiveQuery.name}" +
                            " write_failed=${safeErrorName(write.exceptionOrNull())}",
                    )
                    return
                }
                publish("bt.opo.rfcomm readonly_query name=${effectiveQuery.name} write_ok bytes=${effectiveQuery.packet.size}")

                val raw = mutableListOf<ByteArray>()
                val stageFrames = mutableListOf<BluetoothOpoRfcommProtocol.Frame>()
                val deadlineNanos = System.nanoTime() + effectiveTimeoutMs * 1_000_000L
                var targetMatched = routedFrames.getValue(effectiveQuery.name).any(effectiveQuery::matches)
                while (!targetMatched && raw.size < MAX_CHUNKS && raw.sumOf { it.size } < MAX_TOTAL_BYTES) {
                    val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                    if (remainingMs <= 0L) break
                    val next = withTimeoutOrNull(remainingMs) { chunks.receiveCatching().getOrNull() } ?: break
                    raw += next
                    val decoded = frameDecoder.feed(next)
                    stageFrames += decoded
                    decoded.forEach { frame ->
                        val owner = BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES.firstOrNull { it.matches(frame) }
                        if (owner != null) {
                            routedFrames.getValue(owner.name) += frame
                            if (owner.name != effectiveQuery.name) {
                                publish(
                                    "bt.opo.rfcomm readonly_response_routed arrivedDuring=${effectiveQuery.name}" +
                                        " owner=${owner.name} cmd=0x${hexWord(frame.command ?: 0)}" +
                                        " seq=0x${hexByte(frame.sequence ?: 0)}",
                                )
                            }
                        }
                    }
                    targetMatched = routedFrames.getValue(effectiveQuery.name).any(effectiveQuery::matches)
                }

                publishChunkDiagnostics("query_${effectiveQuery.name}", raw.map(BluetoothOpoRfcommProtocol::chunkFingerprint), ::publish)
                publishFrameDiagnostics("query_${effectiveQuery.name}", stageFrames, ::publish)
                val matchedNow = routedFrames.getValue(effectiveQuery.name).count(effectiveQuery::matches)
                publish(
                    "bt.opo.rfcomm readonly_query_result name=${effectiveQuery.name}" +
                        " matched=$matchedNow frames=${stageFrames.size}" +
                        " timedOut=${!targetMatched}" +
                        " bufferedBytes=${frameDecoder.bufferedBytes()}",
                )
            }

            var finalObservation = buildObservation()
            if (helloAck) {
                val byName = BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES.associateBy { it.name }

                if (fastPath) {
                    // Stage A: only controls required to paint the Hardware DSP page. Publish READY
                    // before slower model/topology enrichment. Capability remains first because its
                    // response is useful for later write gating.
                    executeQuery(byName.getValue("capability"), FAST_CONTROL_QUERY_TIMEOUT_MS)
                    executeQuery(byName.getValue("anc"), FAST_CONTROL_QUERY_TIMEOUT_MS)
                    executeQuery(byName.getValue("eq_current"), FAST_CONTROL_QUERY_TIMEOUT_MS)
                    finalObservation = buildObservation()
                    publish(
                        "bt.opo.rfcomm fast_ready checkpoint=true" +
                            " matchedReadResponses=${finalObservation.matchedReadOnlyResponses}",
                    )
                    onObservation(finalObservation)

                    // Stage B: enrich the same live session. Product ID locks the exact HeyMelody
                    // profile, version unlocks firmware-gated preset entries, and action=2/type=1
                    // returns the current firmware's ANC support bitmap. 0x8122 may legitimately
                    // return count=0 on Free4 because its custom-EQ topology is app-profile-driven.
                    executeQuery(byName.getValue("product_id"))
                    executeQuery(byName.getValue("version"))
                    executeQuery(byName.getValue("anc_support"))
                    executeQuery(byName.getValue("eq_all"))
                    executeQuery(byName.getValue("feature_switches"))
                    executeQuery(byName.getValue("headset_spatial"))
                    executeQuery(byName.getValue("prompt_volume"))
                    finalObservation = buildObservation()
                    onObservation(finalObservation)
                    publish("bt.opo.rfcomm fast_ready enrichment_complete=true")
                } else {
                    BluetoothOpoRfcommProtocol.READ_ONLY_QUERIES.forEach { executeQuery(it) }
                    finalObservation = buildObservation()
                    onObservation(finalObservation)
                }

                attemptedQueries.forEach { name ->
                    val query = byName.getValue(name)
                    val owned = routedFrames.getValue(name).toList()
                    publish(
                        "bt.opo.rfcomm readonly_query_final name=$name" +
                            " matched=${owned.count(query::matches)} routedFrames=${owned.size}",
                    )
                }
            } else {
                publish("bt.opo.rfcomm readonly_queries skipped=no_confirmed_hello_ack")
                onObservation(finalObservation)
            }

            publish(
                "bt.opo.rfcomm readonly_summary" +
                    " passiveFrames=${passiveFrames.size}" +
                    " postHelloFrames=${postHelloFrames.size}" +
                    " helloAck=${finalObservation.helloAckConfirmed}" +
                    " queries=${finalObservation.queryObservations.size}" +
                    " matchedReadResponses=${finalObservation.matchedReadOnlyResponses}" +
                    " bufferedBytes=${frameDecoder.bufferedBytes()}",
            )
            Result.Ready(finalObservation, diagnostics)
        } finally {
            closeQuietly(socket)
            if (connectJob.isActive) connectJob.cancel()
            readerJob?.cancel()
        }
    }

    private suspend fun writePacket(socket: BluetoothSocket, packet: ByteArray): kotlin.Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                socket.outputStream.write(packet)
                socket.outputStream.flush()
            }
        }

    private fun feedFrames(
        decoder: BluetoothOpoRfcommProtocol.FrameDecoder,
        chunks: List<ByteArray>,
    ): List<BluetoothOpoRfcommProtocol.Frame> = buildList {
        chunks.forEach { addAll(decoder.feed(it)) }
    }

    private suspend fun collectWindow(
        chunks: Channel<ByteArray>,
        durationMs: Long,
    ): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var totalBytes = 0
        val deadlineNanos = System.nanoTime() + durationMs * 1_000_000L
        while (result.size < MAX_CHUNKS && totalBytes < MAX_TOTAL_BYTES) {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            val next = withTimeoutOrNull(remainingMs) { chunks.receiveCatching().getOrNull() } ?: break
            result += next
            totalBytes += next.size
        }
        return result
    }

    private suspend fun collectUntilFrame(
        chunks: Channel<ByteArray>,
        decoder: BluetoothOpoRfcommProtocol.FrameDecoder,
        durationMs: Long,
        predicate: (BluetoothOpoRfcommProtocol.Frame) -> Boolean,
    ): ReadWindow {
        val raw = mutableListOf<ByteArray>()
        val frames = mutableListOf<BluetoothOpoRfcommProtocol.Frame>()
        var totalBytes = 0
        val deadlineNanos = System.nanoTime() + durationMs * 1_000_000L
        while (raw.size < MAX_CHUNKS && totalBytes < MAX_TOTAL_BYTES && frames.none(predicate)) {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            val next = withTimeoutOrNull(remainingMs) { chunks.receiveCatching().getOrNull() } ?: break
            raw += next
            totalBytes += next.size
            frames += decoder.feed(next)
        }
        return ReadWindow(raw, frames)
    }

    private fun publishChunkDiagnostics(
        stage: String,
        chunks: List<BluetoothOpoRfcommProtocol.ChunkFingerprint>,
        publish: (String) -> Unit,
    ) {
        if (chunks.isEmpty()) {
            publish("bt.opo.rfcomm $stage chunks=0")
            return
        }
        chunks.forEachIndexed { chunkIndex, chunk ->
            publish(
                "bt.opo.rfcomm $stage chunk=$chunkIndex bytes=${chunk.bytes}" +
                    " sha256Prefix=${chunk.sha256Prefix} candidates=${chunk.candidates.size}",
            )
            chunk.candidates.forEachIndexed { candidateIndex, candidate ->
                publish(
                    buildString {
                        append("bt.opo.rfcomm candidate stage=").append(stage)
                        append(" chunk=").append(chunkIndex)
                        append(" index=").append(candidateIndex)
                        append(" offset=").append(candidate.offset)
                        candidate.declaredLength?.let { append(" declaredLen=").append(it) }
                        candidate.command?.let { append(" cmd=0x").append(hexWord(it)) }
                        candidate.sequence?.let { append(" seq=0x").append(hexByte(it)) }
                    },
                )
            }
        }
    }

    private fun publishFrameDiagnostics(
        stage: String,
        frames: List<BluetoothOpoRfcommProtocol.Frame>,
        publish: (String) -> Unit,
    ) {
        if (frames.isEmpty()) {
            publish("bt.opo.rfcomm frames stage=$stage count=0")
            return
        }
        frames.forEachIndexed { index, frame ->
            publish(
                buildString {
                    append("bt.opo.rfcomm frame stage=").append(stage)
                    append(" index=").append(index)
                    append(" bytes=").append(frame.bytes)
                    append(" declaredLen=").append(frame.declaredLength)
                    frame.command?.let { append(" cmd=0x").append(hexWord(it)) }
                    frame.sequence?.let { append(" seq=0x").append(hexByte(it)) }
                    frame.declaredPayloadLength?.let { append(" payloadLen=").append(it) }
                    append(" payloadBytes=").append(frame.dataBytes)
                    if (frame.payloadTruncated) append(" payloadTruncated=true")
                    append(" sha256Prefix=").append(frame.sha256Prefix)
                    frame.controlDataHex?.let { append(" controlDataHex=").append(it) }
                },
            )
        }
    }

    private suspend fun closeQuietly(socket: BluetoothSocket) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { socket.close() }
        }
    }

    private fun safeErrorName(error: Throwable?): String =
        error?.javaClass?.simpleName?.takeIf { it.isNotBlank() } ?: "failed"

    private fun hexByte(value: Int): String = value.toString(16).padStart(2, '0')
    private fun hexWord(value: Int): String = value.toString(16).padStart(4, '0')

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 3_500L
        private const val DEFAULT_PASSIVE_LISTEN_MS = 650L
        private const val DEFAULT_RESPONSE_TIMEOUT_MS = 1_800L
        private const val DEFAULT_QUERY_RESPONSE_MS = 1_250L
        private const val FAST_CONTROL_QUERY_TIMEOUT_MS = 1_250L
        private const val MAX_READ_BYTES = 512
        private const val MAX_CHUNKS = 24
        private const val MAX_TOTAL_BYTES = 8 * 1024
    }
}
