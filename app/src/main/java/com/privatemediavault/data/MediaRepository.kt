package com.privatemediavault.data

import com.privatemediavault.domain.model.ExportResult
import com.privatemediavault.domain.model.ImportReport
import com.privatemediavault.domain.model.MediaType
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * Coordinates the Room metadata database ([MediaDao]) and the [EncryptedFileStore],
 * exposing the vault contents and the lifecycle operations (import, delete, export,
 * thumbnail decryption) to the view models.
 *
 * ### Why streams/byte arrays instead of `android.net.Uri` and `Bitmap`
 * The design sketches this interface in terms of `Uri` (import/export) and `Bitmap`
 * (thumbnails), but both are Android-only types that would pull the whole repository —
 * and its tests — onto an Android runtime. Following the same approach already used for
 * [EncryptedFileStore], the core contract is expressed over [ImportSource] descriptors,
 * [OutputStream]-producing lambdas, and raw thumbnail [ByteArray]s so the import
 * partitioning, delete/export, and session-gating logic stays verifiable off-device.
 * The thin [AndroidUriMediaRepository] adapter bridges `Uri`/`Bitmap` on-device.
 */
interface MediaRepository {

    /**
     * Observes all stored media items, newest first. Emits a fresh list whenever the
     * vault contents change so the UI stays in sync (Req 10.3). Backed directly by
     * [MediaDao.observeAll].
     */
    fun observeItems(): Flow<List<MediaItem>>

    /**
     * Imports each source in [sources] **independently** so that a single failure never
     * aborts the rest of the batch (Req 4.3). Each successful source is encrypted into
     * Vault Storage and recorded as metadata; the returned [ImportReport] partitions the
     * inputs into the ids that succeeded and the per-file failures, with the two sets
     * disjoint and together covering every input (Req 4.1, 4.3).
     *
     * When [removeOriginals] is `true`, the source's
     * [ImportSource.deleteOriginal] action is invoked after that source imports
     * successfully (Req 4.4).
     *
     * Requires an unlocked session because encryption needs the DEK.
     */
    suspend fun importItems(sources: List<ImportSource>, removeOriginals: Boolean): ImportReport

    /**
     * Permanently removes the item identified by [id] from both Vault Storage (the
     * encrypted media blob and its thumbnail) and the metadata database (Req 10.2, 10.3).
     *
     * @return `true` if a metadata row existed and was removed, `false` otherwise.
     */
    suspend fun deleteItem(id: String): Boolean

    /**
     * Writes a decrypted copy of the item identified by [id] to the stream produced by
     * [destination]. Session-gated: when the session is locked the export is refused and
     * [ExportResult.SessionLocked] is returned rather than producing plaintext
     * (Req 11.1, 11.2).
     */
    suspend fun exportItem(id: String, destination: () -> OutputStream): ExportResult

    /**
     * Returns the decrypted thumbnail bytes for the item identified by [id]. Session-gated
     * (Req 5.3, 5.4): refuses with [IllegalStateException] when the session is locked.
     *
     * @throws IllegalStateException when the session is locked.
     * @throws java.io.FileNotFoundException when no thumbnail blob exists for [id].
     */
    suspend fun decryptedThumbnail(id: String): ByteArray

    /**
     * Returns the full decrypted media bytes for the item identified by [id] — the clear
     * image or video content shown when the User unblurs an item in the viewer (Req 7.1,
     * 7.3). Session-gated exactly like [decryptedThumbnail]: the bytes are decrypted only
     * within an Authenticated Session and the call refuses with [IllegalStateException]
     * when the session is locked (Req 5.3, 5.4, 7.2), so clear content never materializes
     * without a session.
     *
     * @throws IllegalStateException when the session is locked.
     * @throws java.io.FileNotFoundException when no media blob exists for [id].
     */
    suspend fun decryptedMedia(id: String): ByteArray
}

/**
 * A single item to import, described in a device-independent way.
 *
 * Mirrors the stream-based approach of [EncryptedFileStore]: rather than an
 * `android.net.Uri`, an import source carries the metadata needed to build a
 * [MediaItem] plus lazy providers for the media bytes ([openStream]) and the thumbnail
 * bytes ([openThumbnail]). [deleteOriginal] is the optional action used when the User
 * has enabled removal of originals (Req 4.4).
 *
 * @param sourceName     human-readable name; used as the failure key and the display name.
 * @param mediaType      whether the source is an image or a video.
 * @param sizeBytes      size of the original media in bytes.
 * @param durationMs     duration for videos; `null` for images.
 * @param openStream     opens a fresh stream over the original media bytes; invoked once.
 * @param openThumbnail  opens a fresh stream over the thumbnail bytes; invoked once.
 * @param deleteOriginal removes the original source from its device location; returns
 *                       `true` on success. `null` when the source cannot be deleted.
 */
class ImportSource(
    val sourceName: String,
    val mediaType: MediaType,
    val sizeBytes: Long,
    val durationMs: Long?,
    val openStream: () -> InputStream,
    val openThumbnail: () -> InputStream,
    val deleteOriginal: (() -> Boolean)? = null,
)
