package com.rawsmusic.module.data

import android.app.Application
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.migration.MmkvToRoomMigration
import com.rawsmusic.module.data.repository.MusicRepository

object DataModule {

    fun init(app: Application) {
        // MMKV is initialized in CoreInit
        // Room database initialization
        MusicRepository.init(app)
        // MMKV → Room one-time migration
        val database = MusicDatabase.getInstance(app)
        MmkvToRoomMigration.migrateIfNeeded(database)
    }
}
