package com.privatemediavault.viewmodel

import com.privatemediavault.data.EncryptedFileStore
import com.privatemediavault.data.DefaultMediaRepository
import com.privatemediavault.data.ImportSource
import com.privatemediavault.data.MediaDao
import com.privatemediavault.data.MediaItem
import com.privatemediavault.data.MediaRepository
import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.AuthResult
import com.privatemediavault.domain.model.ExportResult
import com.privatemediavault.domain.model.ImportReport
import com.privatemediavault.domain.model.MediaType
import com.privatemediavault.domain.model.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Example/unit tests for the viewer's session-denied and re-blur-failure flows
 * (task 9.5). These are concrete, single-scenario behaviours rather than universal
 * properties, so plain JUnit examples are used instead of jqwik.
 *
 * All collaborators are pure-JVM fakes so the viewer/repository coordination stays
 * verifiable off-device:
 *
 *  - [ToggleableSessionManager] — a [SessionManager] whose unlocked/locked state can be
 *    flipped, so the unblur gate can be exercised in either state.
 *  - [FakeViewerRepository] — a [MediaRepository] that serves (or refuses) the decrypted
 *    media bytes and records whether `decryptedMedia` was ever invoked.
 *  - For the export-denial flow, the **real** [DefaultMediaRepository] is driven over a
 *    recording [EncryptedFileStore] so the genuine export gating is exercised.
 *
 * [ViewerViewModel] launches work on its `viewModelScope` (backed by `Dispatchers.Main`),
 * so the tests install a [StandardTestDispatcher] as Main and drive the coroutines to
 * quiescence with `advanceUntilIdle()` before asserting on the published state/events.
 *
 * Covers:
 *  - unblur() while locked → the item stays blurred (no clear bytes) and a
 *    [ViewerEvent.NavigateToPin] is emitted; the repository is never asked to decrypt
 *    (Req 7.2).
 *  - unblur() while unlocked → the item reaches Clear State with the decrypted bytes
 *    (the happy path that contrasts the denial).
 *  - reblur() whose blur step throws → the item is **kept** in Clear State and the failure
 *    is surfaced via `errorMessage` (Req 8.2).
 *  - export denied while locked → [ExportResult.SessionLocked] with no plaintext written,
 *    and the export is not resumed automatically: it must be explicitly re-initiated after
 *    the session is unlocked (Req 11.2, 11.3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelDeniedActionsTest {

    private val mediaId = "item-1"
    private val item: MediaItem = MediaItem(
        id = mediaId,
        displayName = "item",
        mediaType = MediaType.IMAGE,
        encryptedFileName = "$mediaId.enc",
        sizeBytes = 1024L,
        durationMs = null,
        importedAt = 0L,
        encryptedThumbName = "$mediaId-thumb.enc",
    )

    // Req 7.2: an unblur attempted with no active session is denied — the item stays
    // blurred, no clear bytes are produced, and the PIN entry screen is requested.
    @Test
    fun `unblur while locked keeps the item blurred and routes to PIN entry`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val repository = FakeViewerRepository(media = byteArrayOf(1, 2, 3))
                val session = ToggleableSessionManager(initiallyUnlocked = false)
                val viewModel = ViewerViewModel(item, repository, session)

                val events = mutableListOf<ViewerEvent>()
                backgroundScope.launch { viewModel.events.collect { events += it } }
                advanceUntilIdle()

                viewModel.unblur()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                assertFalse("a denied unblur must leave the item blurred", state.isClear)
                assertNull("no clear bytes may exist after a denied unblur", state.mediaBytes)
                assertFalse(state.isLoading)
                assertEquals(
                    "a locked unblur must request the PIN entry screen exactly once",
                    listOf(ViewerEvent.NavigateToPin),
                    events,
                )
                assertFalse(
                    "a locked unblur must never ask the repository to decrypt media",
                    repository.decryptedMediaCalled,
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // Happy-path contrast to the denial above: while unlocked, unblur reaches Clear State
    // with the decrypted bytes and emits no navigation event (Req 7.1).
    @Test
    fun `unblur while unlocked reaches clear state with the decrypted bytes`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val clearBytes = byteArrayOf(9, 8, 7, 6)
                val repository = FakeViewerRepository(media = clearBytes)
                val session = ToggleableSessionManager(initiallyUnlocked = true)
                val viewModel = ViewerViewModel(item, repository, session)

                val events = mutableListOf<ViewerEvent>()
                backgroundScope.launch { viewModel.events.collect { events += it } }
                advanceUntilIdle()

                viewModel.unblur()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                assertTrue("an unlocked unblur must reach Clear State", state.isClear)
                assertArrayEquals(
                    "the clear state must hold the decrypted bytes",
                    clearBytes,
                    state.mediaBytes,
                )
                assertFalse(state.isLoading)
                assertNull(state.errorMessage)
                assertTrue("a successful unblur must not route to PIN entry", events.isEmpty())
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // Req 8.2: when the blur step fails, the item must NOT silently appear hidden — it is
    // kept in Clear State and the failure is reported to the user.
    @Test
    fun `reblur whose blur step fails keeps the item clear and reports the failure`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val repository = FakeViewerRepository(media = byteArrayOf(1, 2, 3, 4))
                val session = ToggleableSessionManager(initiallyUnlocked = true)
                val viewModel = ViewerViewModel(
                    item = item,
                    repository = repository,
                    sessionManager = session,
                    reblurAction = { throw RuntimeException("blur renderer unavailable") },
                )

                // First reach Clear State so re-blur has something to hide.
                viewModel.unblur()
                advanceUntilIdle()
                assertTrue("precondition: the item must be clear before re-blur", viewModel.uiState.value.isClear)

                viewModel.reblur()

                val state = viewModel.uiState.value
                assertTrue("a failed re-blur must keep the item in Clear State", state.isClear)
                assertNotNull("a failed re-blur must surface an error message", state.errorMessage)
                assertTrue(
                    "the error message must tell the user the item is still visible",
                    state.errorMessage!!.contains("still visible"),
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // Req 11.2/11.3: an export attempted while locked is denied (SessionLocked) with no
    // plaintext written, and it is not resumed automatically — the user must explicitly
    // re-initiate the export after unlocking.
    @Test
    fun `export denied while locked returns SessionLocked and must be explicitly re-initiated`() =
        runTest {
            val plaintext = byteArrayOf(11, 22, 33, 44, 55)
            val dao = RecordingMediaDao()
            val fileStore = RecordingFileStore().apply { putBlob(mediaId, plaintext) }
            val repository = DefaultMediaRepository(
                dao = dao,
                fileStore = fileStore,
                sessionManager = AlwaysUnlockedSessionManager(),
                idGenerator = { mediaId },
                clock = { 0L },
            )

            // (1) Locked: the export is refused and no plaintext is produced (Req 11.2).
            fileStore.lockSession()
            val firstDestination = ByteArrayOutputStream()
            val denied = repository.exportItem(mediaId) { firstDestination }

            assertEquals(ExportResult.SessionLocked, denied)
            assertEquals(
                "a denied export must not write any plaintext to the destination",
                0,
                firstDestination.size(),
            )
            assertEquals(
                "a denied export must not produce a decrypted copy",
                0,
                fileStore.successfulExports,
            )

            // (2) No automatic resume: the repository performs exactly one attempt per
            //     explicit call. While the session is still locked nothing has re-tried the
            //     export on its own (Req 11.3).
            assertEquals(
                "the denied export must not be retried automatically",
                1,
                fileStore.exportAttempts,
            )

            // (3) The user re-initiates the export after the session is unlocked; only this
            //     explicit second call produces the decrypted copy (Req 11.3).
            fileStore.unlockSession()
            val secondDestination = ByteArrayOutputStream()
            val success = repository.exportItem(mediaId) { secondDestination }

            assertEquals(ExportResult.Success, success)
            assertArrayEquals(
                "the re-initiated export must write the decrypted bytes",
                plaintext,
                secondDestination.toByteArray(),
            )
            assertEquals(
                "exactly one extra explicit attempt happened on re-initiation",
                2,
                fileStore.exportAttempts,
            )
            assertEquals(
                "only the re-initiated export produced a decrypted copy",
                1,
                fileStore.successfulExports,
            )
        }

    /**
     * [SessionManager] whose unlocked/locked state is fixed at construction (enough for the
     * viewer gating tests). Auth/DEK operations are unused here and fail loudly if invoked.
     */
    private class ToggleableSessionManager(initiallyUnlocked: Boolean) : SessionManager {
        private val state = MutableStateFlow<SessionState>(
            if (initiallyUnlocked) SessionState.Unlocked(0L) else SessionState.Locked,
        )

        override val sessionState: StateFlow<SessionState> = state.asStateFlow()

        override fun authenticate(pin: CharArray): AuthResult =
            error("authenticate must not run in these viewer tests")

        override fun isUnlocked(): Boolean = state.value is SessionState.Unlocked

        override fun withDek(block: (SecretKey) -> Unit) =
            error("withDek must not run in these viewer tests")

        override fun endSession() {
            state.value = SessionState.Locked
        }
    }

    /**
     * [MediaRepository] that serves the decrypted media bytes for the viewed item and
     * records whether `decryptedMedia` was ever called (so a denied unblur can be proven to
     * never reach the decrypt path). The other operations are unused by these tests.
     */
    private class FakeViewerRepository(private val media: ByteArray) : MediaRepository {
        var decryptedMediaCalled: Boolean = false
            private set

        override fun observeItems(): Flow<List<MediaItem>> =
            MutableStateFlow<List<MediaItem>>(emptyList()).asStateFlow()

        override suspend fun importItems(
            sources: List<ImportSource>,
            removeOriginals: Boolean,
        ): ImportReport = error("importItems must not run in these viewer tests")

        override suspend fun deleteItem(id: String): Boolean =
            error("deleteItem must not run in these viewer tests")

        override suspend fun exportItem(id: String, destination: () -> OutputStream): ExportResult =
            error("exportItem must not run in these viewer tests")

        override suspend fun decryptedThumbnail(id: String): ByteArray =
            error("decryptedThumbnail must not run in these viewer tests")

        override suspend fun decryptedMedia(id: String): ByteArray {
            decryptedMediaCalled = true
            return media
        }
    }

    /**
     * In-memory [MediaDao]: enough to satisfy [DefaultMediaRepository]'s constructor for the
     * export flow. The export path under test never touches the DAO.
     */
    private class RecordingMediaDao : MediaDao {
        private val rows = LinkedHashMap<String, MediaItem>()
        private val state = MutableStateFlow<List<MediaItem>>(emptyList())

        override suspend fun insert(item: MediaItem) {
            rows[item.id] = item
            state.value = rows.values.toList()
        }

        override suspend fun deleteById(id: String): Int = if (rows.remove(id) != null) 1 else 0

        override fun observeAll(): Flow<List<MediaItem>> = state.asStateFlow()
    }

    /**
     * [EncryptedFileStore] fake that serves stored bytes on [exportTo] and counts every
     * attempt and every successful export. When [locked], [exportTo] throws
     * [IllegalStateException] *before* writing — mimicking the real store refusing a locked
     * session — so a denied export records an attempt but no successful export and no bytes.
     */
    private class RecordingFileStore : EncryptedFileStore {
        private val blobs = HashMap<String, ByteArray>()
        private var locked = false
        var exportAttempts = 0
            private set
        var successfulExports = 0
            private set

        fun putBlob(itemId: String, bytes: ByteArray) {
            blobs[itemId] = bytes
        }

        fun lockSession() {
            locked = true
        }

        fun unlockSession() {
            locked = false
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
            exportAttempts++
            check(!locked) { "session locked" }
            destination().use { it.write(blobs.getValue(itemId)) }
            successfulExports++
        }

        override fun delete(itemId: String): Boolean = blobs.remove(itemId) != null
    }

    /**
     * Minimal always-unlocked [SessionManager] required by [DefaultMediaRepository]. The
     * export path delegates session-gating to the file store, so this is construction-only.
     */
    private class AlwaysUnlockedSessionManager : SessionManager {
        private val dek: SecretKey = SecretKeySpec(ByteArray(32), "AES")
        private val state = MutableStateFlow<SessionState>(SessionState.Unlocked(0L))

        override val sessionState: StateFlow<SessionState> = state.asStateFlow()

        override fun authenticate(pin: CharArray): AuthResult =
            throw UnsupportedOperationException("not used by the export test")

        override fun isUnlocked(): Boolean = true

        override fun withDek(block: (SecretKey) -> Unit) = block(dek)

        override fun endSession() {
            state.value = SessionState.Locked
        }
    }
}
