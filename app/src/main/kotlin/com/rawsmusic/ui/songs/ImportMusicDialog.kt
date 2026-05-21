package com.rawsmusic.ui.songs

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.R
import com.rawsmusic.core.common.utils.CjkSortUtils
import com.rawsmusic.databinding.DialogImportMusicBinding
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImportMusicDialog(
    private val fragment: SongsFragment,
    private val onLaunchFolderPicker: () -> Unit
) {
    private var dialog: AlertDialog? = null
    private var binding: DialogImportMusicBinding? = null
    private val rootPaths = mutableListOf<String>()
    private val dirNodes = mutableListOf<DirNode>()

    data class DirNode(
        val path: String,
        val name: String,
        val depth: Int,
        var enabled: Boolean = true,
        var expanded: Boolean = false,
        val children: MutableList<DirNode> = mutableListOf(),
        var itemView: View? = null,
        var childContainer: LinearLayout? = null
    )

    fun onFolderResult(uri: Uri?) {
        uri ?: return
        try {
            fragment.requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val realPath = extractRealPathFromUri(uri)
        if (realPath.isNullOrBlank()) {
            Toast.makeText(fragment.requireContext(), "无法识别该文件夹路径", Toast.LENGTH_SHORT).show()
            return
        }

        onFolderSelected(realPath)
    }

    fun show() {
        val ctx = fragment.requireContext()
        binding = DialogImportMusicBinding.inflate(LayoutInflater.from(ctx), null, false)

        binding!!.btnSelectFolder.text = "新增文件夹"
        binding!!.btnSelectFolder.setOnClickListener {
            onLaunchFolderPicker()
        }

        binding!!.btnCancel.setOnClickListener {
            dialog?.dismiss()
        }

        binding!!.btnStartScan.setOnClickListener {
            startScan()
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(binding!!.root)
            .setCancelable(true)
            .create()

        dialog?.window?.setBackgroundDrawableResource(R.drawable.dialog_bg_rounded)
        dialog?.show()

        val window = dialog?.window
        if (window != null) {
            val dm = ctx.resources.displayMetrics
            val width = (dm.widthPixels * 0.85).toInt()
            val height = (dm.heightPixels * 0.8).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        rootPaths.clear()
        dirNodes.clear()
        binding?.dirTreeList?.removeAllViews()
        binding?.dirTreeContainer?.visibility = View.GONE
        binding?.btnStartScan?.isEnabled = false

        // 优先从 rootScanPaths 恢复原始根目录，保持目录树结构
        val savedRootPaths = AppPreferences.UI.rootScanPaths
        val savedScanPaths = AppPreferences.UI.scanPaths.toSet()
        if (savedRootPaths.isNotEmpty()) {
            for (path in savedRootPaths) {
                if (File(path).exists() && path !in rootPaths) {
                    rootPaths.add(path)
                    buildDirectoryTree(path, savedScanPaths)
                }
            }
        } else if (savedScanPaths.isNotEmpty()) {
            // 兼容旧数据：没有 rootScanPaths 时，用 scanPaths 作为根目录
            for (path in savedScanPaths) {
                if (File(path).exists() && path !in rootPaths) {
                    rootPaths.add(path)
                    buildDirectoryTree(path)
                }
            }
        }
        if (rootPaths.isNotEmpty()) {
            updateFolderListDisplay()
            binding?.dirTreeContainer?.visibility = View.VISIBLE
            binding?.btnStartScan?.isEnabled = true
        }
    }

    private fun onFolderSelected(path: String) {
        if (path in rootPaths) {
            Toast.makeText(fragment.requireContext(), "该文件夹已添加", Toast.LENGTH_SHORT).show()
            return
        }

        rootPaths.add(path)
        AppPreferences.UI.lastSelectedFolderPath = path

        buildDirectoryTree(path)

        updateFolderListDisplay()
        binding?.dirTreeContainer?.visibility = View.VISIBLE
        binding?.btnStartScan?.isEnabled = true
    }

    private fun updateFolderListDisplay() {
        if (rootPaths.isEmpty()) {
            binding?.tvSelectedFolder?.visibility = View.GONE
        } else {
            binding?.tvSelectedFolder?.visibility = View.VISIBLE
            val displayText = if (rootPaths.size == 1) {
                "已选择：${rootPaths[0]}"
            } else {
                "已选择 ${rootPaths.size} 个文件夹"
            }
            binding?.tvSelectedFolder?.text = displayText
        }
    }

    private fun buildDirectoryTree(rootPath: String, enabledPaths: Set<String>? = null) {
        val rootFile = File(rootPath)
        if (!rootFile.exists() || !rootFile.isDirectory) return

        val savedPaths = enabledPaths ?: AppPreferences.UI.scanPaths.toSet()
        val rootNode = scanDirectory(rootFile, 0)
        if (rootNode != null) {
            restoreEnabledState(rootNode, savedPaths)
            dirNodes.add(rootNode)
            renderNode(rootNode, binding!!.dirTreeList)
        }
    }

    private fun restoreEnabledState(node: DirNode, savedPaths: Set<String>) {
        if (node.path in savedPaths) {
            node.enabled = true
            setChildrenEnabled(node, true)
        } else {
            val hasChildInSaved = savedPaths.any { it.startsWith(node.path + "/") }
            if (hasChildInSaved) {
                node.enabled = false
                node.expanded = true
                for (child in node.children) {
                    restoreEnabledState(child, savedPaths)
                }
            } else {
                node.enabled = true
            }
        }
    }

    private fun scanDirectory(dir: File, depth: Int): DirNode? {
        if (!dir.isDirectory) return null
        if (dir.name.startsWith(".") && depth > 0) return null

        val node = DirNode(
            path = dir.absolutePath,
            name = dir.name,
            depth = depth,
            enabled = true
        )

        if (depth < 6) {
            try {
                val subDirs = dir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { CjkSortUtils.sortKey(it.name) }
                    ?: emptyList()

                for (subDir in subDirs) {
                    scanDirectory(subDir, depth + 1)?.let { node.children.add(it) }
                }
            } catch (_: SecurityException) {}
        }

        return node
    }

    private fun renderNode(node: DirNode, container: ViewGroup) {
        val ctx = fragment.requireContext()
        val density = ctx.resources.displayMetrics.density
        val itemView = LayoutInflater.from(ctx).inflate(R.layout.item_dir_tree, container, false)

        val ivExpand = itemView.findViewById<ImageView>(R.id.ivExpand)
        val ivFolderIcon = itemView.findViewById<ImageView>(R.id.ivFolderIcon)
        val tvDirName = itemView.findViewById<TextView>(R.id.tvDirName)
        val cbEnabled = itemView.findViewById<CheckBox>(R.id.cbEnabled)

        val paddingStart = (node.depth * 24 * density).toInt()
        itemView.setPadding(paddingStart, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)

        tvDirName.text = if (node.depth == 0) node.path.substringAfterLast("/") else node.name
        cbEnabled.isChecked = node.enabled

        if (node.children.isNotEmpty()) {
            ivExpand.visibility = View.VISIBLE
            ivExpand.rotation = if (node.expanded) 0f else -90f
        } else {
            ivExpand.visibility = View.INVISIBLE
        }

        ivFolderIcon.setImageResource(R.drawable.ic_folder_2_fill)

        ivExpand.setOnClickListener {
            node.expanded = !node.expanded
            ivExpand.rotation = if (node.expanded) 0f else -90f
            updateChildrenVisibility(node)
        }

        cbEnabled.setOnCheckedChangeListener { _, isChecked ->
            node.enabled = isChecked
            setChildrenEnabled(node, isChecked)
        }

        container.addView(itemView)
        node.itemView = itemView

        val childContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = if (node.expanded) View.VISIBLE else View.GONE
        }
        container.addView(childContainer)
        node.childContainer = childContainer

        for (child in node.children) {
            renderNode(child, childContainer)
        }
    }

    private fun updateChildrenVisibility(node: DirNode) {
        node.childContainer?.visibility = if (node.expanded) View.VISIBLE else View.GONE
        if (node.expanded) {
            for (child in node.children) {
                updateChildrenVisibility(child)
            }
        }
    }

    private fun setChildrenEnabled(node: DirNode, enabled: Boolean) {
        for (child in node.children) {
            child.enabled = enabled
            child.itemView?.let {
                it.findViewById<CheckBox>(R.id.cbEnabled)?.setOnCheckedChangeListener(null)
                it.findViewById<CheckBox>(R.id.cbEnabled)?.isChecked = enabled
                it.findViewById<CheckBox>(R.id.cbEnabled)?.setOnCheckedChangeListener { _, isChecked ->
                    child.enabled = isChecked
                    setChildrenEnabled(child, isChecked)
                }
            }
            setChildrenEnabled(child, enabled)
        }
    }

    private fun collectEnabledPaths(nodes: List<DirNode>): List<String> {
        val paths = mutableListOf<String>()
        for (node in nodes) {
            if (node.enabled) {
                paths.add(node.path)
            } else {
                paths.addAll(collectEnabledPaths(node.children))
            }
        }
        return paths
    }

    private fun startScan() {
        val enabledPaths = collectEnabledPaths(dirNodes)
        if (enabledPaths.isEmpty()) {
            Toast.makeText(fragment.requireContext(), "请至少勾选一个目录", Toast.LENGTH_SHORT).show()
            return
        }

        val newPaths = enabledPaths.toMutableList()
        val existingPaths = AppPreferences.UI.scanPaths.toMutableList()
        val rootPathSet = rootPaths.toSet()
        val keptPaths = existingPaths.filter { it !in rootPathSet && rootPathSet.none { root -> it.startsWith(root + "/") } }
        newPaths.addAll(keptPaths)
        AppPreferences.UI.scanPaths = newPaths
        // 保存原始根目录，用于下次重建目录树
        val existingRoots = AppPreferences.UI.rootScanPaths.toMutableSet()
        existingRoots.addAll(rootPaths)
        AppPreferences.UI.rootScanPaths = existingRoots.toList()

        binding?.btnStartScan?.isEnabled = false
        binding?.btnSelectFolder?.isEnabled = false
        binding?.scanProgressContainer?.visibility = View.VISIBLE
        binding?.scanProgressBar?.progress = 0

        fragment.lifecycleScope.launch(Dispatchers.Main) {
            try {
                val result = withContext(Dispatchers.IO) {
                    com.rawsmusic.module.data.repository.MusicRepository.clearAll()
                    val songs = mutableListOf<com.rawsmusic.core.common.model.AudioFile>()
                    com.rawsmusic.module.scanner.MediaStoreScanner.scan(
                        fragment.requireContext(), enabledPaths, quickScan = false
                    ).collect { progress ->
                        when (progress) {
                            is com.rawsmusic.module.scanner.ScanProgress.Started -> {
                                launch(Dispatchers.Main) {
                                    binding?.tvScanProgress?.text = "发现 ${progress.totalEstimated} 首音频"
                                }
                            }
                            is com.rawsmusic.module.scanner.ScanProgress.Progress -> {
                                launch(Dispatchers.Main) {
                                    val pct = if (progress.total > 0) (progress.scanned * 100 / progress.total) else 0
                                    binding?.scanProgressBar?.progress = pct
                                    binding?.tvScanProgress?.text = "$pct%"
                                }
                            }
                            is com.rawsmusic.module.scanner.ScanProgress.Completed -> {
                                songs.addAll(progress.songs)
                            }
                            is com.rawsmusic.module.scanner.ScanProgress.Error -> {
                                launch(Dispatchers.Main) {
                                    binding?.tvScanProgress?.text = "错误: ${progress.message}"
                                }
                            }
                        }
                    }
                    com.rawsmusic.module.data.repository.MusicRepository.replaceAllSongs(songs)
                    songs.size
                }

                Toast.makeText(fragment.requireContext(), "扫描完成，共 $result 首歌曲", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            } catch (e: Exception) {
                Toast.makeText(fragment.requireContext(), "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
                binding?.btnStartScan?.isEnabled = true
                binding?.btnSelectFolder?.isEnabled = true
            }
        }
    }

    private fun extractRealPathFromUri(uri: android.net.Uri): String? {
        val encodedPath = uri.encodedPath ?: uri.path
        if (encodedPath != null) {
            val segments = encodedPath.split("/").filter { it.isNotBlank() }
            val treeIdx = segments.indexOf("tree")
            if (treeIdx >= 0 && treeIdx + 1 < segments.size) {
                val part = java.net.URLDecoder.decode(segments[treeIdx + 1], "UTF-8")
                if (part.contains(":")) {
                    val colonIdx = part.indexOf(":")
                    val storage = part.substring(0, colonIdx)
                    val folder = part.substring(colonIdx + 1)
                    return when (storage) {
                        "primary" -> "/storage/emulated/0/$folder".trimEnd('/')
                        else -> "/storage/$storage/$folder".trimEnd('/')
                    }
                }
                return "/$part".trimEnd('/')
            }
        }
        return null
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}
