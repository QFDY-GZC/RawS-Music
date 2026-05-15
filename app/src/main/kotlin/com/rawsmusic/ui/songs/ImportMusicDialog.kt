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
import com.rawsmusic.databinding.DialogImportMusicBinding
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音乐导入对话框
 * - 选择文件夹后显示目录树
 * - 目录树可展开/折叠，每个目录有勾选框
 * - 点击"开始扫描"后按勾选项扫描
 */
class ImportMusicDialog(
    private val fragment: SongsFragment,
    private val onLaunchFolderPicker: () -> Unit
) {
    private var dialog: AlertDialog? = null
    private var binding: DialogImportMusicBinding? = null
    private var selectedRootPath: String? = null
    private val dirNodes = mutableListOf<DirNode>()

    /** 目录树节点 */
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

        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog?.show()
    }

    private fun onFolderSelected(path: String) {
        selectedRootPath = path
        binding?.tvSelectedFolder?.text = "已选择：$path"
        binding?.tvSelectedFolder?.visibility = View.VISIBLE

        // 构建目录树
        buildDirectoryTree(path)

        binding?.dirTreeContainer?.visibility = View.VISIBLE
        binding?.btnStartScan?.isEnabled = true
    }

    private fun buildDirectoryTree(rootPath: String) {
        dirNodes.clear()
        binding?.dirTreeList?.removeAllViews()

        val rootFile = File(rootPath)
        if (!rootFile.exists() || !rootFile.isDirectory) {
            binding?.tvSelectedFolder?.text = "文件夹不存在或无法访问"
            return
        }

        val rootNode = scanDirectory(rootFile, 0)
        if (rootNode != null) {
            dirNodes.add(rootNode)
            renderNode(rootNode, binding!!.dirTreeList)
        }
    }

    private fun scanDirectory(dir: File, depth: Int): DirNode? {
        if (!dir.isDirectory) return null
        if (dir.name.startsWith(".") && depth > 0) return null // 跳过隐藏目录

        val node = DirNode(
            path = dir.absolutePath,
            name = dir.name,
            depth = depth,
            enabled = true
        )

        // 限制深度，避免嵌套过深
        if (depth < 6) {
            try {
                val subDirs = dir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() }
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

        // 缩进
        val paddingStart = (node.depth * 24 * density).toInt()
        itemView.setPadding(paddingStart, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)

        tvDirName.text = if (node.depth == 0) node.path.substringAfterLast("/") else node.name
        cbEnabled.isChecked = node.enabled

        // 箭头可见性和方向
        if (node.children.isNotEmpty()) {
            ivExpand.visibility = View.VISIBLE
            ivExpand.rotation = if (node.expanded) 0f else -90f
        } else {
            ivExpand.visibility = View.INVISIBLE
        }

        // 文件夹图标：叶子节点用不同图标
        ivFolderIcon.setImageResource(
            if (node.children.isEmpty()) android.R.drawable.ic_menu_view
            else android.R.drawable.ic_menu_set_as
        )

        // 展开/折叠
        ivExpand.setOnClickListener {
            node.expanded = !node.expanded
            ivExpand.rotation = if (node.expanded) 0f else -90f
            updateChildrenVisibility(node)
        }

        // 勾选框
        cbEnabled.setOnCheckedChangeListener { _, isChecked ->
            node.enabled = isChecked
            // 级联：勾选/取消子目录
            setChildrenEnabled(node, isChecked)
        }

        container.addView(itemView)
        node.itemView = itemView

        // 子目录容器
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

        // 渲染子节点
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

    /** 收集所有被勾选的目录路径 */
    private fun collectEnabledPaths(nodes: List<DirNode>): List<String> {
        val paths = mutableListOf<String>()
        for (node in nodes) {
            if (node.enabled) {
                paths.add(node.path)
            }
        }
        return paths
    }

    private fun startScan() {
        if (selectedRootPath == null) return
        val enabledPaths = collectEnabledPaths(dirNodes)
        if (enabledPaths.isEmpty()) {
            Toast.makeText(fragment.requireContext(), "请至少勾选一个目录", Toast.LENGTH_SHORT).show()
            return
        }

        // 保存扫描路径配置
        val existingPaths = AppPreferences.UI.scanPaths.toMutableList()
        for (path in enabledPaths) {
            if (path !in existingPaths) {
                existingPaths.add(path)
            }
        }
        AppPreferences.UI.scanPaths = existingPaths

        // 显示扫描进度
        binding?.btnStartScan?.isEnabled = false
        binding?.btnSelectFolder?.isEnabled = false
        binding?.scanProgressContainer?.visibility = View.VISIBLE
        binding?.scanProgressBar?.progress = 0

        // 执行扫描
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
