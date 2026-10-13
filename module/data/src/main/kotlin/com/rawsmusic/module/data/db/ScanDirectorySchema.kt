package com.rawsmusic.module.data.db

internal object ScanDirectorySchema {
    const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS scan_directories (source_key TEXT NOT NULL PRIMARY KEY, modified_at INTEGER NOT NULL, checked_at INTEGER NOT NULL)"
}
