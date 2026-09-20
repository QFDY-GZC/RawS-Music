package com.rawsmusic.core.common.ui

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class AppNoticeIcon {
    USB,
    INFO,
    WARNING,
    ERROR,
    AUDIO,
    AUDIO_EFFECTS,
    EQUALIZER,
    SCAN,
    PLAYLIST,
    FOLDER,
    DOWNLOAD,
    SHARE,
    METADATA,
    LYRICS,
    AI,
    QUEUE,
    PLAY_NEXT,
    ARTWORK,
    TRANSCODE,
}

data class AppNoticeEvent(
    val id: Long,
    val message: String,
    val icon: AppNoticeIcon,
)

object AppNoticeBus {
    private val nextId = AtomicLong(0L)
    private val _events = MutableSharedFlow<AppNoticeEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    fun post(
        message: String,
        icon: AppNoticeIcon = AppNoticeIcon.INFO,
    ) {
        if (message.isBlank()) return
        _events.tryEmit(
            AppNoticeEvent(
                id = nextId.incrementAndGet(),
                message = message,
                icon = icon,
            )
        )
    }

    fun error(message: String) {
        post(message = message, icon = AppNoticeIcon.ERROR)
    }
}
