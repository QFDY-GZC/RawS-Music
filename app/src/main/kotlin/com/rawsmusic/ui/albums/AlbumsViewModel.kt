package com.rawsmusic.ui.albums

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.launch

class AlbumsViewModel : ViewModel() {

    private val _albums = MutableLiveData<List<Album>>()
    val albums: LiveData<List<Album>> = _albums

    private var allAlbums: List<Album> = emptyList()

    fun loadAlbums() {
        viewModelScope.launch {
            MusicRepository.albums.collect { albumList ->
                allAlbums = albumList
                _albums.postValue(albumList)
            }
        }
    }

    fun getAllAlbums(): List<Album> = allAlbums
}
