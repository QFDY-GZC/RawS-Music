package com.rawsmusic.ui.songs

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.launch

class SongsViewModel : ViewModel() {

    private val _songs = MutableLiveData<List<AudioFile>>()
    val songs: LiveData<List<AudioFile>> = _songs

    private var sortOrder: SortOrder = AppPreferences.Sort.songSortOrder

    fun loadSongs() {
        viewModelScope.launch {
            val result = MusicRepository.getAllSongs(sortOrder)
            _songs.postValue(result)
        }
    }

    fun playSong(song: AudioFile, position: Int) {
        val currentSongs = _songs.value ?: emptyList()
        // 在完整歌曲列表中查找歌曲的正确位置，而不是依赖adapter position
        val realIndex = currentSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        if (currentSongs.isNotEmpty()) {
            PlayerHolder.controller?.playQueue(currentSongs, realIndex)
        } else {
            PlayerHolder.controller?.play(song)
        }
    }

    fun setSortOrder(order: SortOrder) {
        sortOrder = order
        AppPreferences.Sort.songSortOrder = order
        loadSongs()
    }
}
