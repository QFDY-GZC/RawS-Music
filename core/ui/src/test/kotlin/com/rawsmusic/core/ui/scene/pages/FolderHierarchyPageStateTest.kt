package com.rawsmusic.core.ui.scene.pages

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.FolderHierarchyNode
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.virtuallist.ReferenceRegisteredVirtualList
import org.junit.Assert.*
import org.junit.Test

class FolderHierarchyPageStateTest {
    private fun interactiveTransition(content: Boolean) = FolderHierarchyTransition(
        serial = 1,
        direction = FolderHierarchyDirection.BACK,
        retainedProvider = ReferenceRegisteredVirtualList(NavScene.FOLDER_HIERARCHY),
        sourcePath = "/Music/Albums",
        targetPath = "/Music",
        interactive = true,
        contentGesture = content,
    )

    @Test fun onlyTheOwningRecognizerMayUpdateProgress() {
        for (content in listOf(true, false)) {
            val transition = interactiveTransition(content)
            assertTrue(transition.acceptsGestureProgress(content))
            assertFalse(transition.acceptsGestureProgress(!content))
        }
    }

    @Test fun releasedGestureCannotBeReplayedDuringTargetPreparation() {
        for (content in listOf(true, false)) {
            val released = interactiveTransition(content).copy(gestureReleased = true)
            val prepared = released.copy(targetPublished = true, readyForMotion = true)
            assertFalse(prepared.acceptsGestureProgress(content))
            assertFalse(prepared.acceptsGestureProgress(!content))
            assertTrue(prepared.gestureReleased)
        }
    }

    @Test fun nonInteractiveNavigationRejectsGestureProgress() {
        val transition = interactiveTransition(false).copy(interactive = false)
        assertFalse(transition.acceptsGestureProgress(false))
        assertFalse(transition.acceptsGestureProgress(true))
    }

    @Test fun layoutAcknowledgementIsScopedToTransactionAndDirectory() {
        val state = FolderHierarchyPageState()
        state.drawnLayouts.acknowledgeDraw(1 to null)
        assertTrue(state.drawnLayouts.isReady(1 to null))
        assertFalse(state.drawnLayouts.isReady(1 to "/Music"))
        assertFalse(state.drawnLayouts.isReady(2 to null))
        state.drawnLayouts.invalidate()
        assertFalse(state.drawnLayouts.isReady(1 to null))
    }

    @Test fun preparationAndVisibleControllerReuseOneIndex() {
        val state = FolderHierarchyPageState("/Music/Albums")
        val songs = ArrayList<AudioFile>()
        val folders = ArrayList<FolderHierarchyNode>()
        val prepared = state.indexFor(songs, folders)
        assertSame(prepared, state.indexFor(songs, folders))
        assertEquals("/Music/Albums", state.pathState.value)
        state.scrollByPath["/Music/Albums"] = 420.5f
        assertSame(prepared, state.indexFor(songs, folders))
        assertEquals(420.5f, state.scrollByPath["/Music/Albums"]!!, 0f)
    }

    @Test fun replacementLibraryInvalidatesTheIndex() {
        val state = FolderHierarchyPageState()
        val songs = ArrayList<AudioFile>()
        val folders = ArrayList<FolderHierarchyNode>()
        val previous = state.indexFor(songs, folders)
        val replacement = state.indexFor(ArrayList(), folders)
        assertNotSame(previous, replacement)
    }
}
