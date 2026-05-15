package com.rawsmusic.core.ui.search

import com.rawsmusic.core.common.model.AudioFile

object PinyinSearchHelper {

    data class SearchIndex(
        val text: String,
        val firstChar: String
    )

    fun buildIndex(text: String): SearchIndex {
        val firstChar = text.firstOrNull()?.uppercase() ?: "#"
        return SearchIndex(text, firstChar)
    }

    fun matchQuery(query: String, text: String): Boolean {
        if (text.contains(query, ignoreCase = true)) return true
        return false
    }

    fun searchSongs(query: String, songs: List<AudioFile>): List<AudioFile> {
        if (query.isBlank()) return songs
        val lowerQuery = query.lowercase()
        return songs.filter { song ->
            song.title.lowercase().contains(lowerQuery) ||
            song.artist.lowercase().contains(lowerQuery) ||
            song.album.lowercase().contains(lowerQuery)
        }
    }

    fun groupByFirstChar(songs: List<AudioFile>): Map<String, List<AudioFile>> {
        return songs.groupBy { song ->
            val firstChar = song.title.firstOrNull() ?: '#'
            if (firstChar.isLetter()) {
                firstChar.uppercaseChar().toString()
            } else {
                "#"
            }
        }.toSortedMap(compareBy { key ->
            if (key == "#") "zzz" else key
        })
    }
}
