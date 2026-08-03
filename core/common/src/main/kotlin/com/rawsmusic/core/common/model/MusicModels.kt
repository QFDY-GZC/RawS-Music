package com.rawsmusic.core.common.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Artist(
    val id: Long = 0,
    val name: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val coverPath: String = ""
) : Parcelable

@Parcelize
data class Album(
    val id: Long = 0,
    val name: String = "",
    val artist: String = "",
    val songCount: Int = 0,
    val year: Int = 0,
    val coverPath: String = "",
    val hasHiRes: Boolean = false
) : Parcelable

@Parcelize
data class Genre(
    val id: Long = 0,
    val name: String = "",
    val songCount: Int = 0
) : Parcelable

@Parcelize
data class Folder(
    val path: String = "",
    val name: String = "",
    val songCount: Int = 0
) : Parcelable
