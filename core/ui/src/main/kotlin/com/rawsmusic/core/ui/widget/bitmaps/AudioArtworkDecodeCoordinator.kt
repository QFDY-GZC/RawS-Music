package com.rawsmusic.core.ui.widget.bitmaps

import java.util.concurrent.ConcurrentHashMap

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

    private val sourceSelectionsInFlight = ConcurrentHashMap<String, Any>()

    fun <T> decode(
        providerKey: String,
        decodeEmbedded: (RawArtworkPolicy.DecodeStage) -> T?,
        decodeFolder: () -> FolderCandidate<T>?,
        discardRejectedFolder: (T) -> Unit = {}
    ): DecodeResult<T> {
        val flight = Any()
        if (sourceSelectionsInFlight.putIfAbsent(providerKey, flight) != null) {
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
        }
    }
}
