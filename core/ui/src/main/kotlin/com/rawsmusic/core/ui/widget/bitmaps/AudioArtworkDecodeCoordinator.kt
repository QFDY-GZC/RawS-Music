package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Runs audio artwork source selection exactly once for every provider path.
 *
 * BitmapProvider supplies the concrete decoders; this class owns only ordering, same-key
 * serialization, and the folder-fallback commit barrier. Primary requests and sibling-tier
 * coalescing therefore cannot silently diverge into different source orders.
 */
internal object AudioArtworkDecodeCoordinator {
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
    ): T? {
        val lock = sourceSelectionLocks[(providerKey.hashCode() and Int.MAX_VALUE) % sourceSelectionLocks.size]
        return synchronized(lock) {
            val embeddedState = ArtworkSourceIndex.embeddedStateFor(providerKey)
            if (embeddedState != ArtworkSourceAuthority.EmbeddedState.Absent) {
                for (stage in ArtworkSourceSelectionPolicy.embeddedDecodeOrder) {
                    val decoded = decodeEmbedded(stage)
                    if (decoded != null) {
                        ArtworkSourceIndex.markEmbeddedPresent(providerKey)
                        return@synchronized decoded
                    }
                }
            }

            val permit = ArtworkSourceIndex.beginFolderFallback(providerKey)
                ?: return@synchronized null
            val folder = decodeFolder() ?: return@synchronized null
            if (ArtworkSourceIndex.commitFolderSource(permit, folder.sourcePath)) {
                folder.value
            } else {
                discardRejectedFolder(folder.value)
                null
            }
        }
    }
}
