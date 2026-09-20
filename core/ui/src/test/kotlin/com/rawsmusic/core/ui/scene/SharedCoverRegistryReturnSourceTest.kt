package com.rawsmusic.core.ui.scene

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCoverRegistryReturnSourceTest {
    @Test
    fun `idle outer scene cannot clear same scene provider actor`() {
        val registry = SharedCoverRegistry()
        val source = snapshot("FOLDER_HIERARCHY", "folder:1", 300f)
        val target = snapshot("FOLDER_HIERARCHY", "folder:1", 0f)
        registry.prepareEphemeralTransition("hierarchy:1", source, target)
        registry.markOverlayReady("hierarchy:1")
        registry.clearFrozen()
        assertTrue(registry.isPrepared("hierarchy:1"))
        assertTrue(registry.isOverlayReady("hierarchy:1"))
        registry.clearEphemeralTransition("hierarchy:1")
        assertFalse(registry.isPrepared("hierarchy:1"))
    }

    @Test
    fun `new route replaces provider actor and remains normally clearable`() {
        val registry = SharedCoverRegistry()
        val source = snapshot("FOLDER_HIERARCHY", "folder:1", 300f)
        registry.prepareEphemeralTransition("hierarchy:1", source, source)
        registry.freezeSnapshot(source)
        registry.prepareTransition("route", source, snapshot("FOLDER_DETAIL", "folder:1", 0f))
        registry.clearEphemeralTransition("hierarchy:1")
        assertTrue(registry.isPrepared("route"))
        registry.clearFrozen()
        assertFalse(registry.isPrepared("route"))
    }

    @Test
    fun `offscreen physical header vetoes stale compose header for every return entry`() {
        val registry = SharedCoverRegistry()
        val element = "album:1"
        val list = snapshot("ALBUMS", element, 300f)
        val header = snapshot("ALBUM_DETAIL", element, 20f)
        registry.prepareTransition("forward", list, header)
        registry.register("ALBUM_DETAIL", element, Any(), header)
        registry.registerPhysicalLayoutEndpoint(
            "ALBUM_DETAIL", element, Any(),
            header.copy(boundsInWindow = Rect(0f, -300f, 200f, -100f), sharedEligible = false),
        )

        assertFalse(registry.freeze("ALBUM_DETAIL", element))
        assertFalse(registry.captureLiveCollectionReturnSource("ALBUM_DETAIL", "ALBUMS"))
        assertTrue(registry.findPairs("ALBUM_DETAIL", "ALBUMS", allowRememberedTarget = true).isEmpty())
    }

    @Test
    fun `physical header becomes eligible again after scrolling back into view`() {
        val registry = SharedCoverRegistry()
        val element = "album:1"
        val owner = Any()
        val header = snapshot("ALBUM_DETAIL", element, -80f)
        registry.prepareTransition("forward", snapshot("ALBUMS", element, 300f), header)
        registry.registerPhysicalLayoutEndpoint("ALBUM_DETAIL", element, owner, header.copy(sharedEligible = false))
        assertFalse(registry.captureLiveCollectionReturnSource("ALBUM_DETAIL", "ALBUMS"))
        registry.registerPhysicalLayoutEndpoint("ALBUM_DETAIL", element, owner, header)
        assertTrue(registry.captureLiveCollectionReturnSource("ALBUM_DETAIL", "ALBUMS"))
        assertEquals(header, registry.findPairs("ALBUM_DETAIL", "ALBUMS", allowRememberedTarget = true).single().first)
    }

    private fun snapshot(scene: String, element: String, top: Float): SharedCoverSnapshot =
        SharedCoverSnapshot(
            sceneId = scene,
            elementId = element,
            boundsInWindow = Rect(10f, top, 210f, top + 200f),
            coverKey = "cover",
            radiusDp = 14f,
        )

    @Test
    fun `offscreen detail source cannot be resurrected from remembered header`() {
        val registry = SharedCoverRegistry()
        val listOwner = Any()
        val detailOwner = Any()
        val element = "album:1"

        val list = snapshot("ALBUMS", element, 300f)
        val header = snapshot("ALBUM_DETAIL", element, 20f)
        registry.register("ALBUMS", element, listOwner, list)
        registry.freeze("ALBUMS", element)
        registry.register("ALBUM_DETAIL", element, detailOwner, header)
        val forward = registry.findPairs("ALBUMS", "ALBUM_DETAIL").single()
        registry.prepareTransition("ALBUMS->ALBUM_DETAIL", forward.first, forward.second)

        registry.unregister("ALBUM_DETAIL", element, detailOwner)

        assertFalse(registry.captureLiveCollectionReturnSource("ALBUM_DETAIL", "ALBUMS"))
        assertTrue(
            registry.findPairs(
                fromSceneId = "ALBUM_DETAIL",
                toSceneId = "ALBUMS",
                allowRememberedTarget = true,
            ).isEmpty()
        )
    }

    @Test
    fun `back freeze pins current scrolled detail geometry against later callbacks`() {
        val registry = SharedCoverRegistry()
        val listOwner = Any()
        val detailOwner = Any()
        val element = "album:1"

        val list = snapshot("ALBUMS", element, 300f)
        val initialHeader = snapshot("ALBUM_DETAIL", element, 20f)
        registry.register("ALBUMS", element, listOwner, list)
        registry.freeze("ALBUMS", element)
        registry.register("ALBUM_DETAIL", element, detailOwner, initialHeader)
        val forward = registry.findPairs("ALBUMS", "ALBUM_DETAIL").single()
        registry.prepareTransition("ALBUMS->ALBUM_DETAIL", forward.first, forward.second)

        val scrolledHeader = snapshot("ALBUM_DETAIL", element, -80f)
        registry.register("ALBUM_DETAIL", element, detailOwner, scrolledHeader)
        // SceneTransitionHost captures the current physical holder for every Back route. A top-bar
        // freeze is compatible with this but no longer required for correctness.
        assertTrue(registry.captureLiveCollectionReturnSource("ALBUM_DETAIL", "ALBUMS"))

        // Simulate a late layout callback after Back has already captured the physical source.
        registry.register("ALBUM_DETAIL", element, detailOwner, snapshot("ALBUM_DETAIL", element, -140f))

        val reverse = registry.findPairs(
            fromSceneId = "ALBUM_DETAIL",
            toSceneId = "ALBUMS",
            allowRememberedTarget = true,
        ).single()
        assertEquals(-80f, reverse.first.boundsInWindow.top, 0.001f)
        assertEquals(list.boundsInWindow, reverse.second.boundsInWindow)
    }
    @Test
    fun `prepared geometry does not hide holder before overlay owns pixels`() {
        val registry = SharedCoverRegistry()
        val element = "album:1"
        val from = snapshot("ALBUMS", element, 300f)
        val to = snapshot("ALBUM_DETAIL", element, 20f)
        val transitionKey = "ALBUMS->ALBUM_DETAIL"

        registry.prepareTransition(transitionKey, from, to)
        assertTrue(registry.isPrepared(transitionKey))
        assertFalse(registry.isOverlayReady(transitionKey))

        registry.markOverlayReady(transitionKey)
        assertTrue(registry.isOverlayReady(transitionKey))

        registry.clearEphemeralTransition(transitionKey)
        assertFalse(registry.isOverlayReady(transitionKey))
    }

    @Test
    fun `reverse target preparer replaces remembered list endpoint before pair resolution`() {
        val registry = SharedCoverRegistry()
        val element = "album:1"
        val listOwner = Any()
        val detailOwner = Any()
        val preparerOwner = Any()
        val list = snapshot("ALBUMS", element, 300f).copy(
            itemIndex = 7,
            itemViewportBounds = Rect(0f, 260f, 300f, 460f),
        )
        val header = snapshot("ALBUM_DETAIL", element, 20f)
        registry.register("ALBUMS", element, listOwner, list)
        registry.freeze("ALBUMS", element)
        registry.register("ALBUM_DETAIL", element, detailOwner, header)
        val forward = registry.findPairs("ALBUMS", "ALBUM_DETAIL").single()
        registry.prepareTransition("ALBUMS->ALBUM_DETAIL", forward.first, forward.second)
        registry.captureLiveCollectionReturnSource("ALBUM_DETAIL", "ALBUMS")

        registry.attachCollectionReturnTargetPreparer(preparerOwner) { session ->
            session.listEndpoint.copy(
                boundsInWindow = Rect(10f, 150f, 210f, 350f),
                itemViewportBounds = Rect(0f, 110f, 300f, 310f),
            )
        }
        assertTrue(registry.prepareCollectionReturnTarget("ALBUM_DETAIL", "ALBUMS"))

        val candidates = registry.findPairs(
            fromSceneId = "ALBUM_DETAIL",
            toSceneId = "ALBUMS",
            allowRememberedTarget = true,
        ).single()
        // Target stabilization happens when the transition is prepared; candidate lookup may
        // still see the retained list's previous measurement until its next layout callback.
        registry.prepareTransition("reverse", candidates.first, candidates.second)
        val reverse = checkNotNull(registry.getPreparedPair("reverse"))
        assertEquals(150f, reverse.second.boundsInWindow.top, 0.001f)
        assertEquals(110f, reverse.second.itemViewportBounds?.top ?: -1f, 0.001f)
        registry.detachCollectionReturnTargetPreparer(preparerOwner)
    }

    @Test
    fun `terminal physical return always retires promoted owner`() {
        val registry = SharedCoverRegistry()
        val element = "album:terminal"
        val list = snapshot("ALBUMS", element, 300f).copy(physicalSlotId = 7)
        val header = snapshot("ALBUM_DETAIL", element, 20f)
        var finalizedElement = ""
        val finalizerOwner = Any()

        registry.freezeSnapshot(list)
        registry.prepareTransition("forward", list, header)
        assertTrue(registry.isPhysicalPromotionActive())

        registry.attachPhysicalReturnFinalizer(finalizerOwner) { id ->
            finalizedElement = id
            true
        }
        assertTrue(registry.finishPhysicalReturn(element))

        assertEquals(element, finalizedElement)
        assertFalse(registry.isPhysicalPromotionActive())
        assertEquals(null, registry.collectionSessionSnapshot())
        assertFalse(registry.isPrepared("forward"))
        registry.detachPhysicalReturnFinalizer(finalizerOwner)
    }

    @Test
    fun `terminal physical return clears registry even when destination holder cannot be restored`() {
        val registry = SharedCoverRegistry()
        val element = "album:missing-target"
        val list = snapshot("ALBUMS", element, 300f).copy(physicalSlotId = 3)
        val header = snapshot("ALBUM_DETAIL", element, 20f)
        val finalizerOwner = Any()

        registry.freezeSnapshot(list)
        registry.prepareTransition("forward", list, header)
        registry.attachPhysicalReturnFinalizer(finalizerOwner) { false }

        assertFalse(registry.finishPhysicalReturn(element))
        assertFalse(registry.isPhysicalPromotionActive())
        assertEquals(null, registry.collectionSessionSnapshot())
        assertFalse(registry.isPrepared("forward"))
        registry.detachPhysicalReturnFinalizer(finalizerOwner)
    }

    @Test
    fun `stale physical promotion never suppresses settled detail hero`() {
        val registry = SharedCoverRegistry()
        val element = "album:lost-holder"
        val owner = Any()
        var live = true

        registry.attachPhysicalPromotionLiveness(owner) { id ->
            live && id == element
        }
        registry.freezeSnapshot(
            snapshot("ALBUMS", element, 300f).copy(physicalSlotId = 5)
        )

        assertTrue(registry.isPhysicalPromotionActive())
        assertTrue(registry.shouldSuppressPhysicalHero(element))

        live = false

        assertFalse(registry.isPhysicalPromotionActive())
        assertFalse(registry.isPhysicalPromotedElement(element))
        assertFalse(registry.shouldSuppressPhysicalHero(element))
        registry.detachPhysicalPromotionLiveness(owner)
    }

}
