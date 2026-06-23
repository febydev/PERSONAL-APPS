package com.privatemediavault.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.privatemediavault.domain.model.MediaType

/**
 * Room entity holding the metadata for a single stored media item.
 *
 * This table stores metadata ONLY. The encrypted media bytes and the encrypted
 * thumbnail bytes live on disk under `filesDir/vault/` and are referenced here by
 * file name ([encryptedFileName], [encryptedThumbName]). No plaintext or media
 * bytes are ever persisted in the database (Requirement 5.1).
 *
 * Render state (blurred vs. clear) is runtime UI state and is intentionally absent
 * here, so every load defaults to blurred (Requirement 6.1).
 */
@Entity(tableName = "media_items")
data class MediaItem(
    /** Stable unique identifier (UUID). */
    @PrimaryKey val id: String,
    /** Human-readable name shown in the vault grid. */
    val displayName: String,
    /** Whether this item is an image or a video. */
    val mediaType: MediaType,
    /** Name of the encrypted blob under `filesDir/vault/`. */
    val encryptedFileName: String,
    /** Size of the original media in bytes. */
    val sizeBytes: Long,
    /** Duration in milliseconds for videos; null for images. */
    val durationMs: Long?,
    /** Import timestamp as epoch milliseconds. */
    val importedAt: Long,
    /** Name of the small encrypted thumbnail blob under `filesDir/vault/`. */
    val encryptedThumbName: String
)
