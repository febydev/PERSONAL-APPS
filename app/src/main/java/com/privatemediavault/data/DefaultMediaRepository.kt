package com.privatemediavault.data

import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.ExportResult
import com.privatemediavault.domain.model.FailedImport
import com.privatemediavault.domain.model.ImportReport
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Default [MediaRepository] that ties together the metadata [MediaDao] and the
 * encrypted-blob [EncryptedFileStore].
 *
 * Each imported item produces two encrypted blobs: the media blob (keyed by the item id)
 * and a thumbnail blob (keyed by [thumbId]). The repository imports every source in a
 * batch independently — one failure is recorded and the batch continues — so the
 * [ImportReport] cleanly partitions inputs into successes and failures without loss
 * (Req 4.1, 4.3). Delete removes both blobs and the metadata row (Req 10.2, 10.3), and
 * export/thumbnail decryption are session-gated through [EncryptedFileStore]/[SessionManager]
 * (Req 11.1, 11.2, 5.3, 5.4).
 *
 * The id and clock generators are injected so import behavior is deterministic and
 * verifiable off-device.
 *
 * @param dao             metadata persistence (insert / deleteById / observeAll).
 * @param fileStore       encrypted blob storage; performs encryption and session-gating.
 * @param sessionManager  source of truth for "is a session active" (thumbnail gating).
 * @param idGenerator     supplies a unique item id per import; defaults to a random UUID.
 * @param clock           supplies the import timestamp; defaults to wall-clock millis.
 */
class DefaultMediaRepository(
    private val dao: MediaDao,
    private val fileStore: EncryptedFileStore,
    private val sessionManager: SessionManager,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MediaRepository {

    override fun observeItems(): Flow<List<MediaItem>> = dao.observeAll()

    override suspend fun importItems(
        sources: List<ImportSource>,
        removeOriginals: Boolean,
    ): ImportReport {
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<FailedImport>()

        for (source in sources) {
            val id = idGenerator()
            try {
                importOne(id, source)
            } catch (t: Throwable) {
                // One file failing must not abort the batch: record it and move on so
                // every other source still gets a chance to import (Req 4.3).
                cleanUpPartialBlobs(id)
                failed += FailedImport(source.sourceName, t.message ?: t.javaClass.simpleName)
                continue
            }

            succeeded += id

            // The vault copy is now safe; removing the original is a best-effort
            // follow-on action and never rolls back a successful import (Req 4.4).
            if (removeOriginals) {
                runCatching { source.deleteOriginal?.invoke() }
            }
        }

        return ImportReport(succeeded = succeeded, failed = failed)
    }

    /**
     * Encrypts the media and thumbnail blobs for [source] under [id] and writes the
     * metadata row. Any exception propagates so the caller can record the failure and
     * clean up partial state.
     */
    private suspend fun importOne(id: String, source: ImportSource) {
        val encryptedFileName = fileStore.importFrom(source.openStream, id)
        val encryptedThumbName = fileStore.importFrom(source.openThumbnail, thumbId(id))

        dao.insert(
            MediaItem(
                id = id,
                displayName = source.sourceName,
                mediaType = source.mediaType,
                encryptedFileName = encryptedFileName,
                sizeBytes = source.sizeBytes,
                durationMs = source.durationMs,
                importedAt = clock(),
                encryptedThumbName = encryptedThumbName,
            )
        )
    }

    override suspend fun deleteItem(id: String): Boolean {
        // Remove both encrypted blobs from storage, then the metadata row. Blob deletion
        // needs no session because it touches no key material (Req 10.2). The metadata
        // removal is what actually drops the item from the observed vault contents
        // (Req 10.3), so its row count is the authoritative "did anything exist" signal.
        fileStore.delete(id)
        fileStore.delete(thumbId(id))
        return dao.deleteById(id) > 0
    }

    override suspend fun exportItem(id: String, destination: () -> OutputStream): ExportResult =
        try {
            fileStore.exportTo(id, destination)
            ExportResult.Success
        } catch (e: IllegalStateException) {
            // The store refuses with IllegalStateException when the session is locked;
            // surface that as a denial so the UI can route to PIN entry (Req 11.2).
            ExportResult.SessionLocked
        } catch (t: Throwable) {
            ExportResult.Failed(t.message ?: t.javaClass.simpleName)
        }

    override suspend fun decryptedThumbnail(id: String): ByteArray {
        // Gate before touching the store so a locked session yields a clear, uniform
        // refusal even before any blob lookup (Req 5.3, 5.4).
        check(sessionManager.isUnlocked()) {
            "Vault session is locked; thumbnail decryption is refused"
        }
        val buffer = ByteArrayOutputStream()
        fileStore.openDecrypted(thumbId(id)).use { decrypted ->
            decrypted.copyTo(buffer)
        }
        return buffer.toByteArray()
    }

    override suspend fun decryptedMedia(id: String): ByteArray {
        // Same session gate as the thumbnail path: refuse before any blob lookup so a
        // locked session yields a uniform denial and no clear bytes are ever produced
        // (Req 5.3, 5.4, 7.2).
        check(sessionManager.isUnlocked()) {
            "Vault session is locked; media decryption is refused"
        }
        val buffer = ByteArrayOutputStream()
        fileStore.openDecrypted(id).use { decrypted ->
            decrypted.copyTo(buffer)
        }
        return buffer.toByteArray()
    }

    /**
     * Best-effort removal of any blobs written for [id] before a failure, so a partial
     * import (e.g. media blob written but thumbnail failed) leaves no orphaned ciphertext.
     */
    private fun cleanUpPartialBlobs(id: String) {
        runCatching { fileStore.delete(id) }
        runCatching { fileStore.delete(thumbId(id)) }
    }

    private companion object {
        /** Suffix that derives the thumbnail blob's item id from the media item id. */
        const val THUMB_SUFFIX = "-thumb"

        fun thumbId(id: String): String = id + THUMB_SUFFIX
    }
}
