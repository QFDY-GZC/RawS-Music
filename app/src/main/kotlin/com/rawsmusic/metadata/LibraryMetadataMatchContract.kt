package com.rawsmusic.metadata

import android.content.Context
import android.content.Intent
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.model.AudioFile
import java.io.File
import java.util.UUID

enum class LibraryMetadataMatchMode {
    LYRICS_ONLY,
    FILL_MISSING,
    MATCH_CURRENT,
    REMATCH_ALL,
}

data class LibraryMetadataMatchRequest(
    val id: String,
    val mode: LibraryMetadataMatchMode,
    val songs: List<AudioFile>,
)

object LibraryMetadataMatchContract {
    const val EXTRA_JOB_ID = "library_metadata_job_id"

    fun createIntent(
        context: Context,
        songs: List<AudioFile>,
        mode: LibraryMetadataMatchMode,
    ): Intent {
        val jobId = UUID.randomUUID().toString()
        writeRequest(context, LibraryMetadataMatchRequest(jobId, mode, songs))
        return Intent(context, LibraryMetadataMatchService::class.java)
            .putExtra(EXTRA_JOB_ID, jobId)
    }

    fun readRequest(context: Context, jobId: String): LibraryMetadataMatchRequest? {
        val file = requestFile(context, jobId)
        if (!file.isFile) return null
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val mode = runCatching {
                LibraryMetadataMatchMode.valueOf(root["mode"]?.asString.orEmpty())
            }.getOrDefault(LibraryMetadataMatchMode.FILL_MISSING)
            val songs = root.getAsJsonArray("songs").orEmpty().mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                AudioFile(
                    id = item.long("id"),
                    path = item.string("path"),
                    title = item.string("title"),
                    artist = item.string("artist"),
                    album = item.string("album"),
                    albumId = item.long("albumId", -1L),
                    duration = item.long("duration"),
                    fileSize = item.long("fileSize"),
                    dateModified = item.long("dateModified"),
                    albumArtPath = item.string("albumArtPath"),
                    cueOffsetMs = item.long("cueOffsetMs"),
                    cueEndMs = item.long("cueEndMs"),
                    cueTrackIndex = item.int("cueTrackIndex"),
                )
            }
            LibraryMetadataMatchRequest(jobId, mode, songs)
        }.getOrNull()
    }

    fun deleteRequest(context: Context, jobId: String) {
        runCatching { requestFile(context, jobId).delete() }
    }

    private fun writeRequest(context: Context, request: LibraryMetadataMatchRequest) {
        val root = JsonObject().apply {
            addProperty("id", request.id)
            addProperty("mode", request.mode.name)
            add("songs", JsonArray().apply {
                request.songs.distinctBy { Triple(it.path, it.cueOffsetMs, it.cueTrackIndex) }
                    .forEach { song ->
                        add(JsonObject().apply {
                            addProperty("id", song.id)
                            addProperty("path", song.path)
                            addProperty("title", song.title)
                            addProperty("artist", song.artist)
                            addProperty("album", song.album)
                            addProperty("albumId", song.albumId)
                            addProperty("duration", song.duration)
                            addProperty("fileSize", song.fileSize)
                            addProperty("dateModified", song.dateModified)
                            addProperty("albumArtPath", song.albumArtPath)
                            addProperty("cueOffsetMs", song.cueOffsetMs)
                            addProperty("cueEndMs", song.cueEndMs)
                            addProperty("cueTrackIndex", song.cueTrackIndex)
                        })
                    }
            })
        }
        val target = requestFile(context, request.id)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(root.toString())
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Unable to persist metadata matching job" }
    }

    private fun requestFile(context: Context, jobId: String): File = File(
        File(context.filesDir, "metadata_match_jobs").apply { mkdirs() },
        "$jobId.json"
    )

    private fun JsonObject.string(key: String): String = get(key)?.let {
        runCatching { it.asString }.getOrDefault("")
    }.orEmpty()

    private fun JsonObject.long(key: String, fallback: Long = 0L): Long = get(key)?.let {
        runCatching { it.asLong }.getOrDefault(fallback)
    } ?: fallback

    private fun JsonObject.int(key: String, fallback: Int = 0): Int = get(key)?.let {
        runCatching { it.asInt }.getOrDefault(fallback)
    } ?: fallback

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
}
