package com.rawsmusic.core.ui.scene

import com.rawsmusic.core.ui.widget.virtuallist.ArtistAllSongsVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.stableVirtualListHash64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.geometry.Rect

class ArtistAllSongsNavigationTest {
    @Test
    fun specialArtistRowUsesTheDestinationHeaderCoverIdentity() {
        val artistKey = "artist/test"
        val row = ArtistAllSongsVirtualListItem(
            artistKey = artistKey,
            title = "所有艺术家歌曲",
            cover = "artwork-key",
            songCountText = "3首歌曲",
        )

        assertEquals(
            "cover:artist-all-songs:${stableVirtualListHash64(artistKey)}",
            row.sharedCoverElementId,
        )
    }

    @Test
    fun allSongsIsASeparateDetailSceneAndReturnsToArtistAlbums() {
        val allSongs = NavScene.fromId(52)
        assertEquals("artist_all_songs", allSongs?.tag)
        assertTrue(allSongs?.isDetail() == true)
        val navigation = NavigationState()
        navigation.navigateTo(NavScene.ARTISTS)
        navigation.navigateTo(NavScene.ARTIST_DETAIL, "artist%2Ftest")
        navigation.navigateTo(requireNotNull(allSongs), "artist%2Ftest")
        assertEquals(NavScene.ARTIST_DETAIL, navigation.backStack[navigation.backStack.lastIndex - 1])
        assertEquals("artist%2Ftest", navigation.transitionToArgument)
    }

    @Test
    fun nestedArtistDetailCanClickWhileItsParentHeaderIsStillPromoted() {
        val registry = SharedCoverRegistry()
        val owner = Any()
        registry.attachPhysicalPromotionStarter(owner) { _, _, _ -> true }
        val parentCover = "cover:artist:11"
        val source = SharedCoverSnapshot(
            sceneId = NavScene.ARTISTS.name,
            elementId = parentCover,
            boundsInWindow = Rect(0f, 0f, 80f, 80f),
            coverKey = "artwork",
            radiusDp = 40f,
            physicalSlotId = 1,
        )
        val header = source.copy(sceneId = NavScene.ARTIST_DETAIL.name, physicalSlotId = -1)
        registry.prepareTransition("artists-to-detail", source, header)
        assertTrue(registry.promotePreparedPhysicalSource("artists-to-detail"))

        assertTrue(registry.canStartPhysicalSharedClick(NavScene.ARTIST_DETAIL.name))
        assertFalse(registry.canStartPhysicalSharedClick(NavScene.ALBUM_DETAIL.name))
    }

    @Test
    fun returningFromAllSongsRestoresTheArtistsToDetailSharedOwner() {
        val registry = SharedCoverRegistry()
        val owner = Any()
        val parentId = "cover:artist:11"
        val childId = "cover:artist-all-songs:11"
        val stashed = mutableSetOf<String>()
        registry.attachPhysicalPromotionStarter(owner) { _, _, _ -> true }
        registry.attachPhysicalPromotionStasher(owner) { id -> stashed.add(id) }
        registry.attachPhysicalPromotionActivator(owner) { id, _, _ -> stashed.remove(id) }
        registry.attachPhysicalReturnFinalizer(owner) { true }

        fun endpoint(scene: NavScene, id: String, slot: Int) = SharedCoverSnapshot(
            sceneId = scene.name,
            elementId = id,
            boundsInWindow = Rect(0f, 0f, 80f, 80f),
            coverKey = "artwork",
            radiusDp = 40f,
            physicalSlotId = slot,
        )

        val artistRow = endpoint(NavScene.ARTISTS, parentId, 1)
        val artistHeader = endpoint(NavScene.ARTIST_DETAIL, parentId, -1)
        registry.register(NavScene.ARTIST_DETAIL.name, parentId, owner, artistHeader)
        registry.prepareTransition("artists-to-detail", artistRow, artistHeader)
        assertTrue(registry.promotePreparedPhysicalSource("artists-to-detail"))

        val allSongsRow = endpoint(NavScene.ARTIST_DETAIL, childId, 2)
        val allSongsHeader = endpoint(NavScene.ARTIST_ALL_SONGS, childId, -1)
        registry.freezeSnapshot(allSongsRow)
        assertTrue(registry.isPhysicalStashedElement(parentId))
        registry.prepareTransition("detail-to-all", allSongsRow, allSongsHeader)
        assertTrue(registry.promotePreparedPhysicalSource("detail-to-all"))
        registry.prepareTransition("all-to-detail", allSongsHeader, allSongsRow)
        assertTrue(registry.finishPhysicalReturn(childId))

        assertTrue(registry.isPhysicalPromotedElement(parentId))
        assertFalse(registry.isPhysicalStashedElement(parentId))
        assertEquals(NavScene.ARTISTS.name, registry.collectionSessionSnapshot()?.listSceneId)
        assertEquals(NavScene.ARTIST_DETAIL.name, registry.collectionSessionSnapshot()?.detailSceneId)
        assertTrue(registry.captureLiveCollectionReturnSource(
            NavScene.ARTIST_DETAIL.name,
            NavScene.ARTISTS.name,
        ))
    }

    @Test
    fun sceneOnlyChildReturnKeepsTheParentHolderUntilRouteCommit() {
        val registry = SharedCoverRegistry()
        val owner = Any()
        val parentId = "cover:artist:11"
        val childId = "cover:artist-all-songs:11"
        val stashed = mutableSetOf<String>()
        registry.attachPhysicalPromotionStarter(owner) { _, _, _ -> true }
        registry.attachPhysicalPromotionStasher(owner) { id -> stashed.add(id) }
        registry.attachPhysicalPromotionActivator(owner) { id, _, _ -> stashed.remove(id) }
        registry.attachPhysicalPromotionCanceller(owner) { true }

        fun endpoint(scene: NavScene, id: String, slot: Int) = SharedCoverSnapshot(
            sceneId = scene.name,
            elementId = id,
            boundsInWindow = Rect(0f, 0f, 80f, 80f),
            coverKey = "artwork",
            radiusDp = 40f,
            physicalSlotId = slot,
        )
        val parentHeader = endpoint(NavScene.ARTIST_DETAIL, parentId, -1)
        registry.prepareTransition(
            "artists-to-detail", endpoint(NavScene.ARTISTS, parentId, 1), parentHeader,
        )
        assertTrue(registry.promotePreparedPhysicalSource("artists-to-detail"))
        registry.register(NavScene.ARTIST_DETAIL.name, parentId, owner, parentHeader)
        val childRow = endpoint(NavScene.ARTIST_DETAIL, childId, 2)
        registry.freezeSnapshot(childRow)
        registry.prepareTransition(
            "detail-to-all", childRow, endpoint(NavScene.ARTIST_ALL_SONGS, childId, -1),
        )
        assertTrue(registry.promotePreparedPhysicalSource("detail-to-all"))

        assertTrue(registry.retireNestedChildForSceneOnlyReturn(
            NavScene.ARTIST_ALL_SONGS.name, NavScene.ARTIST_DETAIL.name,
        ))
        assertTrue(registry.isPhysicalStashedElement(parentId))
        assertEquals(childId, registry.collectionSessionSnapshot()?.elementId)
        assertTrue(registry.restoreNestedParentAfterCommittedBack(
            NavScene.ARTIST_ALL_SONGS.name, NavScene.ARTIST_DETAIL.name,
        ))
        assertTrue(registry.isPhysicalPromotedElement(parentId))
        assertEquals(parentId, registry.collectionSessionSnapshot()?.elementId)
    }
}
