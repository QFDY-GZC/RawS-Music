package com.rawsmusic.core.ui.scene.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderHierarchyMotionTest {
    @Test
    fun forwardUsesReferenceItemToHeaderRoleEndpoints() {
        val retainedStart = resolveFolderHierarchyRoleMotion(FolderHierarchyDirection.FORWARD, true, 0f)
        val retainedEnd = resolveFolderHierarchyRoleMotion(FolderHierarchyDirection.FORWARD, true, 1f)
        val currentStart = resolveFolderHierarchyRoleMotion(FolderHierarchyDirection.FORWARD, false, 0f)
        val currentEnd = resolveFolderHierarchyRoleMotion(FolderHierarchyDirection.FORWARD, false, 1f)

        assertEquals(1f, retainedStart.scale, 0f)
        assertEquals(1.5f, retainedEnd.scale, 0f)
        assertEquals(0.5f, currentStart.scale, 0f)
        assertEquals(1f, currentEnd.scale, 0f)
        assertEquals(1f, retainedStart.alpha, 0f)
        assertEquals(0f, retainedEnd.alpha, 0f)
        assertEquals(0f, currentStart.alpha, 0f)
        assertEquals(1f, currentEnd.alpha, 0f)
    }

    @Test
    fun backUsesSameSourceDestinationRolesWithReverseSharedArtwork() {
        val retainedStart = resolveFolderHierarchyRoleMotion(FolderHierarchyDirection.BACK, true, 0f)
        val retainedEnd = resolveFolderHierarchyRoleMotion(FolderHierarchyDirection.BACK, true, 1f)
        val currentStart = resolveFolderHierarchyRoleMotion(FolderHierarchyDirection.BACK, false, 0f)
        val currentEnd = resolveFolderHierarchyRoleMotion(FolderHierarchyDirection.BACK, false, 1f)

        assertEquals(1f, retainedStart.scale, 0f)
        assertEquals(0.5f, retainedEnd.scale, 0f)
        assertEquals(1.5f, currentStart.scale, 0f)
        assertEquals(1f, currentEnd.scale, 0f)
    }
}
