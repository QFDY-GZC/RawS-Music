package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualListProviderNavigationFrameTest {
    @Test fun retainedDirectoryKeepsClickedHolderBankAcrossChildPublication() {
        val provider = ReferenceRegisteredVirtualList(com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY)
        fun visit(path: String) = provider.publishNavigationContext(
            VirtualListProviderNavigationFrame(physicalProviderIdentity = path),
            com.rawsmusic.core.ui.scene.SharedTransitionSpec(),
        )
        visit("/music")
        val clickedBank = provider.physicalPoolIdentity
        val retained = ReferenceRegisteredVirtualList(provider.scene, provider.nestedPhysicalIdentity("/music"))
        visit("/music/album")
        assertSame(clickedBank, retained.physicalPoolIdentity)
        org.junit.Assert.assertNotSame(retained.physicalPoolIdentity, provider.physicalPoolIdentity)
        // Local directories must not change the route identity used by outer-scene preflight.
        assertEquals("", provider.providerIdentity)
        visit("/music")
        assertSame(retained.physicalPoolIdentity, provider.physicalPoolIdentity)
    }

    @Test fun preparationBindsChildBeforeMotionButMotionCannotBindColdRows() {
        val preparing = VirtualListProviderNavigationFrame(active = true)
        fun action(frame: VirtualListProviderNavigationFrame) = resolveVirtualListOuterPublicationAction(
            outerAlreadyBound = true, contentChanged = true, residentContentReady = false,
            motionActive = frame.readyForMotion,
        )
        assertEquals(VirtualListOuterPublicationAction.FULL_CONTENT_BIND, action(preparing))
        assertEquals(VirtualListOuterPublicationAction.DEFER_UNTIL_PREPARED,
            action(preparing.copy(readyForMotion = true)))
    }
    @Test fun hierarchyDirectoriesKeepDistinctStableHolderBanks() {
        val provider = ReferenceRegisteredVirtualList(com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY)
        fun visit(path: String) = provider.publishNavigationContext(
            VirtualListProviderNavigationFrame(physicalProviderIdentity = path),
            com.rawsmusic.core.ui.scene.SharedTransitionSpec(),
        )
        val root = provider.physicalPoolIdentity
        visit("/music")
        val parent = provider.physicalPoolIdentity
        org.junit.Assert.assertNotSame(root, parent)
        visit("/music/album")
        org.junit.Assert.assertNotSame(parent, provider.physicalPoolIdentity)
        visit("/music")
        assertSame(parent, provider.physicalPoolIdentity)
        visit("")
        assertSame(root, provider.physicalPoolIdentity)
    }
    @Test fun persistentProviderRetainsNavigationAcknowledgementAndSharedContext() {
        val provider = ReferenceRegisteredVirtualList(com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY)
        val retained = ReferenceRegisteredVirtualList(com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY)
        var acknowledgements = 0
        val frame = VirtualListProviderNavigationFrame(active = true, retainedProvider = retained,
            onPopulationPublished = { acknowledgements++ })
        val shared = com.rawsmusic.core.ui.scene.SharedTransitionSpec(active = true, transitionKey = "child")
        provider.publishNavigationContext(frame, shared)
        assertSame(frame, provider.providerNavigationFrame)
        assertSame(shared, provider.providerSharedTransitionSpec)
        provider.providerNavigationFrame.onPopulationPublished?.invoke()
        assertEquals(1, acknowledgements)
        provider.publishNavigationContext(VirtualListProviderNavigationFrame(), com.rawsmusic.core.ui.scene.SharedTransitionSpec())
        assertFalse(provider.providerNavigationActive)
        assertFalse(provider.providerNavigationFrame.active)
    }
    @Test fun localNavigationRequiresBothProviders() {
        assertFalse(usesLocalProviderNavigation(true, false, false))
        assertFalse(usesLocalProviderNavigation(false, true, false))
        assertTrue(usesLocalProviderNavigation(true, true, false))
    }

    @Test fun outerSceneTransitionAlwaysOwnsThePopulationTransform() {
        assertFalse(usesLocalProviderNavigation(true, true, true))
    }
}
