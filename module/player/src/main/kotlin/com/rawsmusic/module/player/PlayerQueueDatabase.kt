package com.rawsmusic.module.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Small durable queue store modelled after Reference's queue table.
 *
 * The renderer still consumes [com.rawsmusic.core.common.model.PlayQueue], but queue ownership is
 * no longer tied to a MMKV JSON write.  Each row has its own id, sort position and creation time;
 * replacing the snapshot happens in one SQLite transaction so a process kill cannot leave the
 * queue and its cursor at different generations.
 */
internal class PlayerQueueDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    data class Snapshot(
        val entries: List<Entry>,
        val currentEntryId: Long?,
        val prioritySongsJson: List<String>,
        val shuffleTraversalOrder: List<Int> = emptyList(),
        val shuffleTraversalCursor: Int = -1,
    )

    data class Entry(
        val entryId: Long,
        val sort: Int,
        val songJson: String,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE queue_entries (
                entry_id INTEGER PRIMARY KEY,
                sort INTEGER NOT NULL,
                song_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                shuffle_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE queue_state (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                current_entry_id INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE priority_entries (
                priority_id INTEGER PRIMARY KEY AUTOINCREMENT,
                sort INTEGER NOT NULL,
                song_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE queue_traversal (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                traversal_order TEXT NOT NULL,
                traversal_cursor INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX queue_entries_sort_idx ON queue_entries(sort)")
        db.execSQL("CREATE INDEX priority_entries_sort_idx ON priority_entries(sort)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS queue_traversal (
                    singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                    traversal_order TEXT NOT NULL,
                    traversal_cursor INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    fun saveSnapshot(
        entries: List<Entry>,
        currentEntryId: Long?,
        prioritySongsJson: List<String>,
        shuffleTraversalOrder: List<Int> = emptyList(),
        shuffleTraversalCursor: Int = -1,
    ) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete(TABLE_QUEUE, null, null)
            val createdAt = System.currentTimeMillis()
            entries.forEach { entry ->
                db.insertOrThrow(
                    TABLE_QUEUE,
                    null,
                    ContentValues().apply {
                        put("entry_id", entry.entryId)
                        put("sort", entry.sort)
                        put("song_json", entry.songJson)
                        put("created_at", createdAt)
                    },
                )
            }

            db.delete(TABLE_STATE, null, null)
            db.insertOrThrow(
                TABLE_STATE,
                null,
                ContentValues().apply {
                    put("singleton", 1)
                    if (currentEntryId == null) putNull("current_entry_id")
                    else put("current_entry_id", currentEntryId)
                    put("updated_at", createdAt)
                },
            )

            db.delete(TABLE_PRIORITY, null, null)
            prioritySongsJson.forEachIndexed { index, songJson ->
                db.insertOrThrow(
                    TABLE_PRIORITY,
                    null,
                    ContentValues().apply {
                        put("sort", index)
                        put("song_json", songJson)
                    },
                )
            }
            db.delete(TABLE_TRAVERSAL, null, null)
            db.insertOrThrow(
                TABLE_TRAVERSAL,
                null,
                ContentValues().apply {
                    put("singleton", 1)
                    put("traversal_order", shuffleTraversalOrder.joinToString(","))
                    put("traversal_cursor", shuffleTraversalCursor)
                },
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadSnapshot(): Snapshot? {
        val db = readableDatabase
        val entries = buildList {
            db.query(
                TABLE_QUEUE,
                arrayOf("entry_id", "sort", "song_json"),
                null,
                null,
                null,
                null,
                "sort ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        Entry(
                            entryId = cursor.getLong(0),
                            sort = cursor.getInt(1),
                            songJson = cursor.getString(2),
                        ),
                    )
                }
            }
        }

        var stateExists = false
        var currentEntryId: Long? = null
        db.query(
            TABLE_STATE,
            arrayOf("current_entry_id"),
            "singleton = 1",
            null,
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                stateExists = true
                if (!cursor.isNull(0)) currentEntryId = cursor.getLong(0)
            }
        }

        val priority = buildList {
            db.query(
                TABLE_PRIORITY,
                arrayOf("song_json"),
                null,
                null,
                null,
                null,
                "sort ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        var shuffleTraversalOrder = emptyList<Int>()
        var shuffleTraversalCursor = -1
        db.query(
            TABLE_TRAVERSAL,
            arrayOf("traversal_order", "traversal_cursor"),
            "singleton = 1",
            null,
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                shuffleTraversalOrder = cursor.getString(0)
                    .split(',')
                    .mapNotNull(String::toIntOrNull)
                shuffleTraversalCursor = cursor.getInt(1)
            }
        }

        // An existing state row is meaningful even when the queue is intentionally empty.  This
        // prevents an explicit "clear queue" from being undone by the legacy MMKV snapshot on the
        // next cold start.  A database with no state row is the only case that means "not migrated".
        if (!stateExists && entries.isEmpty() && priority.isEmpty()) return null
        return Snapshot(
            entries = entries,
            currentEntryId = currentEntryId,
            prioritySongsJson = priority,
            shuffleTraversalOrder = shuffleTraversalOrder,
            shuffleTraversalCursor = shuffleTraversalCursor,
        )
    }

    private companion object {
        const val DATABASE_NAME = "player_queue.db"
        const val DATABASE_VERSION = 2
        const val TABLE_QUEUE = "queue_entries"
        const val TABLE_STATE = "queue_state"
        const val TABLE_PRIORITY = "priority_entries"
        const val TABLE_TRAVERSAL = "queue_traversal"
    }
}
