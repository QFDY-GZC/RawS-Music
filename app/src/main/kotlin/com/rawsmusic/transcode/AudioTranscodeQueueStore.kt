package com.rawsmusic.transcode

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, R8-stable persistence for [AudioTranscodeQueue].
 *
 * The JSON schema is written explicitly rather than reflecting Kotlin data classes so queue files
 * remain readable across release minification and ordinary model refactors. Result payloads are
 * intentionally reduced to a terminal summary; a conversion's full verification/metadata report
 * is an execution detail, while the durable queue needs request, order and user-visible outcome.
 */
internal class AudioTranscodeQueueStore(context: Context) {
    private val atomicFile = AtomicFile(
        File(context.applicationContext.filesDir, FILE_NAME),
    )
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS-TranscodeQueuePersist").apply {
            priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
            isDaemon = true
        }
    }
    @Volatile private var writesEnabled = true

    fun load(): List<AudioTranscodeQueueEntry> {
        val bytes = runCatching {
            atomicFile.openRead().use { it.readBytes() }
        }.getOrNull() ?: return emptyList()
        if (bytes.isEmpty()) return emptyList()
        return try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.optInt(KEY_VERSION, -1) != SCHEMA_VERSION) {
                writesEnabled = false
                return emptyList()
            }
            val array = root.optJSONArray(KEY_ENTRIES) ?: run {
                writesEnabled = false
                return emptyList()
            }
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    parseEntry(array.optJSONObject(index))?.let(::add)
                }
            }
        } catch (_: Throwable) {
            writesEnabled = false
            emptyList()
        }
    }

    fun scheduleWrite(entries: List<AudioTranscodeQueueEntry>) {
        if (!writesEnabled) return
        val payload = serialize(entries).toByteArray(Charsets.UTF_8)
        writer.execute { writeAtomic(payload) }
    }

    private fun writeAtomic(payload: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(payload)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            stream = null
        } catch (_: Throwable) {
            stream?.let(atomicFile::failWrite)
        }
    }

    private fun serialize(entries: List<AudioTranscodeQueueEntry>): String {
        val array = JSONArray()
        entries.forEach { array.put(serializeEntry(it)) }
        return JSONObject()
            .put(KEY_VERSION, SCHEMA_VERSION)
            .put(KEY_ENTRIES, array)
            .toString()
    }

    private fun serializeEntry(entry: AudioTranscodeQueueEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("request", serializeRequest(entry.request))
        .put("state", entry.state.name)
        .put("progress", serializeProgress(entry.progress))
        .put("summary", entry.terminalSummary?.let(::serializeSummary) ?: JSONObject.NULL)
        .put("enqueuedAtMs", entry.enqueuedAtMs)
        .put("startedAtMs", entry.startedAtMs ?: JSONObject.NULL)
        .put("finishedAtMs", entry.finishedAtMs ?: JSONObject.NULL)

    private fun serializeRequest(request: AudioTranscodeRequest): JSONObject = JSONObject()
        .put("inputPath", request.inputPath)
        .put("outputPath", request.outputPath)
        .put("format", request.format.name)
        .put("targetSampleRateHz", request.targetSampleRateHz ?: JSONObject.NULL)
        .put("targetBitDepth", request.targetBitDepth ?: JSONObject.NULL)
        .put("dsdRate", request.dsdRate.name)
        .put("flacCompressionLevel", request.flacCompressionLevel)
        .put("targetBitRateKbps", request.targetBitRateKbps ?: JSONObject.NULL)
        .put("ditherPolicy", request.ditherPolicy.name)
        .put("metadataPolicy", request.metadataPolicy.name)
        .put("verificationMode", request.verificationMode.name)
        .put("hardwareAccelerationEnabled", request.hardwareAccelerationEnabled)
        .put("deleteSourceOnSuccess", request.deleteSourceOnSuccess)
        .put("autoScanOnSuccess", request.autoScanOnSuccess)
        .put("overwrite", request.overwrite)

    private fun serializeProgress(progress: AudioTranscodeProgress): JSONObject = JSONObject()
        .put("stage", progress.stage.name)
        .put("stagePermille", progress.stagePermille)
        .put("overallPermille", progress.overallPermille)

    private fun serializeSummary(summary: AudioTranscodeQueueTerminalSummary): JSONObject = JSONObject()
        .put("outputPath", summary.outputPath ?: JSONObject.NULL)
        .put("failureReason", summary.failureReason?.name ?: JSONObject.NULL)
        .put("detail", summary.detail ?: JSONObject.NULL)
        .put("nativeCode", summary.nativeCode ?: JSONObject.NULL)
        .put("backend", summary.backend?.let(::serializeBackend) ?: JSONObject.NULL)
        .put("postActionDetail", summary.postActionDetail ?: JSONObject.NULL)
        .put("sourceDeleted", summary.sourceDeleted ?: JSONObject.NULL)
        .put("sourceDeleteNeedsConsent", summary.sourceDeleteNeedsConsent)
        .put("autoScanRequested", summary.autoScanRequested)

    private fun serializeBackend(backend: AudioTranscodeBackendInfo): JSONObject = JSONObject()
        .put("kind", backend.kind.name)
        .put("name", backend.name)
        .put("hardwareRequested", backend.hardwareRequested)
        .put("fallbackReason", backend.fallbackReason ?: JSONObject.NULL)

    private fun parseEntry(json: JSONObject?): AudioTranscodeQueueEntry? {
        json ?: return null
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        val request = parseRequest(json.optJSONObject("request")) ?: return null
        val state = enumValueOrNull<AudioTranscodeQueueState>(json.optString("state")) ?: return null
        val progress = parseProgress(json.optJSONObject("progress")) ?: AudioTranscodeProgress(
            stage = when (state) {
                AudioTranscodeQueueState.QUEUED -> AudioTranscodeStage.QUEUED
                AudioTranscodeQueueState.RUNNING -> AudioTranscodeStage.ENCODING
                AudioTranscodeQueueState.COMPLETED -> AudioTranscodeStage.COMPLETED
                AudioTranscodeQueueState.FAILED -> AudioTranscodeStage.FAILED
                AudioTranscodeQueueState.CANCELLED -> AudioTranscodeStage.CANCELLED
            },
            stagePermille = 0,
            overallPermille = 0,
        )
        return AudioTranscodeQueueEntry(
            id = id,
            request = request,
            state = state,
            progress = progress,
            result = null,
            terminalSummary = parseSummary(json.optJSONObject("summary")),
            enqueuedAtMs = json.optLong("enqueuedAtMs", System.currentTimeMillis()),
            startedAtMs = json.optNullableLong("startedAtMs"),
            finishedAtMs = json.optNullableLong("finishedAtMs"),
        )
    }

    private fun parseRequest(json: JSONObject?): AudioTranscodeRequest? {
        json ?: return null
        val inputPath = json.optString("inputPath").takeIf(String::isNotBlank) ?: return null
        val outputPath = json.optString("outputPath").takeIf(String::isNotBlank) ?: return null
        return AudioTranscodeRequest(
            inputPath = inputPath,
            outputPath = outputPath,
            format = enumValueOrNull<AudioTranscodeFormat>(json.optString("format")) ?: return null,
            targetSampleRateHz = json.optNullableInt("targetSampleRateHz"),
            targetBitDepth = json.optNullableInt("targetBitDepth"),
            dsdRate = enumValueOrNull<AudioTranscodeDsdRate>(json.optString("dsdRate"))
                ?: AudioTranscodeDsdRate.DSD64,
            flacCompressionLevel = json.optInt("flacCompressionLevel", 8),
            targetBitRateKbps = json.optNullableInt("targetBitRateKbps"),
            ditherPolicy = enumValueOrNull<AudioTranscodeDitherPolicy>(json.optString("ditherPolicy"))
                ?: AudioTranscodeDitherPolicy.AUTO,
            metadataPolicy = enumValueOrNull<AudioTranscodeMetadataPolicy>(json.optString("metadataPolicy"))
                ?: AudioTranscodeMetadataPolicy.STRICT,
            verificationMode = enumValueOrNull<AudioTranscodeVerificationMode>(json.optString("verificationMode"))
                ?: AudioTranscodeVerificationMode.AUTO,
            hardwareAccelerationEnabled = json.optBoolean("hardwareAccelerationEnabled", false),
            deleteSourceOnSuccess = json.optBoolean("deleteSourceOnSuccess", false),
            autoScanOnSuccess = json.optBoolean("autoScanOnSuccess", false),
            overwrite = json.optBoolean("overwrite", false),
        )
    }

    private fun parseProgress(json: JSONObject?): AudioTranscodeProgress? {
        json ?: return null
        return AudioTranscodeProgress(
            stage = enumValueOrNull<AudioTranscodeStage>(json.optString("stage")) ?: return null,
            stagePermille = json.optInt("stagePermille", 0).coerceIn(0, 1000),
            overallPermille = json.optInt("overallPermille", 0).coerceIn(0, 1000),
        )
    }

    private fun parseSummary(json: JSONObject?): AudioTranscodeQueueTerminalSummary? {
        json ?: return null
        return AudioTranscodeQueueTerminalSummary(
            outputPath = json.optNullableString("outputPath"),
            failureReason = json.optNullableString("failureReason")
                ?.let { enumValueOrNull<AudioTranscodeFailureReason>(it) },
            detail = json.optNullableString("detail"),
            nativeCode = json.optNullableInt("nativeCode"),
            backend = parseBackend(json.optJSONObject("backend")),
            postActionDetail = json.optNullableString("postActionDetail"),
            sourceDeleted = json.optNullableBoolean("sourceDeleted"),
            sourceDeleteNeedsConsent = json.optBoolean("sourceDeleteNeedsConsent", false),
            autoScanRequested = json.optBoolean("autoScanRequested", false),
        )
    }

    private fun parseBackend(json: JSONObject?): AudioTranscodeBackendInfo? {
        json ?: return null
        val name = json.optString("name").takeIf(String::isNotBlank) ?: return null
        return AudioTranscodeBackendInfo(
            kind = enumValueOrNull<AudioTranscodeBackendKind>(json.optString("kind")) ?: return null,
            name = name,
            hardwareRequested = json.optBoolean("hardwareRequested", false),
            fallbackReason = json.optNullableString("fallbackReason"),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotEmpty)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key)

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val FILE_NAME = "audio_transcode_queue_v1.json"
        private const val KEY_VERSION = "version"
        private const val KEY_ENTRIES = "entries"
    }
}
