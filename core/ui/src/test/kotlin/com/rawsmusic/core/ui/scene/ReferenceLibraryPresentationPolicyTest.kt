package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLibraryPresentationPolicyTest {
    @Test
    fun forwardKeepsOldPresentationAndSwapsInternalLayoutRoles() {
        val sourceTransform = RetainedSceneItemTransform(1f, 2f, { 1f })
        val targetTransform = RetainedSceneItemTransform(3f, 4f, { 0.5f })
        val resolved = resolveReferenceLibraryHostFrame(
            frame = ReferenceLibraryLayoutFrame(
                active = true,
                retainedScene = NavScene.HOME,
                currentScene = NavScene.SONGS,
                presentationScene = NavScene.HOME,
                retainedTransform = sourceTransform,
                currentTransform = targetTransform,
            ),
            requestedScene = NavScene.SONGS,
        )

        assertEquals(NavScene.HOME, resolved.presentationScene)
        assertTrue(resolved.presentsRetainedRole)
        assertEquals(NavScene.HOME, resolved.frame.currentScene)
        assertEquals(NavScene.SONGS, resolved.frame.retainedScene)
        assertEquals(sourceTransform, resolved.frame.currentTransform)
        assertEquals(targetTransform, resolved.frame.retainedTransform)
    }

    @Test
    fun backKeepsDisplayedSourceWithoutRoleSwap() {
        val sourceTransform = RetainedSceneItemTransform(1f, 2f, { 0f })
        val targetTransform = RetainedSceneItemTransform(3f, 4f, { 1f })
        val resolved = resolveReferenceLibraryHostFrame(
            frame = ReferenceLibraryLayoutFrame(
                active = true,
                retainedScene = NavScene.HOME,
                currentScene = NavScene.SONGS,
                presentationScene = NavScene.SONGS,
                retainedTransform = targetTransform,
                currentTransform = sourceTransform,
            ),
            requestedScene = NavScene.SONGS,
        )

        assertEquals(NavScene.SONGS, resolved.presentationScene)
        assertFalse(resolved.presentsRetainedRole)
        assertEquals(NavScene.SONGS, resolved.frame.currentScene)
        assertEquals(NavScene.HOME, resolved.frame.retainedScene)
        assertEquals(sourceTransform, resolved.frame.currentTransform)
        assertEquals(targetTransform, resolved.frame.retainedTransform)
    }

    @Test
    fun forwardEndpointMountsTargetControllerBeforeOuterOwnerIsReleased() {
        val sourceTransform = RetainedSceneItemTransform(1f, 2f, { 0f })
        val targetTransform = RetainedSceneItemTransform(3f, 4f, { 1f })
        val resolved = resolveReferenceLibraryHostFrame(
            frame = ReferenceLibraryLayoutFrame(
                active = true,
                retainedScene = NavScene.HOME,
                currentScene = NavScene.ALBUMS,
                presentationScene = NavScene.ALBUMS,
                retainedTransform = sourceTransform,
                currentTransform = targetTransform,
            ),
            requestedScene = NavScene.ALBUMS,
        )

        assertEquals(NavScene.ALBUMS, resolved.presentationScene)
        assertFalse(resolved.presentsRetainedRole)
        assertEquals(NavScene.ALBUMS, resolved.frame.currentScene)
        assertEquals(NavScene.HOME, resolved.frame.retainedScene)
        assertEquals(targetTransform, resolved.frame.currentTransform)
        assertEquals(sourceTransform, resolved.frame.retainedTransform)
    }

    @Test
    fun backEndpointPromotesRetainedTargetBeforeRouteCommit() {
        val sourceTransform = RetainedSceneItemTransform(1f, 2f, { 0f })
        val targetTransform = RetainedSceneItemTransform(3f, 4f, { 1f })
        val resolved = resolveReferenceLibraryHostFrame(
            frame = ReferenceLibraryLayoutFrame(
                active = true,
                retainedScene = NavScene.HOME,
                currentScene = NavScene.ALBUMS,
                presentationScene = NavScene.HOME,
                retainedTransform = targetTransform,
                currentTransform = sourceTransform,
            ),
            requestedScene = NavScene.ALBUMS,
        )

        assertEquals(NavScene.HOME, resolved.presentationScene)
        assertTrue(resolved.presentsRetainedRole)
        assertEquals(NavScene.HOME, resolved.frame.currentScene)
        assertEquals(NavScene.ALBUMS, resolved.frame.retainedScene)
        assertEquals(targetTransform, resolved.frame.currentTransform)
        assertEquals(sourceTransform, resolved.frame.retainedTransform)
    }

    @Test
    fun retainedDetailRoleKeepsItsOwnProviderIdentityWhenRolesSwap() {
        val resolved = resolveReferenceLibraryHostFrame(
            frame = ReferenceLibraryLayoutFrame(
                active = true,
                retainedScene = NavScene.ALBUM_DETAIL,
                currentScene = NavScene.ALBUMS,
                retainedProviderIdentity = "album:A",
                currentProviderIdentity = "",
                presentationScene = NavScene.ALBUM_DETAIL,
                presentationProviderIdentity = "album:A",
            ),
            requestedScene = NavScene.ALBUMS,
        )

        assertTrue(resolved.presentsRetainedRole)
        assertEquals(NavScene.ALBUM_DETAIL, resolved.frame.currentScene)
        assertEquals("album:A", resolved.frame.currentProviderIdentity)
        assertEquals(NavScene.ALBUMS, resolved.frame.retainedScene)
        assertEquals("", resolved.frame.retainedProviderIdentity)
    }

    @Test
    fun settledUsesRequestedCurrentPresentation() {
        val resolved = resolveReferenceLibraryHostFrame(
            frame = ReferenceLibraryLayoutFrame(currentScene = NavScene.ALBUMS),
            requestedScene = NavScene.ALBUMS,
        )
        assertEquals(NavScene.ALBUMS, resolved.presentationScene)
        assertFalse(resolved.presentsRetainedRole)
    }
    @Test
    fun destinationShellSurvivesPreparationAndActiveMotionUntilPromotion() {
        assertEquals(
            NavScene.SONGS,
            resolveReferenceLibraryStandbyScene(
                frame = ReferenceLibraryLayoutFrame(
                    active = false,
                    currentScene = NavScene.HOME,
                    preparingScene = NavScene.SONGS,
                ),
                currentScene = NavScene.HOME,
            ),
        )
        assertEquals(
            NavScene.SONGS,
            resolveReferenceLibraryStandbyScene(
                frame = ReferenceLibraryLayoutFrame(
                    active = true,
                    currentScene = NavScene.HOME,
                    retainedScene = NavScene.SONGS,
                ),
                currentScene = NavScene.HOME,
            ),
        )
        assertEquals(
            null,
            resolveReferenceLibraryStandbyScene(
                frame = ReferenceLibraryLayoutFrame(
                    active = false,
                    currentScene = NavScene.SONGS,
                ),
                currentScene = NavScene.SONGS,
            ),
        )
    }

}
