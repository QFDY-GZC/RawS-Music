package com.rawsmusic.core.ui.widget.bitmaps

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs audio artwork source selection exactly once for every provider path.
 *
 * BitmapProvider supplies the concrete decoders; this class owns only ordering, exact-key
 * single-flight admission, and the folder-fallback commit barrier. Heavy decoder work must never
 * run while holding a Java monitor: visible 384/512/1024 requests can arrive together and a
 * monitor-held decode otherwise parks the whole artwork worker pool behind one slow extractor.
 */
internal object AudioArtworkDecodeCoordinator {
    sealed interface DecodeResult<out T> {
        data class Found<T>(val value: T) : DecodeResult<T>
        data object ConfirmedAbsent : DecodeResult<Nothing>
        data object TransientFailure : DecodeResult<Nothing>
    }

    data class FolderCandidate<T>(
        val value: T,
        val sourcePath: String
    )

    private val sourceSelectionsInFlight = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private class AnalysisAttempt {
        var busySource: CompletableDeferred<Unit>? = null
    }

    private val analysisAttempt = ThreadLocal<AnalysisAttempt>()

    /** Only analysis consumers suspend; visible artwork workers retain their non-blocking path. */
    internal suspend fun <T> retryAnalysisAfterSourceSelection(decode: () -> T?): T? {
        repeat(4) { attemptNumber ->
            currentCoroutineContext().ensureActive()
            val attempt = AnalysisAttempt()
            val previous = analysisAttempt.get()
            analysisAttempt.set(attempt)
            // decode is synchronous. Restore thread-local decoder state BEFORE any suspension,
            // because an IO coroutine may resume on a different existing pool thread.
            val result = try {
                decode()
            } finally {
                if (previous == null) analysisAttempt.remove() else analysisAttempt.set(previous)
            }
            if (result != null) return result
            val busySource = attempt.busySource ?: return null
            if (attemptNumber == 3) return null
            // Await the exact flight observed by the failed probe, not a later map lookup that
            // could miss completion. Cancellation of this waiter never cancels the source owner.
            val completed = withTimeoutOrNull(5_000L) { busySource.await(); true } ?: false
            if (!completed) return null
        }
        return null
    }

    fun <T> decode(
        providerKey: String,
        decodeEmbedded: (RawArtworkPolicy.DecodeStage) -> T?,
        decodeFolder: () -> FolderCandidate<T>?,
        discardRejectedFolder: (T) -> Unit = {}
    ): DecodeResult<T> {
        val flight = CompletableDeferred<Unit>()
        val existingFlight = sourceSelectionsInFlight.putIfAbsent(providerKey, flight)
        if (existingFlight != null) {
            analysisAttempt.get()?.busySource = existingFlight
            // The owner is still resolving embedded-vs-folder authority. Do not block another
            // BitmapWorker and do not race a folder commit. The request remains retryable; once
            // the owner publishes source authority, a later request takes the indexed fast path.
            return DecodeResult.TransientFailure
        }
        return try {
            val embeddedState = ArtworkSourceIndex.embeddedStateFor(providerKey)
            val embeddedWasKnownPresent = embeddedState == ArtworkSourceAuthority.EmbeddedState.Present
            var transientFailure = false
            if (embeddedState != ArtworkSourceAuthority.EmbeddedState.Absent) {
                for (stage in ArtworkSourceSelectionPolicy.embeddedDecodeOrder) {
                    val decoded = try {
                        decodeEmbedded(stage)
                    } catch (error: Exception) {
                        transientFailure = true
                        null
                    }
                    if (decoded != null) {
                        ArtworkSourceIndex.markEmbeddedPresent(providerKey)
                        return DecodeResult.Found(decoded)
                    }
                }
            }

            // A transient embedded probe must not turn into a permanent Absent authority. The
            // folder candidate can still be used for this request, but it is deliberately not
            // committed until every embedded stage completed without an exception.
            val permit = ArtworkSourceIndex.beginFolderFallback(
                providerKey = providerKey,
                confirmsEmbeddedAbsent = !transientFailure
            ) ?: return when {
                transientFailure || embeddedWasKnownPresent -> {
                    // A previously successful embedded source is evidence that the file has
                    // artwork. A later null probe can be a temporary extractor/permission race,
                    // never proof that the artwork was removed. Keep the source retryable and
                    // do not let a folder fallback overwrite it.
                    if (transientFailure) ArtworkSourceIndex.resetEmbeddedAuthority(providerKey)
                    DecodeResult.TransientFailure
                }
                else -> DecodeResult.ConfirmedAbsent
            }
            val folder = try {
                decodeFolder()
            } catch (error: Exception) {
                transientFailure = true
                null
            } ?: return if (transientFailure) {
                ArtworkSourceIndex.resetEmbeddedAuthority(providerKey)
                DecodeResult.TransientFailure
            } else {
                DecodeResult.ConfirmedAbsent
            }
            if (!transientFailure && ArtworkSourceIndex.commitFolderSource(permit, folder.sourcePath)) {
                DecodeResult.Found(folder.value)
            } else if (transientFailure) {
                ArtworkSourceIndex.resetEmbeddedAuthority(providerKey)
                DecodeResult.Found(folder.value)
            } else {
                discardRejectedFolder(folder.value)
                DecodeResult.ConfirmedAbsent
            }
        } finally {
            sourceSelectionsInFlight.remove(providerKey, flight)
            flight.complete(Unit)
        }
    }
}
