package com.privatemediavault.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database holding vault metadata only.
 *
 * The database stores [MediaItem] rows describing each stored media item; the
 * encrypted media and thumbnail bytes live on disk under `filesDir/vault/` and are
 * never persisted here (Requirement 5.1).
 */
@Database(
    entities = [MediaItem::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(MediaTypeConverter::class)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao

    companion object {
        const val DATABASE_NAME = "vault.db"
    }
}
