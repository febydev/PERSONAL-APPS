package com.privatemediavault.data

import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.AuthResult
import com.privatemediavault.domain.model.MediaType
import com.privatemediavault.domain.model.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Property-based test for Property 11 — Import partitions inputs without loss.
 *
 * Exercises the real [DefaultMediaRepository.importItems] batch logic over pure-JVM
 * fakes for its two collaborators:
 *
 *  - [RecordingMediaDao] — an in-memory [MediaDao] that records inserted rows and exposes
 *    them through `observeAll` (a real Room in-memory DB needs an Android runtime, so a
 *    fake stands in for the metadata store).
 *  - [SelectiveFailingFileStore] — an [EncryptedFileStore] that reads each source stream
 *    and stores the bytes in memory; a source designated as failing throws when its bytes
 *    are read (its `openStream` raises), driving the per-file failure path.
 *
 * A deterministic, sequential id generator (`item-<index>`) lets the test map each
 * generated success id back to the input source at that position, and each source name
 * (`src-<index>`) carries its position too — so both halves of the [ImportReport] can be
 * projected onto the input index space and compared for partition completeness.
 */
class DefaultMediaRepositoryImportPartitioningPropertyTest {

    // Feature: private-media-vault, Property 11: Import partitions inputs without loss
    // Validates: Requirements 4.1, 4.3
    // For any list of sources where an arbitrary subset fails: ImportReport.succeeded and
    // failed are disjoint, their union (by source identity) equals the input set, and
    // every successful item is present in the vault contents (observeItems).
    @Property(tries = 100)
    fun `import partitions inputs into disjoint succeeded and failed sets without loss`(
        @ForAll("failureMasks") failing: List<Boolean>
    ) = runBlocking {
        val dao = RecordingMediaDao()
        val fileStore = SelectiveFailingFileStore()
        val counter = AtomicInteger(0)
        val repository = DefaultMediaRepository(
            dao = dao,
            fileStore = fileStore,
            sessionManager = AlwaysUnlockedSessionManager(),
            // i-th call corresponds to sources[i]; iteration is sequential, so the id
            // "item-<i>" uniquely identifies the source at input position i.
            idGenerator = { "item-${counter.getAndIncrement()}" },
            clock = { 0L },
        )

        val sources = failing.mapIndexed { index, willFail -> source(index, willFail) }

        val report = repository.importItems(sources, removeOriginals = false)

        // Project both halves of the report back onto the input index space.
        val succeededIndices = report.succeeded.map { it.removePrefix("item-").toInt() }
        val failedIndices = report.failed.map { it.sourceName.removePrefix("src-").toInt() }
        val allIndices = sources.indices.toList()

        // --- Disjoint: no input appears as both a success and a failure. ---
        val overlap = succeededIndices.toSet().intersect(failedIndices.toSet())
        assertTrue("succeeded and failed sets must be disjoint, overlap=$overlap", overlap.isEmpty())

        // --- No loss: succeeded ∪ failed covers exactly the input set (no extras). ---
        assertEquals(
            "every input must appear exactly once across succeeded and failed",
            allIndices.toSet(),
            succeededIndices.toSet() + failedIndices.toSet()
        )
        assertEquals(
            "the report must account for exactly as many entries as inputs",
            sources.size,
            report.succeeded.size + report.failed.size
        )

        // --- Partition matches the designated failing subset. ---
        val designatedFailing = failing.indices.filter { failing[it] }.toSet()
        assertEquals(
            "failed set must be exactly the designated-failing inputs",
            designatedFailing,
            failedIndices.toSet()
        )

        // --- Every successful item is present in the vault contents. ---
        val vaultContents = repository.observeItems().first()
        val vaultIds = vaultContents.map { it.id }.toSet()
        for (id in report.succeeded) {
            assertTrue("succeeded id $id must be present in vault contents", id in vaultIds)
            val index = id.removePrefix("item-").toInt()
            val row = vaultContents.first { it.id == id }
            assertEquals(
                "vault row for $id must carry its source's display name",
                "src-$index",
                row.displayName
            )
        }
        // Vault contents must hold exactly the successful items — failures leave no row.
        assertEquals(
            "vault must contain exactly the successfully imported items",
            report.succeeded.toSet(),
            vaultIds
        )
    }

    /**
     * Builds one [ImportSource] at input position [index]. Its `sourceName` encodes the
     * position (`src-<index>`) so a [com.privatemediavault.domain.model.FailedImport] can
     * be mapped back to its input. When [willFail] is `true`, reading the media stream
     * throws, driving the per-file failure path; otherwise both streams yield bytes.
     */
    private fun source(index: Int, willFail: Boolean): ImportSource {
        val isVideo = index % 2 == 1
        return ImportSource(
            sourceName = "src-$index",
            mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            sizeBytes = (index + 1).toLong() * 1024L,
            durationMs = if (isVideo) (index + 1).toLong() * 100L else null,
            openStream = {
                if (willFail) throw IOException("designated failing source $index")
                ByteArrayInputStream(byteArrayOf(index.toByte(), 1, 2, 3))
            },
            openThumbnail = { ByteArrayInputStream(byteArrayOf(index.toByte(), 9, 8, 7)) },
            deleteOriginal = null,
        )
    }

    /**
     * Failure masks: each element marks the input at that position as failing (`true`) or
     * succeeding (`false`). Sizes 0..15 cover the empty batch, all-succeed, all-fail, and
     * arbitrary mixed subsets.
     */
    @Provide
    fun failureMasks(): Arbitrary<List<Boolean>> =
        Arbitraries.of(true, false).list().ofMaxSize(15)

    /**
     * In-memory [MediaDao]: records inserted rows keyed by id and replays them through
     * [observeAll], newest-first to mirror the real query ordering. Only the operations
     * [DefaultMediaRepository.importItems] / [observeItems] touch are implemented.
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
     * [EncryptedFileStore] fake that reads each source's bytes (so a throwing source drives
     * the failure path) and keeps them in an in-memory map keyed by item id. Returns a
     * deterministic blob name. Decryption/export are unused by import partitioning.
     */
    private class SelectiveFailingFileStore : EncryptedFileStore {
        private val blobs = HashMap<String, ByteArray>()

        override fun importFrom(source: () -> InputStream, itemId: String): String {
            val bytes = source().use { it.readBytes() }
            blobs[itemId] = bytes
            return "$itemId.enc"
        }

        override fun openDecrypted(itemId: String): InputStream =
            ByteArrayInputStream(blobs.getValue(itemId))

        override fun exportTo(itemId: String, destination: () -> OutputStream) {
            destination().use { it.write(blobs.getValue(itemId)) }
        }

        override fun delete(itemId: String): Boolean = blobs.remove(itemId) != null
    }

    /**
     * Minimal always-unlocked [SessionManager]. `importItems` never consults the session,
     * but [DefaultMediaRepository] requires one; the unused auth/DEK methods are stubbed.
     */
    private class AlwaysUnlockedSessionManager : SessionManager {
        private val dek: SecretKey = SecretKeySpec(ByteArray(32), "AES")
        private val state = MutableStateFlow<SessionState>(SessionState.Unlocked(0L))

        override val sessionState: StateFlow<SessionState> = state.asStateFlow()

        override fun authenticate(pin: CharArray): AuthResult =
            throw UnsupportedOperationException("not used by Property 11")

        override fun isUnlocked(): Boolean = true

        override fun withDek(block: (SecretKey) -> Unit) = block(dek)

        override fun endSession() {
            state.value = SessionState.Locked
        }
    }
}
