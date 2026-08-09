package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Runs audio artwork source selection exactly once for every provider path.
 *
 * BitmapProvider supplies the concrete decoders; this class owns only ordering, same-key
 * serialization, and the folder-fallback commit barrier. Primary requests and sibling-tier
 * coalescing therefore cannot silently diverge into different source orders.
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

    private val sourceSelectionLocks = Array(64) { Any() }

    fun <T> decode(
        providerKey: String,
        decodeEmbedded: (RawArtworkPolicy.DecodeStage) -> T?,
        decodeFolder: () -> FolderCandidate<T>?,
        discardRejectedFolder: (T) -> Unit = {}
    ): DecodeResult<T> {
        val lock = sourceSelectionLocks[(providerKey.hashCode() and Int.MAX_VALUE) % sourceSelectionLocks.size]
        return synchronized(lock) {
            val embeddedState = ArtworkSourceIndex.embeddedStateFor(providerKey)
            val embeddedWasKnownPresent = embeddedState == ArtworkSourceAuthority.EmbeddedState.Present
            var transientFailure = false
            if (embeddedState != ArtworkSourceAuthority.EmbeddedState.Absent) {
                for (stage in ArtworkSourceSelectionPolicy.embeddedDecodeOrder) {
                    val decoded = try {
                        decodeEmbedded(stage)
                    } catch (error: Exception) {
                        transientFailure = true
                        android.util.Log.d(
                            "RawArt",
                            "EMBEDDED_PROBE_TRANSIENT stage=$stage key=${providerKey.takeLast(72)} error=${error.javaClass.simpleName}"
                        )
                        null
                    }
                    if (decoded != null) {
                        ArtworkSourceIndex.markEmbeddedPresent(providerKey)
                        return@synchronized DecodeResult.Found(decoded)
                    }
                }
            }

            // A transient embedded probe must not turn into a permanent Absent authority. The
            // folder candidate can still be used for this request, but it is deliberately not
            // committed until every embedded stage completed without an exception.
            val permit = ArtworkSourceIndex.beginFolderFallback(
                providerKey = providerKey,
                confirmsEmbeddedAbsent = !transientFailure
            ) ?: return@synchronized when {
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
            } ?: return@synchronized if (transientFailure) {
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
        }
    }
}
