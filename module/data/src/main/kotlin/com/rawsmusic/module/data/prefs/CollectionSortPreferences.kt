package com.rawsmusic.module.data.prefs

import com.rawsmusic.core.common.model.SortOrder
import java.security.MessageDigest

/**
 * Persistent sort order for category detail pages.
 *
 * The key includes the owning detail scene and the collection stable key so albums,
 * artists, genres, years and folders do not accidentally share one another's order.
 * SortOrder.name is stored rather than ordinal so enum reordering remains compatible.
 */
object CollectionSortPreferences {
    private const val KEY_PREFIX = "sort_collection_detail_"

    fun read(ownerTag: String, stableKey: String, default: SortOrder): SortOrder {
        val stored = AppPreferences.storage.decodeString(storageKey(ownerTag, stableKey), null)
        return SortOrder.entries.firstOrNull { it.name == stored } ?: default
    }

    fun write(ownerTag: String, stableKey: String, value: SortOrder) {
        AppPreferences.storage.encode(storageKey(ownerTag, stableKey), value.name)
    }

    private fun storageKey(ownerTag: String, stableKey: String): String {
        val identity = "$ownerTag\u0000$stableKey"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val suffix = buildString(32) {
            for (index in 0 until 16) append("%02x".format(digest[index].toInt() and 0xff))
        }
        return KEY_PREFIX + suffix
    }
}
