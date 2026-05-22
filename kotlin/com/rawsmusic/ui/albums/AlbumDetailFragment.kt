package com.rawsmusic.ui.albums

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.rawsmusic.core.common.base.BaseFragment
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.adapter.SongAdapter
import com.rawsmusic.databinding.FragmentAlbumDetailBinding
import com.rawsmusic.ui.songs.PlayerHolder
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.module.player.PlayerController

class AlbumDetailFragment : BaseFragment<FragmentAlbumDetailBinding>() {

    override val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> FragmentAlbumDetailBinding
        get() = { inflater, container, attachToParent ->
            FragmentAlbumDetailBinding.inflate(inflater, container, attachToParent)
        }

    private val viewModel: AlbumDetailViewModel by viewModels()
    private lateinit var songAdapter: SongAdapter

    override fun initView() {
        songAdapter = SongAdapter(
            onSongClick = { song, _ ->
                playSongSafe(song)
            },
            onLongClick = { song, position ->
                // 长按切换选中状态（不再使用操作卡片）
                if (songAdapter.isEditMode) {
                    val isSelected = song.id in songAdapter.selectedIds
                    if (isSelected) songAdapter.deselectSong(song.id) else songAdapter.selectSong(song.id)
                } else {
                    songAdapter.enterEditMode(song.id)
                }
                true
            },
            onSelectionChanged = { _ -> }
        )

        songAdapter.fontApplier = { FontManager.applyToTextView(it) }

        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }

        binding.btnBack.setOnClickListener {
            NavHostFragment.findNavController(this).navigateUp()
        }
    }

    override fun initData() {
        val albumName = arguments?.getString(ARG_ALBUM_NAME) ?: return
        val albumArtist = arguments?.getString(ARG_ALBUM_ARTIST) ?: return
        val coverPath = arguments?.getString(ARG_COVER_PATH) ?: ""

        binding.tvAlbumTitle.text = albumName
        binding.tvAlbumName.text = albumName
        binding.tvAlbumArtist.text = albumArtist

        val coverUri = coverPath.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (coverUri != null) {
            binding.ivAlbumCover.load(coverUri) {
                crossfade(true)
            }
        } else {
            binding.ivAlbumCover.setImageDrawable(null)
        }

        viewModel.loadSongs(albumName, albumArtist)

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
            val hasHiRes = songs.any { it.isHiRes }
            binding.ivHiresCoverBadge.visibility = if (hasHiRes) View.VISIBLE else View.GONE
        }
    }

    private fun playSongSafe(song: AudioFile) {
        if (song.path.isBlank()) return
        // 如果 controller 为空，尝试从 MainActivity 获取或创建
        if (PlayerHolder.controller == null) {
            val activity = activity as? com.rawsmusic.MainActivity
            if (activity != null) {
                activity.playerController ?: PlayerController.getInstance(requireContext()).also {
                    activity.playerController = it
                    PlayerHolder.controller = it
                }
            }
        }
        try {
            viewModel.playSong(song)
        } catch (_: Exception) {}
    }

    private fun deleteSongFile(song: AudioFile) {
        val deleted = com.rawsmusic.module.data.repository.MusicRepository.deleteSongFromDevice(requireContext(), song)
        viewModel.loadSongs(
            arguments?.getString(ARG_ALBUM_NAME) ?: return,
            arguments?.getString(ARG_ALBUM_ARTIST) ?: return
        )
        android.widget.Toast.makeText(requireContext(), if (deleted) "已删除" else "删除失败", android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ARG_ALBUM_NAME = "arg_album_name"
        const val ARG_ALBUM_ARTIST = "arg_album_artist"
        const val ARG_COVER_PATH = "arg_cover_path"

        fun newInstance(albumName: String, albumArtist: String, coverPath: String): AlbumDetailFragment {
            return AlbumDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ALBUM_NAME, albumName)
                    putString(ARG_ALBUM_ARTIST, albumArtist)
                    putString(ARG_COVER_PATH, coverPath)
                }
            }
        }
    }
}
