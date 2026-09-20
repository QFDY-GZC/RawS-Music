package com.rawsmusic.transcode

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTranscodeQueuePolicyTest {
    @Test
    fun priorityInsert_keepsRunningFirst_andPrecedesOrdinaryQueuedItems() {
        val current = listOf(
            entry("running", AudioTranscodeQueueState.RUNNING),
            entry("queued-a", AudioTranscodeQueueState.QUEUED),
            entry("queued-b", AudioTranscodeQueueState.QUEUED),
            entry("done", AudioTranscodeQueueState.COMPLETED),
        )

        val result = insertQueuedEntries(
            current = current,
            newEntries = listOf(entry("priority", AudioTranscodeQueueState.QUEUED)),
            priority = true,
        )

        assertEquals(
            listOf("running", "priority", "queued-a", "queued-b", "done"),
            result.map(AudioTranscodeQueueEntry::id),
        )
    }

    @Test
    fun ordinaryInsert_appendsToQueueTail_withoutReorderingExistingEntries() {
        val current = listOf(
            entry("running", AudioTranscodeQueueState.RUNNING),
            entry("queued-a", AudioTranscodeQueueState.QUEUED),
            entry("done", AudioTranscodeQueueState.COMPLETED),
        )

        val result = insertQueuedEntries(
            current = current,
            newEntries = listOf(entry("normal", AudioTranscodeQueueState.QUEUED)),
            priority = false,
        )

        assertEquals(
            listOf("running", "queued-a", "done", "normal"),
            result.map(AudioTranscodeQueueEntry::id),
        )
    }

    private fun entry(id: String, state: AudioTranscodeQueueState): AudioTranscodeQueueEntry =
        AudioTranscodeQueueEntry(
            id = id,
            request = AudioTranscodeRequest(
                inputPath = "/input/$id.wav",
                outputPath = "/output/$id.flac",
            ),
            state = state,
        )
}
