package com.rawsmusic.core.ui.widget.bitmaps

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioArtworkDecodeCoordinatorTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        ArtworkSourceIndex.clear()
        directory = createTempDir(prefix = "audio-artwork-coordinator-")
    }

    @After
    fun tearDown() {
        ArtworkSourceIndex.clear()
        directory.deleteRecursively()
    }

    @Test
    fun embeddedStagesRunInPolicyOrderAndStopBeforeFolder() {
        val visited = mutableListOf<RawArtworkPolicy.DecodeStage>()
        var folderCalled = false

        val result = AudioArtworkDecodeCoordinator.decode(
            providerKey = "audio:///music/song.flac|1|2",
            decodeEmbedded = { stage ->
                visited += stage
                if (stage == RawArtworkPolicy.DecodeStage.Ffmpeg) "embedded" else null
            },
            decodeFolder = {
                folderCalled = true
                AudioArtworkDecodeCoordinator.FolderCandidate("folder", imageFile("folder.jpg").absolutePath)
            }
        )

        assertEquals(AudioArtworkDecodeCoordinator.DecodeResult.Found("embedded"), result)
        assertEquals(
            listOf(
                RawArtworkPolicy.DecodeStage.RegionHandle,
                RawArtworkPolicy.DecodeStage.NativeSource,
                RawArtworkPolicy.DecodeStage.Ffmpeg
            ),
            visited
        )
        assertFalse(folderCalled)
    }

    @Test
    fun folderResultCommitsOnlyAfterAllEmbeddedStagesMiss() {
        val key = "audio:///music/no-embedded.flac|1|2"
        val folder = imageFile("folder.jpg")

        val result = AudioArtworkDecodeCoordinator.decode(
            providerKey = key,
            decodeEmbedded = { null },
            decodeFolder = {
                AudioArtworkDecodeCoordinator.FolderCandidate("folder", folder.absolutePath)
            }
        )

        assertEquals(AudioArtworkDecodeCoordinator.DecodeResult.Found("folder"), result)
        assertTrue(ArtworkSourceIndex.mayUseFolderFallback(key))
        assertEquals(
            folder.absolutePath,
            ArtworkSourceIndex.sourcePathFor(
                key,
                ArtworkSourceSelectionPolicy.folderIndexedKinds
            )
        )
    }


    @Test
    fun sameKeyRequestsCannotPublishFolderWhileEmbeddedSelectionIsInFlight() {
        val key = "audio:///music/concurrent.flac|1|2"
        val folder = imageFile("concurrent-folder.jpg")
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val results = java.util.concurrent.CopyOnWriteArrayList<AudioArtworkDecodeCoordinator.DecodeResult<String>>()
        var secondFolderCalled = false

        val first = Thread {
            results += AudioArtworkDecodeCoordinator.decode(
                providerKey = key,
                decodeEmbedded = { stage ->
                    if (stage == RawArtworkPolicy.DecodeStage.RegionHandle) {
                        started.countDown()
                        release.await()
                        "embedded"
                    } else {
                        null
                    }
                },
                decodeFolder = {
                    AudioArtworkDecodeCoordinator.FolderCandidate("folder-1", folder.absolutePath)
                }
            )
        }
        val second = Thread {
            started.await()
            results += AudioArtworkDecodeCoordinator.decode(
                providerKey = key,
                decodeEmbedded = { null },
                decodeFolder = {
                    secondFolderCalled = true
                    AudioArtworkDecodeCoordinator.FolderCandidate("folder-2", folder.absolutePath)
                }
            )
        }

        first.start()
        second.start()
        release.countDown()
        first.join()
        second.join()

        assertTrue(results.contains(AudioArtworkDecodeCoordinator.DecodeResult.Found("embedded")))
        assertTrue(results.contains(AudioArtworkDecodeCoordinator.DecodeResult.TransientFailure))
        assertFalse(secondFolderCalled)
        assertFalse(ArtworkSourceIndex.mayUseFolderFallback(key))
    }

    @Test
    fun sameKeyFollowerDoesNotBlockAWorkerBehindSourceSelection() {
        val key = "audio:///music/nonblocking-concurrent.flac|1|2"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val followerReturned = CountDownLatch(1)
        val followerResult = AtomicReference<AudioArtworkDecodeCoordinator.DecodeResult<String>?>(null)
        val followerFolderCalled = AtomicBoolean(false)

        val owner = Thread {
            AudioArtworkDecodeCoordinator.decode(
                providerKey = key,
                decodeEmbedded = { stage ->
                    if (stage == RawArtworkPolicy.DecodeStage.RegionHandle) {
                        started.countDown()
                        release.await()
                        "embedded"
                    } else {
                        null
                    }
                },
                decodeFolder = { null },
            )
        }
        val follower = Thread {
            started.await()
            followerResult.set(AudioArtworkDecodeCoordinator.decode(
                providerKey = key,
                decodeEmbedded = { null },
                decodeFolder = {
                    followerFolderCalled.set(true)
                    null
                },
            ))
            followerReturned.countDown()
        }

        owner.start()
        follower.start()

        // VirtualList artwork workers must never stack up behind a Java monitor while another tier
        // probes the same audio source. The follower is retryable and returns immediately.
        assertTrue(followerReturned.await(500, TimeUnit.MILLISECONDS))
        assertEquals(AudioArtworkDecodeCoordinator.DecodeResult.TransientFailure, followerResult.get())
        assertFalse(followerFolderCalled.get())

        release.countDown()
        owner.join()
        follower.join()
    }

    private fun imageFile(name: String): File = File(directory, name).apply {
        writeBytes(ByteArray(2_048) { (it % 251).toByte() })
    }
}
