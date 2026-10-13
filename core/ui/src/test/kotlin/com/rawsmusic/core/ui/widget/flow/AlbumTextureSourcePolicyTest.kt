package com.rawsmusic.core.ui.widget.flow

import org.junit.Assert.*
import org.junit.Test

class AlbumTextureSourcePolicyTest {
    @Test fun visibleCoverUpgradesProviderCache() {
        assertFalse(canReuseAlbumTexture(null, 42L))
        assertTrue(canPublishAlbumTexture(null, 42L))
    }
    @Test fun lateProviderCannotOverwriteVisibleCover() {
        assertFalse(canPublishAlbumTexture(42L, null))
        assertTrue(canReuseAlbumTexture(42L, null))
    }
    @Test fun changedVisibleCoverRebuildsTexture() {
        assertFalse(canReuseAlbumTexture(42L, 43L))
        assertTrue(canPublishAlbumTexture(42L, 43L))
    }
    @Test fun sameVisibleCoverDoesNotRebuild() {
        assertTrue(canReuseAlbumTexture(42L, 42L))
        assertTrue(canReuseAlbumTexture(null, null))
    }
}
