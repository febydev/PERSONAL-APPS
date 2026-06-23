package com.privatemediavault.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [MediaItem] metadata.
 *
 * Exposes the three operations the data layer needs for this slice: insert, delete,
 * and observe. Observation returns a [Flow] so the UI updates automatically when the
 * vault contents change (Requirements 10.3). Metadata only — no media bytes pass
 * through this DAO (Requirement 5.1).
 */
@Dao
interface MediaDao {

    /**
     * Inserts a media item, replacing any existing row with the same id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MediaItem)

    /**
     * Deletes the media item with the given id. Returns the number of rows removed
     * (0 if no such item existed).
     */
    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /**
     * Observes all media items, newest import first. Emits a new list whenever the
     * table changes.
     */
    @Query("SELECT * FROM media_items ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<MediaItem>>
}
