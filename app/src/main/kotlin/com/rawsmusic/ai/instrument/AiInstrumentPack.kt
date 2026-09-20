package com.rawsmusic.ai.instrument

import java.io.File
import java.security.MessageDigest

/**
 * Immutable contract for an installable local instrument sample pack.
 *
 * Step 3 intentionally starts with a sampler renderer: melody extraction quality can be verified
 * independently from any future neural timbre model, while the player still receives a normal
 * rendered audio file instead of a realtime synthesis dependency.
 */
data class AiInstrumentPackManifest(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val displayName: String,
    val instrument: String,
    val sampleRate: Int,
    val channels: Int,
    val licenseFile: String,
    val samples: List<AiInstrumentSampleManifest>,
) {
    init { validated() }

    /** Gson may allocate Kotlin classes without running init blocks, so stores call this explicitly too. */
    fun validated(): AiInstrumentPackManifest {
        require(schemaVersion in MIN_SUPPORTED_SCHEMA..SCHEMA_VERSION) { "instrument pack schema is unsupported" }
        require(id.matches(ID_PATTERN) && version.matches(VERSION_PATTERN)) { "instrument pack identity is invalid" }
        require(displayName.isNotBlank() && instrument.isNotBlank()) { "instrument pack display metadata is empty" }
        require(sampleRate in 8_000..192_000) { "instrument pack sample rate is invalid" }
        require(channels in 1..2) { "instrument pack channels must be mono or stereo" }
        require(isSafeRelativePath(licenseFile)) { "instrument pack license path is unsafe" }
        require(licenseFile != MANIFEST_FILE) { "instrument pack license path is reserved" }
        require(samples.isNotEmpty() && samples.size <= MAX_SAMPLES) { "instrument pack sample count is invalid" }
        samples.forEach { sample ->
            sample.validated()
            require(isSafeRelativePath(sample.path)) { "instrument sample path is unsafe: ${sample.path}" }
        }
        require(samples.map { it.path }.toSet().size == samples.size) { "instrument pack has duplicate sample paths" }
        require(samples.none { it.path == MANIFEST_FILE || it.path == licenseFile }) {
            "instrument sample path collides with pack metadata"
        }
        return this
    }

    /** Stable pack-content identity so replacing files under the same id/version cannot reuse old lead audio. */
    fun contentFingerprint(): String {
        validated()
        val canonical = buildString {
            append("rawsmusic-instrument-pack-v2|")
            append(id).append('|').append(version).append('|').append(instrument).append('|')
            append(sampleRate).append('|').append(channels).append('|')
            samples.sortedBy { it.path }.forEach { sample ->
                append(sample.path).append(':').append(sample.rootMidiNote).append(':')
                    .append(sample.velocityMin).append(':').append(sample.velocityMax).append(':')
                    .append(sample.gainDb).append(':')
                    .append(sample.loopStartFrame ?: -1L).append(':')
                    .append(sample.loopEndFrameExclusive ?: -1L).append(':')
                    .append(sample.sha256.lowercase()).append('|')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MIN_SUPPORTED_SCHEMA = 1
        const val SCHEMA_VERSION = 2
        const val MAX_SAMPLES = 384
        const val MANIFEST_FILE = "manifest.json"
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,63}")
        private val VERSION_PATTERN = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}")

        fun isSafeRelativePath(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
            if (Regex("^[A-Za-z]:").containsMatchIn(path)) return false
            val normalized = path.replace('\\', '/')
            return normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
        }
    }
}

data class AiInstrumentSampleManifest(
    val path: String,
    val rootMidiNote: Int,
    val velocityMin: Int = 1,
    val velocityMax: Int = 127,
    val gainDb: Float = 0f,
    val loopStartFrame: Long? = null,
    val loopEndFrameExclusive: Long? = null,
    val sha256: String,
) {
    init { validated() }

    fun validated(): AiInstrumentSampleManifest {
        require(rootMidiNote in 0..127) { "sample root note is invalid" }
        require(velocityMin in 1..127 && velocityMax in velocityMin..127) { "sample velocity range is invalid" }
        require(gainDb.isFinite() && gainDb in -36f..12f) { "sample gain is invalid" }
        require((loopStartFrame == null) == (loopEndFrameExclusive == null)) { "sample loop bounds are incomplete" }
        if (loopStartFrame != null && loopEndFrameExclusive != null) {
            require(loopStartFrame >= 0L && loopEndFrameExclusive > loopStartFrame + 1L) {
                "sample loop bounds are invalid"
            }
        }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "sample SHA-256 is invalid" }
        return this
    }
}

data class AiInstalledInstrumentPack(
    val manifest: AiInstrumentPackManifest,
    val directory: File,
) {
    init {
        require(directory.isDirectory) { "instrument pack directory is missing" }
    }

    fun sampleFile(sample: AiInstrumentSampleManifest): File = File(directory, sample.path)
    fun licenseFile(): File = File(directory, manifest.licenseFile)
}
