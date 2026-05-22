package com.rawsmusic.ui.songs

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.lifecycle.LifecycleEventObserver
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.rawsmusic.core.common.base.BaseFragment
import com.rawsmusic.core.common.ext.fadeIn
import com.rawsmusic.core.common.ext.fadeOut
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.CjkSortUtils
import com.rawsmusic.core.ui.R as UiR
import com.rawsmusic.core.ui.adapter.SongAdapter
import com.rawsmusic.databinding.FragmentSongsBinding
import com.rawsmusic.ui.songs.PlayerHolder
import com.rawsmusic.module.data.prefs.FontManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongsFragment : BaseFragment<FragmentSongsBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentSongsBinding
        get() = { inflater, container, attachToParent ->
            FragmentSongsBinding.inflate(inflater, container, attachToParent)
        }

    private val viewModel: SongsViewModel by viewModels()
    private lateinit var songAdapter: SongAdapter
    private var _isSearchMode = false
    val isSearchMode: Boolean get() = _isSearchMode
    private var searchEditText: EditText? = null
    private var importDialog: ImportMusicDialog? = null
    private var searchOriginallyVisible = false

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        importDialog?.onFolderResult(uri)
    }

    /** 手势返回回调 — 当编辑模式时拦截返回键退出编辑 */
    private val editModeBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            songAdapter.exitEditMode()
            updateEditBar()
        }
    }

    private var activePopup: PopupWindow? = null

    override fun initView() {
        importDialog = ImportMusicDialog(this) {
            folderPickerLauncher.launch(null)
        }
        
        songAdapter = SongAdapter(
            onSongClick = { song, position ->
                val currentPlayingId = PlayerHolder.controller?.currentSong?.value?.id
                if (song.id == currentPlayingId) {
                    (activity as? com.rawsmusic.MainActivity)?.openPlayPageFromSongClick()
                } else {
                    viewModel.playSong(song, position)
                }
            },
            onLongClick = { song, position ->
                showSongActionPopup(song, position)
                true
            },
            onSelectionChanged = { selectedIds ->
                updateEditCount(selectedIds.size)
            }
        )

        songAdapter.fontApplier = { FontManager.applyToTextView(it) }

        // 注册手势返回回调
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, editModeBackCallback)

        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }

        binding.btnHamburgerContainer.setOnClickListener {
            (activity as? com.rawsmusic.MainActivity)?.toggleSideMenu()
        }

        binding.alphabetIndex.onLetterSelected = listener@{ letter ->
            val songs = viewModel.songs.value ?: return@listener
            val index = songs.indexOfFirst { song ->
                val firstChar = song.displayName.firstOrNull() ?: '#'
                val key = categorizeForIndex(firstChar)
                key == letter
            }
            if (index >= 0) {
                (binding.recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(index, 0)
            }
        }

        // 批量编辑栏按钮
        binding.btnCancelEdit.setOnClickListener {
            songAdapter.exitEditMode()
            updateEditBar()
        }

        binding.btnSelectAll.setOnClickListener {
            songAdapter.selectAll()
            updateEditCount(songAdapter.selectedIds.size)
        }

        binding.btnDeleteSelected.setOnClickListener {
            deleteSelectedSongs()
        }
    }

    override fun initData() {
        viewModel.loadSongs()

        val bgListener: (Boolean) -> Unit = { _ ->
            songAdapter.notifyVisibleItemsChanged()
        }
        com.rawsmusic.core.ui.theme.ThemeManager.addOnBackgroundChangeListener(bgListener)
        viewLifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                com.rawsmusic.core.ui.theme.ThemeManager.removeOnBackgroundChangeListener(bgListener)
            }
        })
    }

    override fun initObserver() {
        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            songAdapter.submitList(songs)
            binding.alphabetIndex.updateIndexFromTitles(songs.map { it.displayName })
            if (songs.isEmpty()) {
                binding.emptyView.fadeIn()
                binding.recyclerView.fadeOut()
            } else {
                binding.emptyView.fadeOut()
                binding.recyclerView.fadeIn()
            }
        }

        // 数据源变化时自动重新加载（扫描完成后刷新列表）
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            com.rawsmusic.module.data.repository.MusicRepository.songs.collect {
                viewModel.loadSongs()
            }
        }

        // 使用 viewLifecycleOwner.lifecycleScope 确保协程跟随视图生命周期自动取消
        // 避免视图销毁后仍访问 binding 导致 NPE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            PlayerHolder.controller?.currentSong?.collect { song ->
                song?.let {
                    songAdapter.currentPlayingId = it.id
                    val songs = viewModel.songs.value ?: return@collect
                    val index = songs.indexOfFirst { s -> s.id == it.id }
                    if (index >= 0) {
                        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
                        layoutManager?.scrollToPositionWithOffset(index, 0)
                    }
                }
            }
        }
    }

    override fun initListener() {
        binding.swipeRefresh.setOnRefreshListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val customPaths = com.rawsmusic.module.data.prefs.AppPreferences.UI.scanPaths
                    com.rawsmusic.module.scanner.MediaStoreScanner.scan(requireContext(), customPaths, quickScan = true)
                        .collect { progress ->
                            if (progress is com.rawsmusic.module.scanner.ScanProgress.Completed) {
                                com.rawsmusic.module.data.repository.MusicRepository.insertSongs(progress.songs)
                            }
                        }
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    viewModel.loadSongs()
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }

        binding.btnImport.setOnClickListener {
            importDialog?.show()
        }

        binding.btnFolder.setOnClickListener {
            importDialog?.show()
        }

        binding.btnSearch.setOnClickListener {
            toggleSearch()
        }
    }

    /** 更新编辑栏状态 */
    private fun updateEditBar() {
        val editing = songAdapter.isEditMode
        binding.normalTitleBar.visibility = if (editing) View.GONE else View.VISIBLE
        binding.editTitleBar.visibility = if (editing) View.VISIBLE else View.GONE
        if (editing) {
            updateEditCount(songAdapter.selectedIds.size)
        }
        editModeBackCallback.isEnabled = editing
    }

    private fun updateEditCount(count: Int) {
        binding.tvEditCount.text = "已选择 $count 首"
    }

    private fun deleteSelectedSongs() {
        val selectedSongs = songAdapter.getSelectedSongs()
        if (selectedSongs.isEmpty()) {
            Toast.makeText(requireContext(), "未选择歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        var deletedCount = 0
        selectedSongs.forEach { song ->
            if (com.rawsmusic.module.data.repository.MusicRepository.deleteSongFromDevice(requireContext(), song)) {
                deletedCount++
            }
        }
        songAdapter.exitEditMode()
        updateEditBar()
        viewModel.loadSongs()
        Toast.makeText(requireContext(), "已删除 $deletedCount 首", Toast.LENGTH_SHORT).show()
    }

    private fun showSongActionPopup(song: AudioFile, position: Int) {
        activePopup?.dismiss()
        val ctx = context ?: return
        val density = resources.displayMetrics.density

        val popupView = LayoutInflater.from(ctx).inflate(UiR.layout.popup_song_action, null)
        val popup = PopupWindow(popupView,
            (200 * density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true).apply {
            elevation = 8f * density
            isOutsideTouchable = true
            setOnDismissListener { activePopup = null }
        }

        popupView.findViewById<View>(UiR.id.tvPlayNext).setOnClickListener {
            popup.dismiss()
            PlayerHolder.controller?.playNext(song)
            Toast.makeText(ctx, "已添加到下一首播放", Toast.LENGTH_SHORT).show()
        }

        popupView.findViewById<View>(UiR.id.tvAddToPlaylist).setOnClickListener {
            popup.dismiss()
            showAddToPlaylistDialog(song)
        }

        popupView.findViewById<View>(UiR.id.tvDelete).setOnClickListener {
            popup.dismiss()
            confirmDeleteSong(song)
        }

        binding.recyclerView.post {
            val itemView = binding.recyclerView.layoutManager?.findViewByPosition(position)
            if (itemView != null) {
                val location = IntArray(2)
                itemView.getLocationOnScreen(location)
                val anchorX = location[0] + itemView.width / 2 - (100 * density).toInt()
                val anchorY = location[1] + itemView.height
                popup.showAtLocation(binding.recyclerView, Gravity.NO_GRAVITY, anchorX, anchorY)
                activePopup = popup
            }
        }
    }

    private fun confirmDeleteSong(song: AudioFile) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除歌曲")
            .setMessage("确定要删除「${song.title}」吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                val deleted = com.rawsmusic.module.data.repository.MusicRepository.deleteSongFromDevice(requireContext(), song)
                viewModel.loadSongs()
                Toast.makeText(requireContext(), if (deleted) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddToPlaylistDialog(song: AudioFile) {
        val ctx = context ?: return
        val playlistStore = com.rawsmusic.module.data.prefs.PlaylistStore.getInstance(ctx)
        val playlists = playlistStore.playlists.value
        if (playlists.isEmpty()) {
            Toast.makeText(ctx, "暂无歌单", Toast.LENGTH_SHORT).show()
            return
        }
        val names = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("添加到歌单")
            .setItems(names) { _, which ->
                val playlist = playlists[which]
                lifecycleScope.launch {
                    playlistStore.addSongToPlaylist(playlist.id, song)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "已添加到「${playlist.name}」", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleSearch() {
        if (_isSearchMode) exitSearch() else enterSearch()
    }

    fun enterSearch() {
        _isSearchMode = true
        searchOriginallyVisible = binding.btnSearch.visibility == View.VISIBLE
        binding.tvPageTitle.visibility = View.GONE
        binding.btnSearch.visibility = View.GONE
        binding.btnFolder.visibility = View.GONE

        if (searchEditText == null) {
            searchEditText = EditText(requireContext()).apply {
                hint = "搜索歌曲、歌手"
                val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(requireContext())
                setHintTextColor(if (isDark) 0xFF9F8D80.toInt() else 0xFF9A9490.toInt())
                setTextColor(if (isDark) 0xFFE6E1DD.toInt() else 0xFF1C1B1F.toInt())
                textSize = 15f
                background = null
                setSingleLine(true)
                setPadding(8, 0, 8, 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        filterSongs(s?.toString()?.trim() ?: "")
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
        }

        try {
            val parent = binding.tvPageTitle.parent as? LinearLayout ?: return
            val index = parent.indexOfChild(binding.tvPageTitle)
            val lp = binding.tvPageTitle.layoutParams
            if (searchEditText?.parent != null) {
                (searchEditText?.parent as? ViewGroup)?.removeView(searchEditText)
            }
            parent.addView(searchEditText, index, lp)
            searchEditText?.requestFocus()

            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchEditText, 0)
        } catch (_: Exception) {}
    }

    fun exitSearch() {
        _isSearchMode = false
        searchEditText?.text?.clear()
        try {
            searchEditText?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Exception) {}
        binding.tvPageTitle.visibility = View.VISIBLE
        binding.btnSearch.visibility = if (searchOriginallyVisible) View.VISIBLE else View.GONE
        binding.btnFolder.visibility = View.VISIBLE

        try {
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            searchEditText?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {}

        viewModel.loadSongs()
    }

    private fun filterSongs(query: String) {
        val allSongs = viewModel.songs.value ?: return
        if (query.isBlank()) {
            songAdapter.submitList(allSongs)
            return
        }
        val lowerQuery = query.lowercase()
        val filtered = allSongs.filter { song ->
            song.displayName.lowercase().contains(lowerQuery) ||
            song.artist.lowercase().contains(lowerQuery) ||
            song.album.lowercase().contains(lowerQuery)
        }
        songAdapter.submitList(filtered)
    }

    fun getRecyclerView(): androidx.recyclerview.widget.RecyclerView {
        return binding.recyclerView
    }

    private fun categorizeForIndex(c: Char): String {
        return when {
            c in 'A'..'Z' -> c.toString()
            c in 'a'..'z' -> c.uppercaseChar().toString()
            c in '0'..'9' -> "0-9"
            c in '\u3040'..'\u309F' -> categorizeHiragana(c)
            c in '\u30A0'..'\u30FF' -> categorizeKatakana(c)
            c in '\u4E00'..'\u9FFF' -> CjkSortUtils.getPinyinInitial(c)
            else -> "#"
        }
    }

    private fun categorizeHiragana(c: Char): String {
        val groups = listOf(
            "あ" to listOf('あ','い','う','え','お'),
            "か" to listOf('か','き','く','け','こ','が','ぎ','ぐ','げ','ご'),
            "さ" to listOf('さ','し','す','せ','そ','ざ','じ','ず','ぜ','ぞ'),
            "た" to listOf('た','ち','つ','て','と','だ','ぢ','づ','で','ど'),
            "な" to listOf('な','に','ぬ','ね','の'),
            "は" to listOf('は','ひ','ふ','へ','ほ','ば','び','ぶ','べ','ぼ','ぱ','ぴ','ぷ','ぺ','ぽ'),
            "ま" to listOf('ま','み','む','め','も'),
            "や" to listOf('や','ゆ','よ'),
            "ら" to listOf('ら','り','る','れ','ろ'),
            "わ" to listOf('わ','を','ん')
        )
        for ((label, chars) in groups) { if (c in chars) return label }
        return "あ"
    }

    private fun categorizeKatakana(c: Char): String {
        val groups = listOf(
            "ア" to listOf('ア','イ','ウ','エ','オ'),
            "カ" to listOf('カ','キ','ク','ケ','コ','ガ','ギ','グ','ゲ','ゴ'),
            "サ" to listOf('サ','シ','ス','セ','ソ','ザ','ジ','ズ','ゼ','ゾ'),
            "タ" to listOf('タ','チ','ツ','テ','ト','ダ','ヂ','ヅ','デ','ド'),
            "ナ" to listOf('ナ','ニ','ヌ','ネ','ノ'),
            "ハ" to listOf('ハ','ヒ','フ','ヘ','ホ','バ','ビ','ブ','ベ','ボ','パ','ピ','プ','ペ','ポ'),
            "マ" to listOf('マ','ミ','ム','メ','モ'),
            "ヤ" to listOf('ヤ','ユ','ヨ'),
            "ラ" to listOf('ラ','リ','ル','レ','ロ'),
            "ワ" to listOf('ワ','ヲ','ン')
        )
        for ((label, chars) in groups) { if (c in chars) return label }
        return "ア"
    }

}
