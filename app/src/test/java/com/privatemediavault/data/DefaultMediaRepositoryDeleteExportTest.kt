package com.privatemediavault.data

import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.AuthResult
import com.privatemediavault.domain.model.ExportResult
import com.privatemediavault.domain.model.MediaType
import com.privatemediavault.domain.model.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Example/unit tests for the deterministic delete and export flows of
 * [DefaultMediaRepository] (task 6.6). These are concrete, single-scenario flows rather
 * than universal properties, so plain JUnit examples are used instead of jqwik.
 *
 * Both collaborators are pure-JVM fakes so the coordination logic stays verifiable
 * off-device:
 *
 *  - [RecordingMediaDao] — in-memory [MediaDao] that records rows and replays them through
 *    `observeAll` (a real Room DB needs an Android runtime).
 *  - [RecordingFileStore] — in-memory [EncryptedFileStore] that records every `delete`
 *    request and serves stored bytes on `exportTo`; it can be switched to refuse export
 *    with [IllegalStateException] to simulate a locked session.
 *
 * Covers:
 *  - delete removes the metadata row and requests deletion of BOTH the media and the
 *    thumbnail blob, returns `true` for an existing item and `false` for a missing one,
 *    and drops the item from `observeItems` (Req 10.2, 10.3).
 *  - export writes the decrypted bytes to the destination and returns
 *    [ExportResult.Success] while unlocked (Req 11.1).
 *  - export returns [ExportResult.SessionLocked] when the store refuses due to a locked
 *    session (Req 11.1, 11.2).
 */
class DefaultMediaRepositoryDeleteExportTest {

    private val mediaId = "item-1"
    private val thumbId = "$mediaId-thumb"

    @Test
    fun `deleteItem removes metadata row and both blobs and returns true for existing item`() =
        runTest {
            val dao = RecordingMediaDao()
            val fileStore = RecordingFileStore()
            dao.insert(mediaItem(mediaId))
            val repository = newRepository(dao, fileStore)

            // Sanity: the item is present before deletion.
            assertEquals(setOf(mediaId), repository.observeItems().first().map { it.id }.toSet())

            val deleted = repository.deleteItem(mediaId)

            assertTrue("deleteItem must report true when the item existed", deleted)
            // Both the media blob and the thumbnail blob must have been requested for
            // deletion (Req 10.2).
            assertEquals(
                "delete must request removal of both the media and thumbnail blobs",
                listOf(mediaId, thumbId),
                fileStore.deletedIds
            )
            // The metadata row is gone, so the item no longer appears in the vault
            // contents (Req 10.3).
            assertTrue(
                "deleted item must not appear in observeItems",
                repository.observeItems().first().none { it.id == mediaId }
            )
        }

    @Test
    fun `deleteItem returns false when the item does not exist`() = runTest {
        val dao = RecordingMediaDao()
        val fileStore = RecordingFileStore()
        val repository = newRepository(dao, fileStore)

        val deleted = repository.deleteItem("missing")

        assertFalse("deleteItem must report false when no metadata row existed", deleted)
        // Blob deletion is attempted regardless (it touches no key material); both ids
        // are still requested so no orphaned ciphertext can survive.
        assertEquals(
            listOf("missing", "missing-thumb"),
            fileStore.deletedIds
        )
        assertTrue(repository.observeItems().first().isEmpty())
    }

    @Test
    fun `exportItem writes decrypted bytes and returns Success when unlocked`() = runTest {
        val dao = RecordingMediaDao()
        val plaintext = byteArrayOf(10, 20, 30, 40, 50)
        val fileStore = RecordingFileStore().apply { putBlob(mediaId, plaintext) }
        val repository = newRepository(dao, fileStore)

        val destination = ByteArrayOutputStream()
        val result = repository.exportItem(mediaId) { destination }

        assertEquals(ExportResult.Success, result)
        assertArrayEquals(
            "export must write the decrypted bytes to the destination",
            plaintext,
            destination.toByteArray()
        )
    }

    @Test
    fun `exportItem returns SessionLocked when the store refuses due to a locked session`() =
        runTest {
            val dao = RecordingMediaDao()
            val fileStore = RecordingFileStore().apply { lockSession() }
            val repository = newRepository(dao, fileStore)

            val destination = ByteArrayOutputStream()
            val result = repository.exportItem(mediaId) { destination }

            assertEquals(ExportResult.SessionLocked, result)
            assertEquals(
                "no plaintext may be produced while the session is locked",
                0,
                destination.size()
            )
        }

    private fun newRepository(dao: MediaDao, fileStore: EncryptedFileStore): DefaultMediaRepository =
        DefaultMediaRepository(
            dao = dao,
            fileStore = fileStore,
            sessionManager = AlwaysUnlockedSessionManager(),
            idGenerator = { mediaId },
            clock = { 0L },
        )

    private fun mediaItem(id: String): MediaItem = MediaItem(
        id = id,
        displayName = "name-$id",
        mediaType = MediaType.IMAGE,
        encryptedFileName = "$id.enc",
        sizeBytes = 1024L,
        durationMs = null,
        importedAt = 0L,
        encryptedThumbName = "$id-thumb.enc",
    )

    /**
     * In-memory [MediaDao]: records inserted rows and replays them through [observeAll],
     * newest-first to mirror the real query ordering. Only the operations the repository's
     * delete/export/observe paths touch are implemented.
     */
    private class RecordingMediaDao : MediaDao {
        private val rows = LinkedHashMap<String, MediaItem>()
        private val state = MutableStateFlow<List<MediaItem>>(emptyList())

        override suspend fun insert(item: MediaItem) {
            rows[item.id] = item
            emit()
        }

        override suspend fun deleteById(id: String): Int {
            val removed = rows.remove(id) != null
            if (removed) emit()
            return if (removed) 1 else 0
        }

        override fun observeAll(): Flow<List<MediaItem>> = state.asStateFlow()

        private fun emit() {
            state.value = rows.values.sortedByDescending { it.importedAt }
        }
    }

    /**
     * [EncryptedFileStore] fake that records every [delete] request (in call order) and
     * serves stored bytes on [exportTo]. When [locked] is set, [exportTo] throws
     * [IllegalStateException] to mimic the real store refusing while the session is locked.
     */
    private class RecordingFileStore : EncryptedFileStore {
        private val blobs = HashMap<String, ByteArray>()
        private var locked = false
        val deletedIds = mutableListOf<String>()

        fun putBlob(itemId: String, bytes: ByteArray) {
            blobs[itemId] = bytes
        }

        fun lockSession() {
            locked = true
        }

        override fun importFrom(source: () -> InputStream, itemId: String): String {
            val bytes = source().use { it.readBytes() }
            blobs[itemId] = bytes
            return "$itemId.enc"
        }

        override fun openDecrypted(itemId: String): InputStream {
            check(!locked) { "session locked" }
            return ByteArrayInputStream(blobs.getValue(itemId))
        }

        override fun exportTo(itemId: String, destination: () -> OutputStream) {
            check(!locked) { "session locked" }
            destination().use { it.write(blobs.getValue(itemId)) }
        }

        override fun delete(itemId: String): Boolean {
            deletedIds += itemId
            return blobs.remove(itemId) != null
        }
    }

    /**
     * Minimal always-unlocked [SessionManager]. The delete/export paths under test never
     * consult the session directly (export gating is delegated to the file store), but
     * [DefaultMediaRepository] requires one; the unused auth/DEK methods are stubbed.
     */
    private class AlwaysUnlockedSessionManager : SessionManager {
        private val dek: SecretKey = SecretKeySpec(ByteArray(32), "AES")
        private val state = MutableStateFlow<SessionState>(SessionState.Unlocked(0L))

        override val sessionState: StateFlow<SessionState> = state.asStateFlow()

        override fun authenticate(pin: CharArray): AuthResult =
            throw UnsupportedOperationException("not used by delete/export tests")

        override fun isUnlocked(): Boolean = true

        override fun withDek(block: (SecretKey) -> Unit) = block(dek)

        override fun endSession() {
            state.value = SessionState.Locked
        }
    }
}
