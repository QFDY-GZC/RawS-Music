package com.rawsmusic.core.ui.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CapsuleMode {
    FULL,
    NAV_ONLY,
    HIDDEN
}

enum class CapsuleNavId {
    LIBRARY,
    EQ,
    SEARCH,
    SETTINGS
}

object BottomCapsuleStateManager {

    private val _mode = MutableStateFlow(CapsuleMode.FULL)
    val mode: StateFlow<CapsuleMode> = _mode.asStateFlow()

    private val _selectedNavId = MutableStateFlow(CapsuleNavId.LIBRARY)
    val selectedNavId: StateFlow<CapsuleNavId> = _selectedNavId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _songTitle = MutableStateFlow("暂无音乐播放")
    val songTitle: StateFlow<String> = _songTitle.asStateFlow()

    private val _songArtist = MutableStateFlow("")
    val songArtist: StateFlow<String> = _songArtist.asStateFlow()

    private val _coverPath = MutableStateFlow("")
    val coverPath: StateFlow<String> = _coverPath.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isSeeking = MutableStateFlow(false)
    val isSeeking: StateFlow<Boolean> = _isSeeking.asStateFlow()

    private val _lyricText = MutableStateFlow("")
    val lyricText: StateFlow<String> = _lyricText.asStateFlow()

    private val _lyricTranslation = MutableStateFlow("")
    val lyricTranslation: StateFlow<String> = _lyricTranslation.asStateFlow()

    private val _isDualLine = MutableStateFlow(false)
    val isDualLine: StateFlow<Boolean> = _isDualLine.asStateFlow()

    var onBarClick: (() -> Unit)? = null
    var onPlayPauseClick: (() -> Unit)? = null
    var onNextClick: (() -> Unit)? = null
    var onPreviousClick: (() -> Unit)? = null
    var onNavClick: ((CapsuleNavId) -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    fun setMode(mode: CapsuleMode) { _mode.value = mode }

    fun setSelectedNavId(id: CapsuleNavId) { _selectedNavId.value = id }

    fun updatePlaybackState(
        isPlaying: Boolean,
        title: String = _songTitle.value,
        artist: String = _songArtist.value,
        coverPath: String = _coverPath.value
    ) {
        _isPlaying.value = isPlaying
        _songTitle.value = title
        _songArtist.value = artist
        _coverPath.value = coverPath
    }

    fun updateProgress(position: Long, duration: Long) {
        if (!_isSeeking.value) {
            _currentPosition.value = position
        }
        _duration.value = duration
    }

    fun setSeeking(seeking: Boolean) { _isSeeking.value = seeking }

    fun setSeekPosition(position: Long) { _currentPosition.value = position }

    fun setTitle(v: String) { _songTitle.value = v }
    fun setArtist(v: String) { _songArtist.value = v }
    fun setCoverPath(v: String) { _coverPath.value = v }
    fun setIsPlaying(v: Boolean) { _isPlaying.value = v }

    fun setSongInfo(title: String, artist: String) {
        _songTitle.value = title
        _songArtist.value = artist
    }

    fun setPlaying(playing: Boolean) { _isPlaying.value = playing }

    fun setCoverImage(path: String?) { _coverPath.value = path ?: "" }

    fun updateRemainingTime(remainingMs: Long) {}

    fun updateLyric(original: String?, translation: String?, isDualLine: Boolean, isChinese: Boolean) {
        _lyricText.value = original ?: ""
        _lyricTranslation.value = translation ?: ""
        _isDualLine.value = isDualLine
    }
}
