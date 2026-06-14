package com.rawsmusic.ui.albums

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.core.common.base.BaseFragment
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.adapter.SongDataProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
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
    private lateinit var songDataProvider: SongDataProvider

    override fun initView() {
        songDataProvider = SongDataProvider()
        songDataProvider.onItemClicked = { song, _ ->
            playSongSafe(song)
        }
        songDataProvider.onItemLongClicked = { song, position ->
            if (songDataProvider.isSelectMode) {
                val isSelected = position in songDataProvider.selectedPositions
                if (isSelected) songDataProvider.selectedPositions = songDataProvider.selectedPositions - position
                else songDataProvider.selectedPositions = songDataProvider.selectedPositions + position
            } else {
                songDataProvider.isSelectMode = true
                songDataProvider.selectedPositions = setOf(position)
            }
        }
        songDataProvider.onSelectionChanged = { _ -> }

        // Compose 版本的歌曲列表
        binding.composeSongList.setContent {
            val songs by viewModel.songs.observeAsState(emptyList())
            ComposePowerListFull(
                songs = songs,
                onSongClick = { song, _ -> playSongSafe(song) },
                onSongLongClick = { song, position ->
                    if (songDataProvider.isSelectMode) {
                        val isSelected = position in songDataProvider.selectedPositions
                        if (isSelected) songDataProvider.selectedPositions = songDataProvider.selectedPositions - position
                        else songDataProvider.selectedPositions = songDataProvider.selectedPositions + position
                    } else {
                        songDataProvider.isSelectMode = true
                        songDataProvider.selectedPositions = setOf(position)
                    }
                }
            )
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
            BitmapProvider.load(
                key = coverUri.toString(),
                imageView = binding.ivAlbumCover,
                targetWidth = binding.ivAlbumCover.width.coerceAtLeast(512),
                targetHeight = binding.ivAlbumCover.height.coerceAtLeast(512)
            )
        } else {
            binding.ivAlbumCover.setImageDrawable(null)
        }

        viewModel.loadSongs(albumName, albumArtist)

        // Compose 版本：背景变化通过 ThemeManager 自动处理
    }

    override fun initObserver() {
        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            songDataProvider.submitList(songs)
            // Compose 版本：数据通过 observeAsState 自动更新
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
