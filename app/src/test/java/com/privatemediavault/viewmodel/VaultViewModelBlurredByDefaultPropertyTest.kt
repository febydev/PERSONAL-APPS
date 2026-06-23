package com.privatemediavault.viewmodel

import android.net.Uri
import com.privatemediavault.data.ImportSource
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.OutputStream
import javax.crypto.SecretKey

/**
 * Property-based test for [VaultViewModel] blurred-by-default load.
 *
 * Feature: private-media-vault, Property 12: Items load blurred by default.
 * Statement: for any freshly loaded set of media items, every [MediaRenderState.isClear]
 * is `false` (Req 6.1).
 *
 * The test drives the invariant through the real [VaultViewModel] over pure-JVM fakes:
 *
 *  - [FixedItemsRepository] — a [MediaRepository] whose `observeItems()` immediately emits
 *    a generated list of [MediaItem]; the import/delete/export/thumbnail operations are
 *    unused by a fresh load and fail loudly if ever invoked.
 *  - [UnlockedSessionManager] — a [SessionManager] held in [SessionState.Unlocked] for the
 *    whole load, so the re-blur-on-lock path (Req 6.3) is *not* the reason items are
 *    blurred; the default-blurred guarantee is exercised on its own.
 *  - `importSourceFactory` — an unused stub bridging `Uri` -> [ImportSource]; a fresh load
 *    never imports, so it throws if invoked.
 *
 * [VaultViewModel] observes the repository on its `viewModelScope` (backed by
 * `Dispatchers.Main`), so the test installs a [StandardTestDispatcher] as Main and runs
 * the collector to quiescence with `advanceUntilIdle()` before asserting on the published
 * [VaultUiState]. The generated input also includes the empty set (which satisfies the
 * property vacuously) and varied ids/types up to a sizeable batch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelBlurredByDefaultPropertyTest {

    // Feature: private-media-vault, Property 12: Items load blurred by default
    // Validates: Requirements 6.1
    // For any freshly loaded set of media items, every published VaultGridItem's
    // renderState.isClear is false on initial load.
    @Property(tries = 100)
    fun `every freshly loaded item renders blurred by default`(
        @ForAll("mediaItems") items: List<MediaItem>,
    ) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val viewModel = VaultViewModel(
                    repository = FixedItemsRepository(items),
                    sessionManager = UnlockedSessionManager(),
                    importSourceFactory = { _ -> error("import must not run on a fresh load") },
                )

                // Let the init-time observeItems()/observeSession() collectors process the
                // repository's initial emission and publish the grid items.
                advanceUntilIdle()

                val gridItems = viewModel.uiState.value.items

                // The load must surface exactly the supplied items (so a populated batch is
                // not silently dropped, which would make the invariant vacuous).
                assertEquals(
                    "the grid must surface exactly the freshly loaded items",
                    items.map { it.id }.toSet(),
                    gridItems.map { it.item.id }.toSet(),
                )

                // The decisive post-condition (Req 6.1): nothing loads clear.
                val clear = gridItems.filter { it.renderState.isClear }
                assertTrue(
                    "every freshly loaded item must render blurred (isClear == false), " +
                        "but these were clear: " + clear.map { it.item.id },
                    clear.isEmpty(),
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A freshly loaded set of media items with unique ids and varied types. A map from id to
     * an "is video" flag keeps ids unique while letting the type mix be arbitrary; size 0..30
     * covers the empty vault (vacuously blurred) through a sizeable batch.
     */
    @Provide
    fun mediaItems(): Arbitrary<List<MediaItem>> {
        val ids: Arbitrary<String> =
            Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12)
        val isVideo: Arbitrary<Boolean> = Arbitraries.of(true, false)
        return Arbitraries.maps(ids, isVideo).ofMinSize(0).ofMaxSize(30).map { byId ->
            byId.entries.mapIndexed { index, (id, video) -> mediaItem(id, index, video) }
        }
    }

    /** Builds a metadata-only [MediaItem]; only its id and type vary across the input space. */
    private fun mediaItem(id: String, index: Int, isVideo: Boolean): MediaItem =
        MediaItem(
            id = id,
            displayName = "item-$index",
            mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            encryptedFileName = "$id.enc",
            sizeBytes = (index + 1).toLong() * 1024L,
            durationMs = if (isVideo) (index + 1).toLong() * 100L else null,
            importedAt = index.toLong(),
            encryptedThumbName = "$id.thumb.enc",
        )

    /**
     * [MediaRepository] whose `observeItems()` immediately replays a fixed set of items.
     * The import/delete/export/thumbnail operations are never touched by a fresh load and
     * fail loudly if invoked, proving the default-blurred guarantee is independent of them.
     */
    private class FixedItemsRepository(items: List<MediaItem>) : MediaRepository {
        private val state = MutableStateFlow(items)

        override fun observeItems(): Flow<List<MediaItem>> = state.asStateFlow()

        override suspend fun importItems(
            sources: List<ImportSource>,
            removeOriginals: Boolean,
        ): ImportReport = error("importItems must not run on a fresh load")

        override suspend fun deleteItem(id: String): Boolean =
            error("deleteItem must not run on a fresh load")

        override suspend fun exportItem(id: String, destination: () -> OutputStream): ExportResult =
            error("exportItem must not run on a fresh load")

        override suspend fun decryptedThumbnail(id: String): ByteArray =
            error("decryptedThumbnail must not run on a fresh load")

        override suspend fun decryptedMedia(id: String): ByteArray =
            error("decryptedMedia must not run on a fresh load")
    }

    /**
     * [SessionManager] held in [SessionState.Unlocked] for the whole load, so the
     * re-blur-on-lock path (Req 6.3) is not what keeps items blurred — the default-blurred
     * guarantee (Req 6.1) is exercised on its own. Auth/DEK operations are unused here.
     */
    private class UnlockedSessionManager : SessionManager {
        private val state = MutableStateFlow<SessionState>(SessionState.Unlocked(0L))

        override val sessionState: StateFlow<SessionState> = state.asStateFlow()

        override fun authenticate(pin: CharArray): AuthResult =
            error("authenticate must not run on a fresh load")

        override fun isUnlocked(): Boolean = true

        override fun withDek(block: (SecretKey) -> Unit) =
            error("withDek must not run on a fresh load")

        override fun endSession() {
            state.value = SessionState.Locked
        }
    }
}
