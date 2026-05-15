package com.rawsmusic.ui.artists

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.launch

class ArtistsViewModel : ViewModel() {

    private val _artists = MutableLiveData<List<Artist>>()
    val artists: LiveData<List<Artist>> = _artists

    fun loadArtists() {
        viewModelScope.launch {
            MusicRepository.artists.collect { artistList ->
                _artists.postValue(artistList)
            }
        }
    }
}
