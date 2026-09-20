package com.rawsmusic.separation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiStemDependencyPolicyTest {
    @Test
    fun preferredModelWinsWithoutForcingNewSeparation() {
        withTempStems { vocals, instrumental ->
            val newestOtherModel = dependency(
                resultId = "newer",
                modelId = "model-b",
                modelVersion = "2",
                createdAt = 200L,
                vocals = vocals,
                instrumental = instrumental,
            )
            val olderPreferred = dependency(
                resultId = "preferred",
                modelId = "model-a",
                modelVersion = "1",
                createdAt = 100L,
                vocals = vocals,
                instrumental = instrumental,
            )

            val selected = AiStemDependencyPolicy.select(
                candidates = listOf(newestOtherModel, olderPreferred),
                requirement = AiStemDependencyRequirement(
                    preferredModelId = "model-a",
                    preferredModelVersion = "1",
                ),
            )

            assertEquals("preferred", selected?.separationResultId)
        }
    }

    @Test
    fun newestValidDependencyWinsWhenNoModelIsPreferred() {
        withTempStems { vocals, instrumental ->
            val older = dependency("older", "model-a", "1", 100L, vocals, instrumental)
            val newer = dependency("newer", "model-b", "1", 200L, vocals, instrumental)

            val selected = AiStemDependencyPolicy.select(
                candidates = listOf(older, newer),
                requirement = AiStemDependencyRequirement(),
            )

            assertEquals("newer", selected?.separationResultId)
        }
    }

    @Test
    fun missingStemIsNeverReusable() {
        val root = createTempDir(prefix = "rawsmusic-stem-policy-")
        try {
            val vocals = File(root, "vocals.flac").apply { writeBytes(ByteArray(128)) }
            val missingInstrumental = File(root, "instrumental.flac")
            val invalid = dependency(
                resultId = "invalid",
                modelId = "model-a",
                modelVersion = "1",
                createdAt = 100L,
                vocals = vocals,
                instrumental = missingInstrumental,
            )

            assertNull(
                AiStemDependencyPolicy.select(
                    candidates = listOf(invalid),
                    requirement = AiStemDependencyRequirement(),
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun dependencyFingerprintInvalidatesWithSourceOrResult() {
        withTempStems { vocals, instrumental ->
            val result = AiSeparationResult(
                id = "result-a",
                sourceName = "song.flac",
                modelId = "model-a",
                modelVersion = "1",
                modelName = "Model A",
                sampleRate = 44_100,
                totalFrames = 441_000,
                processedSegments = 1,
                elapsedMs = 1L,
                createdAtEpochMs = 100L,
                directory = vocals.parentFile!!.absolutePath,
                outputFormat = "flac",
            )
            val first = AiStemDependency.from(result, "source-a")
            val differentSource = AiStemDependency.from(result, "source-b")
            val differentResult = AiStemDependency.from(result.copy(id = "result-b"), "source-a")

            assertNotEquals(first.dependencyFingerprint, differentSource.dependencyFingerprint)
            assertNotEquals(first.dependencyFingerprint, differentResult.dependencyFingerprint)
        }
    }

    private fun dependency(
        resultId: String,
        modelId: String,
        modelVersion: String,
        createdAt: Long,
        vocals: File,
        instrumental: File,
    ): AiStemDependency = AiStemDependency(
        dependencyFingerprint = "dep-$resultId",
        separationResultId = resultId,
        sourceFingerprint = "source",
        sourceName = "song",
        modelId = modelId,
        modelVersion = modelVersion,
        sampleRate = 44_100,
        totalFrames = 441_000,
        createdAtEpochMs = createdAt,
        outputFormat = "flac",
        vocalsFile = vocals,
        instrumentalFile = instrumental,
    )

    private inline fun withTempStems(block: (File, File) -> Unit) {
        val root = createTempDir(prefix = "rawsmusic-stem-policy-")
        try {
            val vocals = File(root, "vocals.flac").apply { writeBytes(ByteArray(128)) }
            val instrumental = File(root, "instrumental.flac").apply { writeBytes(ByteArray(128)) }
            block(vocals, instrumental)
        } finally {
            root.deleteRecursively()
        }
    }
}
