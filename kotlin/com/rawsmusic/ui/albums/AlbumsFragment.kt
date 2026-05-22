package com.rawsmusic.ui.albums

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.rawsmusic.core.common.base.BaseFragment
import com.rawsmusic.core.common.ext.fadeIn
import com.rawsmusic.core.common.ext.fadeOut
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.ui.adapter.AlbumAdapter
import com.rawsmusic.core.ui.decoration.GridSpacingItemDecoration
import com.rawsmusic.databinding.FragmentAlbumsBinding

class AlbumsFragment : BaseFragment<FragmentAlbumsBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentAlbumsBinding
        get() = { inflater, container, attachToParent ->
            FragmentAlbumsBinding.inflate(inflater, container, attachToParent)
        }

    private val viewModel: AlbumsViewModel by viewModels()
    private lateinit var albumAdapter: AlbumAdapter
    private var _isSearchMode = false
    val isSearchMode: Boolean get() = _isSearchMode
    private var searchEditText: EditText? = null

    override fun initView() {
        albumAdapter = AlbumAdapter { album -> navigateToAlbumDetail(album) }

        binding.recyclerView.apply {
            adapter = albumAdapter
            layoutManager = GridLayoutManager(requireContext(), 2)
            addItemDecoration(GridSpacingItemDecoration(requireContext(), 2, 8f))
            setHasFixedSize(true)
        }

        binding.btnSearch.setOnClickListener { toggleSearch() }
    }

    override fun initData() {
        viewModel.loadAlbums()
    }

    override fun initObserver() {
        viewModel.albums.observe(viewLifecycleOwner) { albums ->
            albumAdapter.submitList(albums)
            if (albums.isEmpty()) {
                binding.emptyView.fadeIn()
                binding.recyclerView.fadeOut()
            } else {
                binding.emptyView.fadeOut()
                binding.recyclerView.fadeIn()
            }
        }

        // 数据源变化时自动重新加载（扫描完成后刷新列表）
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            com.rawsmusic.module.data.repository.MusicRepository.albums.collect {
                viewModel.loadAlbums()
            }
        }
    }

    private fun toggleSearch() {
        if (_isSearchMode) exitSearch() else enterSearch()
    }

    fun enterSearch() {
        _isSearchMode = true
        binding.tvPageTitle.visibility = View.GONE
        binding.btnSearch.visibility = View.GONE

        if (searchEditText == null) {
            searchEditText = EditText(requireContext()).apply {
                hint = "搜索专辑、艺术家"
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
                        filterAlbums(s?.toString()?.trim() ?: "")
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
        binding.btnSearch.visibility = View.VISIBLE

        try {
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            searchEditText?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {}

        viewModel.loadAlbums()
    }

    private fun filterAlbums(query: String) {
        val allAlbums = viewModel.albums.value ?: return
        if (query.isBlank()) {
            albumAdapter.submitList(allAlbums)
            return
        }
        val lowerQuery = query.lowercase()
        val filtered = allAlbums.filter { album ->
            album.name.lowercase().contains(lowerQuery) ||
            album.artist.lowercase().contains(lowerQuery)
        }
        albumAdapter.submitList(filtered)
    }

    private fun navigateToAlbumDetail(album: Album) {
        val bundle = Bundle().apply {
            putString(AlbumDetailFragment.ARG_ALBUM_NAME, album.name)
            putString(AlbumDetailFragment.ARG_ALBUM_ARTIST, album.artist)
            putString(AlbumDetailFragment.ARG_COVER_PATH, album.coverPath)
        }
        try {
            val navController = androidx.navigation.fragment.NavHostFragment.findNavController(this)
            navController.navigate(com.rawsmusic.R.id.action_global_album_detail, bundle)
        } catch (_: Exception) {}
    }
}
