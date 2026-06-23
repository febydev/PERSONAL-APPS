package com.privatemediavault.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.privatemediavault.domain.model.ExportResult
import com.privatemediavault.domain.model.ImportReport
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * Thin Android adapter that bridges `android.net.Uri` and `Bitmap` (as the design's
 * `MediaRepository` signature describes) to the stream/byte-array-based
 * [MediaRepository] core.
 *
 * Import takes a list of content `Uri`s and turns each into an [ImportSource] via the
 * injected [sourceFactory], which knows how to resolve display name, size, media type,
 * a thumbnail, and (when removal of originals is enabled) how to delete the source. All
 * partitioning, encryption, and session-gating is delegated to [delegate]; export bridges
 * the destination `Uri` to an [java.io.OutputStream] through [contentResolver], and
 * thumbnail decryption decodes the delegate's raw bytes into a [Bitmap].
 *
 * Keeping this glue separate lets the partitioning and session logic in
 * [DefaultMediaRepository] stay free of Android dependencies and unit-testable off-device.
 *
 * @param contentResolver opens streams for export destination URIs.
 * @param delegate        the core repository that performs the real work.
 * @param sourceFactory   resolves a content `Uri` into a device-independent [ImportSource].
 */
class AndroidUriMediaRepository(
    private val contentResolver: ContentResolver,
    private val delegate: MediaRepository,
    private val sourceFactory: (Uri) -> ImportSource,
) {

    /** Observes the vault contents. See [MediaRepository.observeItems]. */
    fun observeItems(): Flow<List<MediaItem>> = delegate.observeItems()

    /**
     * Imports each `Uri` in [uris], reporting per-file success/failure (Req 4.1, 4.3) and
     * optionally removing originals (Req 4.4). Mirrors [MediaRepository.importItems].
     */
    suspend fun importItems(uris: List<Uri>, removeOriginals: Boolean): ImportReport =
        delegate.importItems(uris.map(sourceFactory), removeOriginals)

    /** Permanently removes the item and its blobs (Req 10.2, 10.3). */
    suspend fun deleteItem(id: String): Boolean = delegate.deleteItem(id)

    /**
     * Exports a decrypted copy of [id] to the User-selected [dest]. Session-gated;
     * mirrors [MediaRepository.exportItem] (Req 11.1, 11.2).
     */
    suspend fun exportItem(id: String, dest: Uri): ExportResult =
        delegate.exportItem(id) {
            contentResolver.openOutputStream(dest)
                ?: throw IOException("Could not open destination for export: $dest")
        }

    /**
     * Returns the decrypted thumbnail for [id] as a [Bitmap], decoding the delegate's
     * decrypted bytes. Session-gated through the delegate (Req 5.3, 5.4).
     *
     * @throws IllegalStateException when the session is locked.
     * @throws IOException when the decrypted bytes cannot be decoded as an image.
     */
    suspend fun decryptedThumbnail(id: String): Bitmap {
        val bytes = delegate.decryptedThumbnail(id)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Could not decode thumbnail for item $id")
    }

    /**
     * Returns the full decrypted media bytes for [id] — the clear image or video shown
     * when the User unblurs an item in the viewer. Session-gated through the delegate
     * (Req 5.3, 5.4, 7.1, 7.2, 7.3). Unlike [decryptedThumbnail], the raw bytes are
     * returned undecoded so the viewer can decode an image or feed a video to ExoPlayer.
     *
     * @throws IllegalStateException when the session is locked.
     */
    suspend fun decryptedMedia(id: String): ByteArray = delegate.decryptedMedia(id)
}
