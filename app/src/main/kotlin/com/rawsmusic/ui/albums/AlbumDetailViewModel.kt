package com.rawsmusic.ui.albums

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.ui.songs.PlayerHolder
import kotlinx.coroutines.launch

class AlbumDetailViewModel : ViewModel() {

    private val _songs = MutableLiveData<List<AudioFile>>()
    val songs: LiveData<List<AudioFile>> = _songs

    fun loadSongs(albumName: String, albumArtist: String) {
        viewModelScope.launch {
            try {
                val allSongs = MusicRepository.getAllSongs()
                // 优先精确匹配(album+artist)，无结果则仅按album匹配
                var albumSongs = allSongs.filter {
                    it.album == albumName && it.artist == albumArtist
                }
                if (albumSongs.isEmpty()) {
                    albumSongs = allSongs.filter { it.album == albumName }
                }
                _songs.postValue(albumSongs)
            } catch (_: Exception) {
                _songs.postValue(emptyList())
            }
        }
    }

    fun playSong(song: AudioFile, @Suppress("UNUSED_PARAMETER") position: Int = 0) {
        if (song.path.isBlank()) return  // 忽略无效歌曲
        val controller = PlayerHolder.controller ?: run {
            android.util.Log.e("AlbumDetail", "playSong: controller is null")
            return
        }
        val songList = _songs.value ?: emptyList()
        if (songList.isEmpty()) {
            try { controller.play(song) } catch (_: Exception) {}
            return
        }
        val index = songList.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        try {
            controller.playQueue(songList, index)
        } catch (_: Exception) {
            try { controller.play(song) } catch (_: Exception) {}
        }
    }
}
