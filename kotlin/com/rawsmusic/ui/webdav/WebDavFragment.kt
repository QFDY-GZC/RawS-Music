package com.rawsmusic.ui.webdav

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rawsmusic.R
import com.rawsmusic.core.common.base.BaseFragment
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.scanner.webdav.AuthMode
import com.rawsmusic.module.scanner.webdav.WebDavClient
import com.rawsmusic.module.scanner.webdav.WebDavConfig
import com.rawsmusic.module.scanner.webdav.WebDavItem
import com.rawsmusic.ui.songs.PlayerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebDavFragment : BaseFragment<com.rawsmusic.databinding.FragmentWebdavBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> com.rawsmusic.databinding.FragmentWebdavBinding
        get() = { inflater, container, attachToParent ->
            com.rawsmusic.databinding.FragmentWebdavBinding.inflate(inflater, container, attachToParent)
        }

    private val webDavClient = WebDavClient()
    private val items = mutableListOf<WebDavItem>()
    private lateinit var adapter: WebDavAdapter
    private var currentUrl = ""
    private var isLoading = false

    private val config: WebDavConfig
        get() = WebDavConfig(
            url = AppPreferences.WebDav.url,
            username = AppPreferences.WebDav.username,
            password = AppPreferences.WebDav.password,
            authMode = when (AppPreferences.WebDav.authMode) {
                1 -> AuthMode.BASIC
                2 -> AuthMode.DIGEST
                else -> AuthMode.AUTO
            }
        )

    override fun initView() {
        adapter = WebDavAdapter(items, onItemClick = { item -> onItemClicked(item) },
            onMoreClick = { item -> onMoreClicked(item) })
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnBack.setOnClickListener {
            if (!goBack()) {
                try {
                    androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp()
                } catch (_: Exception) {}
            }
        }

        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    override fun initData() {
        val savedUrl = AppPreferences.WebDav.url
        if (savedUrl.isNotBlank()) {
            val startUrl = AppPreferences.WebDav.lastUrl.ifBlank { savedUrl }
            loadDirectory(startUrl)
        } else {
            showEmpty("请配置 WebDAV 连接")
        }
    }

    override fun initListener() {}

    private fun onItemClicked(item: WebDavItem) {
        if (item.isDirectory) {
            val dirUrl = webDavClient.buildFileUrl(config, item.href)
            loadDirectory(dirUrl)
        } else {
            if (isAudioItem(item)) {
                playWebDavItem(item)
            }
        }
    }

    private fun onMoreClicked(item: WebDavItem) {
        if (item.isDirectory) return
        if (!isAudioItem(item)) return

        val options = arrayOf("播放", "添加到播放队列")
        AlertDialog.Builder(requireContext())
            .setTitle(item.fileName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playWebDavItem(item)
                    1 -> addToQueue(item)
                }
            }
            .show()
    }

    private fun playWebDavItem(item: WebDavItem) {
        val fileUrl = webDavClient.buildAuthenticatedUrl(config, item.href)
        val audioFile = AudioFile(
            id = item.href.hashCode().toLong(),
            path = fileUrl,
            title = item.fileName.substringBeforeLast("."),
            artist = "WebDAV",
            album = "WebDAV 远程音乐",
            duration = 0,
            format = item.fileName.substringAfterLast(".", "").uppercase(),
            fileSize = item.size
        )
        val controller = PlayerHolder.controller ?: return
        controller.play(audioFile)
    }

    private fun addToQueue(item: WebDavItem) {
        val fileUrl = webDavClient.buildAuthenticatedUrl(config, item.href)
        val audioFile = AudioFile(
            id = item.href.hashCode().toLong(),
            path = fileUrl,
            title = item.fileName.substringBeforeLast("."),
            artist = "WebDAV",
            album = "WebDAV 远程音乐",
            duration = 0,
            format = item.fileName.substringAfterLast(".", "").uppercase(),
            fileSize = item.size
        )
        val controller = PlayerHolder.controller ?: return
        controller.addToQueue(audioFile)
        Toast.makeText(requireContext(), "已添加到播放队列", Toast.LENGTH_SHORT).show()
    }

    private fun isAudioItem(item: WebDavItem): Boolean {
        val name = item.fileName
        return AudioUtils.isAudioFile(name)
    }

    private fun loadDirectory(url: String) {
        if (isLoading) return
        isLoading = true
        showLoading()
        binding.tvCurrentPath.text = url

        ioScope.launch {
            try {
                val result = webDavClient.listDirectory(config, url)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    currentUrl = url
                    AppPreferences.WebDav.lastUrl = url
                    items.clear()
                    items.addAll(result.sortedWith(compareByDescending<WebDavItem> { it.isDirectory }.thenBy { it.fileName }))
                    adapter.notifyDataSetChanged()

                    if (items.isEmpty()) {
                        showEmpty("此目录为空")
                    } else {
                        binding.emptyView.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                    }
                    binding.loadingView.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    showEmpty("加载失败: ${e.message}")
                }
            }
        }
    }

    private fun goBack(): Boolean {
        val parentUrl = webDavClient.getParentUrl(currentUrl, config) ?: return false
        loadDirectory(parentUrl)
        return true
    }

    private fun showLoading() {
        binding.loadingView.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
    }

    private fun showEmpty(message: String) {
        binding.emptyView.visibility = View.VISIBLE
        binding.loadingView.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.tvEmptyTitle.text = message
    }

    private fun showSettingsDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val etUrl = EditText(context).apply {
            hint = "WebDAV 地址 (如 https://dav.example.com/music/)"
            setText(AppPreferences.WebDav.url)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(etUrl)

        val etUsername = EditText(context).apply {
            hint = "用户名 (可选)"
            setText(AppPreferences.WebDav.username)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(etUsername)

        val etPassword = EditText(context).apply {
            hint = "密码 (可选)"
            setText(AppPreferences.WebDav.password)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(etPassword)

        val authModes = arrayOf("自动选择", "Basic 认证", "Digest 认证")
        var selectedAuthMode = AppPreferences.WebDav.authMode
        val tvAuthMode = TextView(context).apply {
            text = "认证方式: ${authModes[selectedAuthMode]}"
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("选择认证方式")
                    .setSingleChoiceItems(authModes, selectedAuthMode) { dialog, which ->
                        selectedAuthMode = which
                        text = "认证方式: ${authModes[which]}"
                        dialog.dismiss()
                    }
                    .show()
            }
        }
        layout.addView(tvAuthMode)

        val dialog = AlertDialog.Builder(context)
            .setTitle("WebDAV 设置")
            .setView(layout)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("测试连接", null)
            .create()

        dialog.setOnShowListener {
            val btnSave = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val btnTest = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

            btnSave.setOnClickListener {
                val url = etUrl.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(context, "请输入 WebDAV 地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppPreferences.WebDav.url = url
                AppPreferences.WebDav.username = etUsername.text.toString().trim()
                AppPreferences.WebDav.password = etPassword.text.toString().trim()
                AppPreferences.WebDav.lastUrl = ""
                AppPreferences.WebDav.authMode = selectedAuthMode
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadDirectory(url)
            }

            btnTest.setOnClickListener {
                val url = etUrl.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(context, "请输入 WebDAV 地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val testConfig = WebDavConfig(
                    url = url,
                    username = etUsername.text.toString().trim(),
                    password = etPassword.text.toString().trim(),
                    authMode = when (selectedAuthMode) {
                        1 -> AuthMode.BASIC
                        2 -> AuthMode.DIGEST
                        else -> AuthMode.AUTO
                    }
                )
                btnTest.isEnabled = false
                btnTest.text = "测试中…"
                ioScope.launch {
                    val result = webDavClient.testConnection(testConfig)
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "测试连接"
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    inner class WebDavAdapter(
        private val items: List<WebDavItem>,
        private val onItemClick: (WebDavItem) -> Unit,
        private val onMoreClick: (WebDavItem) -> Unit
    ) : RecyclerView.Adapter<WebDavAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvInfo: TextView = view.findViewById(R.id.tvInfo)
            val btnMore: ImageView = view.findViewById(R.id.btnMore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_webdav, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.fileName

            if (item.isDirectory) {
                holder.ivIcon.setImageResource(R.drawable.ic_folder_2_fill)
                holder.tvInfo.text = "文件夹"
                holder.btnMore.visibility = View.GONE
            } else {
                holder.ivIcon.setImageResource(R.drawable.ic_music_note)
                val sizeStr = AudioUtils.formatFileSize(item.size)
                val ext = item.fileName.substringAfterLast(".", "").uppercase()
                holder.tvInfo.text = "$ext · $sizeStr"
                if (isAudioItem(item)) {
                    holder.btnMore.visibility = View.VISIBLE
                    holder.btnMore.setOnClickListener { onMoreClick(item) }
                } else {
                    holder.btnMore.visibility = View.GONE
                }
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
