package com.rawsmusic.ui.folderfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedFolderAccessPolicyTest {
    @Test
    fun android11KeepsDirectlyReadableFolderWithoutSystemPicker() {
        assertTrue(
            SelectedFolderAccessPolicy.pathsRequiringSaf(
                sdkInt = 30,
                hasAllFilesAccess = false,
                selectedPaths = listOf("/storage/emulated/0/Music"),
                uriByGrantedPath = emptyMap(),
                directlyReadablePaths = listOf("/storage/emulated/0/Music")
            ).isEmpty()
        )
    }

    @Test
    fun android11RequestsTreeGrantOnlyWhenFolderIsActuallyUnreadable() {
        assertEquals(
            listOf("/storage/1234-5678/Music"),
            SelectedFolderAccessPolicy.pathsRequiringSaf(
                sdkInt = 30,
                hasAllFilesAccess = false,
                selectedPaths = listOf("/storage/1234-5678/Music"),
                uriByGrantedPath = emptyMap(),
                directlyReadablePaths = emptyList()
            )
        )
    }

    @Test
    fun parentTreeGrantCoversSelectedDescendant() {
        assertTrue(
            SelectedFolderAccessPolicy.pathsRequiringSaf(
                sdkInt = 30,
                hasAllFilesAccess = false,
                selectedPaths = listOf("/storage/emulated/0/Music/Album"),
                uriByGrantedPath = mapOf("/storage/emulated/0/Music" to "content://tree"),
                directlyReadablePaths = emptyList()
            ).isEmpty()
        )
    }

    @Test
    fun android10UsesSafOnlyForAnUnreadableFolder() {
        assertEquals(
            listOf("/storage/1234-5678/Music"),
            SelectedFolderAccessPolicy.pathsRequiringSaf(
                sdkInt = 29,
                hasAllFilesAccess = false,
                selectedPaths = listOf("/storage/1234-5678/Music"),
                uriByGrantedPath = emptyMap(),
                directlyReadablePaths = emptyList()
            )
        )
    }

    @Test
    fun android9AndBelowStayOnClassicStoragePermission() {
        assertTrue(
            SelectedFolderAccessPolicy.pathsRequiringSaf(
                sdkInt = 28,
                hasAllFilesAccess = false,
                selectedPaths = listOf("/storage/emulated/0/Music"),
                uriByGrantedPath = emptyMap(),
                directlyReadablePaths = emptyList()
            ).isEmpty()
        )
    }

    @Test
    fun allFilesAccessNeverNeedsPerFolderPicker() {
        assertTrue(
            SelectedFolderAccessPolicy.pathsRequiringSaf(
                sdkInt = 35,
                hasAllFilesAccess = true,
                selectedPaths = listOf("/storage/1234-5678/Music"),
                uriByGrantedPath = emptyMap(),
                directlyReadablePaths = emptyList()
            ).isEmpty()
        )
    }
}
