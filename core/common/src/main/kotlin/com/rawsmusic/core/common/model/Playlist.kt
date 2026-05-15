package com.rawsmusic.core.common.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Playlist(
    val id: Long = 0,
    val name: String = "",
    val coverPath: String = "",
    val songCount: Int = 0,
    val createDate: Long = System.currentTimeMillis(),
    val updateDate: Long = System.currentTimeMillis(),
    val isSystem: Boolean = false
) : Parcelable

@Parcelize
data class PlaylistSong(
    val id: Long = 0,
    val playlistId: Long = 0,
    val audioId: Long = 0,
    val sortOrder: Int = 0,
    val dateAdded: Long = System.currentTimeMillis()
) : Parcelable
