package com.rawsmusic.module.data.source.runtime

import com.rawsmusic.core.common.source.RawSourceMediaItem

internal object MusicSourceRuntimeWire {
    const val ACTION_SEARCH = 1
    const val ACTION_RESOLVE_AUDIO = 2
    const val ACTION_GET_LYRIC = 3
    const val ACTION_LX_RESOLVE_AUDIO = 4
    const val ACTION_LX_GET_LYRIC = 5
    const val ACTION_RESPONSE = 100

    const val KEY_REQUEST_ID = "request_id"
    const val KEY_SCRIPT_PATH = "script_path"
    const val KEY_SCRIPT_SHA256 = "script_sha256"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_SOURCE_NAME = "source_name"
    const val KEY_QUERY = "query"
    const val KEY_PAGE = "page"
    const val KEY_MEDIA_TYPE = "media_type"
    const val KEY_ITEM_PAYLOAD = "item_payload"
    const val KEY_QUALITY = "quality"
    const val KEY_SUCCESS = "success"
    const val KEY_PAYLOAD = "payload"
    const val KEY_ERROR = "error"

    const val MAX_RUNTIME_RESPONSE_BYTES = 512 * 1024
    const val MAX_RUNTIME_ITEM_BYTES = 384 * 1024
}

data class MusicSourceSearchGroup(
    val sourceId: String,
    val sourceName: String,
    val items: List<RawSourceMediaItem>,
    val isEnd: Boolean = true,
    val error: String = "",
)

data class AggregatedMusicSourceSearch(
    val groups: List<MusicSourceSearchGroup> = emptyList(),
) {
    val items: List<RawSourceMediaItem>
        get() = groups.flatMap(MusicSourceSearchGroup::items)

    val errors: List<MusicSourceSearchGroup>
        get() = groups.filter { it.error.isNotBlank() }
}
