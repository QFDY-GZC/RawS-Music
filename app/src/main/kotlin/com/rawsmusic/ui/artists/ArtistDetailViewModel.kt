package com.rawsmusic.ui.artists

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.ui.songs.PlayerHolder
import kotlinx.coroutines.launch

class ArtistDetailViewModel : ViewModel() {

    private val _songs = MutableLiveData<List<AudioFile>>()
    val songs: LiveData<List<AudioFile>> = _songs

    private val _albums = MutableLiveData<List<Album>>()
    val albums: LiveData<List<Album>> = _albums

    fun loadArtistData(artistName: String) {
        viewModelScope.launch {
            try {
                val allSongs = MusicRepository.getAllSongs()
                val artistSongs = allSongs.filter { it.artist == artistName }
                _songs.postValue(artistSongs)

                val artistAlbums = MusicRepository.albums.value
                    .filter { it.artist == artistName }
                _albums.postValue(artistAlbums)
            } catch (_: Exception) {
                _songs.postValue(emptyList())
                _albums.postValue(emptyList())
            }
        }
    }

    fun playSong(song: AudioFile, @Suppress("UNUSED_PARAMETER") position: Int = 0) {
        if (song.path.isBlank()) return
        val controller = PlayerHolder.controller ?: return
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
