package com.rawsmusic.ui.folderfilter

import android.content.Context
import android.os.Environment
import com.rawsmusic.R
import com.rawsmusic.ui.treeview.InMemoryTree
import java.io.File
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ScanLoader(
    private val context: Context,
    private val idCounter: AtomicInteger,
    private val iconResIds: IconResIds
) {
    @Volatile
    var cancelled = false

    fun loadRoots(tree: InMemoryTree<FolderInfo>, selectedPaths: TreeSet<String>, callback: (CollectedScanInfo) -> Unit) {
        thread(name = "FolderScan-Roots") {
            val collected = CollectedScanInfo(null, selectedPaths)
            try {
                val internalPath = Environment.getExternalStorageDirectory().absolutePath
                val internalName = "Internal Storage"

                val internalInfo = StorageFolderInfo(
                    id = idCounter.getAndIncrement(),
                    fullPath = internalPath,
                    displayName = internalName,
                    label = internalName,
                    isStorage = true,
                    notLoaded = true,
                    isRemovable = false,
                    isUnavailable = false,
                    isUsb = false
                )
                internalInfo.iconRes = iconResIds.storageIcon

                collected.addNode(internalInfo)
                tree.addNode(internalInfo, null, 0)

                val storageDir = File("/storage")
                if (storageDir.exists()) {
                    val children = try { storageDir.listFiles() } catch (_: Exception) { null }
                    children?.forEach { child ->
                        if (cancelled) return@thread
                        if (child.isDirectory && child.name != "self" && child.name != "emulated") {
                            val path = child.absolutePath
                            val isSdCard = path.contains("sdcard", ignoreCase = true) ||
                                path.contains("ext_sd", ignoreCase = true)
                            val isUsb = path.contains("usb", ignoreCase = true)

                            val info = StorageFolderInfo(
                                id = idCounter.getAndIncrement(),
                                fullPath = path,
                                displayName = child.name,
                                label = child.name,
                                isStorage = true,
                                notLoaded = true,
                                isRemovable = true,
                                isUnavailable = !child.canRead(),
                                isUsb = isUsb
                            )
                            info.iconRes = when {
                                isUsb -> iconResIds.usbIcon
                                isSdCard -> iconResIds.sdCardIcon
                                else -> iconResIds.storageIcon
                            }
                            collected.addNode(info)
                            tree.addNode(info, internalInfo, 1)
                        }
                    }
                }

                collected.commitToTree(tree)
                callback(collected)
            } catch (e: Exception) {
                collected.isInvalid = true
                callback(collected)
            }
        }
    }

    fun loadChildren(parentInfo: FolderInfo, tree: InMemoryTree<FolderInfo>, selectedPaths: TreeSet<String>, callback: (CollectedScanInfo) -> Unit) {
        thread(name = "FolderScan-Children-${parentInfo.id}") {
            val collected = CollectedScanInfo(parentInfo, selectedPaths)
            try {
                val parentDir = File(parentInfo.fullPath)
                if (!parentDir.exists() || !parentDir.isDirectory) {
                    collected.isInvalid = true
                    callback(collected)
                    return@thread
                }

                val children = try { parentDir.listFiles() } catch (_: SecurityException) { null }
                if (children == null) {
                    collected.isInvalid = true
                    callback(collected)
                    return@thread
                }

                val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase() }

                if (dirs.isEmpty()) {
                    collected.commitToTree(tree)
                    callback(collected)
                    return@thread
                }

                for (dir in dirs) {
                    if (cancelled) return@thread

                    val childHasSubDirs = try {
                        dir.listFiles()?.any { it.isDirectory && !it.name.startsWith(".") } ?: false
                    } catch (_: Exception) { false }

                    val childInfo = FolderInfo(
                        id = idCounter.getAndIncrement(),
                        level = parentInfo.level + 1,
                        fullPath = dir.absolutePath,
                        displayName = dir.name,
                        label = dir.name,
                        isStorage = false,
                        notLoaded = childHasSubDirs
                    )
                    childInfo.iconRes = iconResIds.folderIcon
                    collected.addNode(childInfo)
                    tree.addNode(childInfo, parentInfo, parentInfo.level + 1)
                }

                collected.commitToTree(tree)
                callback(collected)
            } catch (e: Exception) {
                collected.isInvalid = true
                callback(collected)
            }
        }
    }
}
