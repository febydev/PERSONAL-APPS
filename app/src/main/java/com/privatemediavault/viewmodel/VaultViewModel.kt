package com.privatemediavault.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.privatemediavault.data.ImportSource
import com.privatemediavault.data.InMemorySettingsStore
import com.privatemediavault.data.MediaItem
import com.privatemediavault.data.MediaRepository
import com.privatemediavault.data.SettingsStore
import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.FailedImport
import com.privatemediavault.domain.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A single grid cell: the persisted [item] metadata paired with its runtime
 * [renderState]. The render state is in-memory only and defaults to Blurred State, so
 * every cell renders blurred until the user explicitly clears it in the viewer (Req 6.1).
 */
data class VaultGridItem(
    val item: MediaItem,
    val renderState: MediaRenderState,
)

/**
 * View state observed by `VaultGridScreen`.
 *
 * @param items         the vault contents, each carrying its blurred/clear render state.
 * @param isImporting   `true` while an import batch is in flight (drives a progress hint).
 * @param importErrors  per-file failures from the most recent import (Req 4.3); cleared
 *                      once the user dismisses them.
 * @param removeOriginals whether a successful import should delete the source original
 *                      from its device location (Req 4.4). Toggled from Settings (task 9.6).
 */
data class VaultUiState(
    val items: List<VaultGridItem> = emptyList(),
    val isImporting: Boolean = false,
    val importErrors: List<FailedImport> = emptyList(),
    val removeOriginals: Boolean = false,
)

/**
 * Backs `VaultGridScreen`. Observes the vault contents from [MediaRepository], holds the
 * runtime [MediaRenderState] for every item, and surfaces the import action and its
 * per-file failures.
 *
 * Blurred-by-default (Req 6.1): every item is loaded with `isClear = false` through
 * [MediaRenderStateHolder], so the grid renders entirely blurred. Unblurring happens only
 * in the media viewer (task 9.4); the grid merely exposes a selection hook via the screen's
 * click callback.
 *
 * Re-blur on lock (Req 6.3): the view model observes [SessionManager.sessionState] and, on
 * any transition to [SessionState.Locked], drives every item back to Blurred State via
 * [MediaRenderStateHolder.resetAllToBlurred]. This is total and idempotent, so no item can
 * linger in Clear State across a lock.
 *
 * The repository contract is expressed over device-independent [ImportSource]s; the
 * injected [importSourceFactory] bridges the picker's `android.net.Uri`s to those sources
 * (the same bridging the `AndroidUriMediaRepository` performs), keeping the import
 * partitioning and session logic verifiable off-device.
 *
 * @param repository          source of vault contents, import, and thumbnail decryption.
 * @param sessionManager      observed for lock transitions that force a re-blur (Req 6.3).
 * @param importSourceFactory turns a picked `Uri` into a device-independent [ImportSource].
 * @param renderStateHolder   holds the per-item blurred/clear render state.
 * @param settingsStore       shared source of truth for the remove-originals preference
 *                            (Req 4.4); written from `SettingsScreen` and read here on
 *                            import so a toggle made in Settings is honoured immediately.
 */
class VaultViewModel(
    private val repository: MediaRepository,
    private val sessionManager: SessionManager,
    private val importSourceFactory: (Uri) -> ImportSource,
    private val renderStateHolder: MediaRenderStateHolder = MediaRenderStateHolder(),
    private val settingsStore: SettingsStore = InMemorySettingsStore(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    /** Latest items observed from the repository, kept so render-state changes can re-publish. */
    private var latestItems: List<MediaItem> = emptyList()

    /**
     * The user's global reveal-all override (Req: Reveal All / Blur All). When `true` and the
     * session is unlocked, every item is published clear; when `false` the normal per-item
     * blurred-by-default state holds. Defaults to `false`, so a fresh load stays blurred.
     */
    private var revealAll: Boolean = false

    /**
     * Whether the session is currently locked. A locked session always forces blurred render,
     * overriding [revealAll], so nothing stays clear once the vault is no longer authenticated.
     */
    private var sessionLocked: Boolean = false

    init {
        observeItems()
        observeSession()
        observeRemoveOriginals()
        observeRevealAll()
    }

    /**
     * Mirrors the repository's vault contents into the UI state. On every emission the
     * render states are reconciled: existing items keep their current blurred/clear flag
     * and any newly imported items default to Blurred State (Req 6.1).
     */
    private fun observeItems() {
        viewModelScope.launch {
            repository.observeItems().collect { items ->
                latestItems = items
                reconcileRenderStates(items)
                publish()
            }
        }
    }

    /**
     * Watches the session and forces every item back to Blurred State the moment the
     * session locks (background, explicit lock, or timeout), so nothing remains clear
     * once the vault is no longer authenticated (Req 6.3, 9.1, 9.2).
     */
    private fun observeSession() {
        viewModelScope.launch {
            sessionManager.sessionState.collect { state ->
                val locked = state is SessionState.Locked
                sessionLocked = locked
                if (locked) {
                    renderStateHolder.resetAllToBlurred()
                }
                publish()
            }
        }
    }

    /**
     * Observes the shared reveal-all override (Req: Reveal All / Blur All). When the user
     * flips it in Settings the grid re-publishes immediately so every item unblurs (or
     * re-blurs) at once. The override is honoured only while unlocked; [publish] suppresses it
     * whenever the session is locked.
     */
    private fun observeRevealAll() {
        viewModelScope.launch {
            settingsStore.revealAll.collect { enabled ->
                if (revealAll != enabled) {
                    revealAll = enabled
                    publish()
                }
            }
        }
    }

    /**
     * Reloads the render-state holder for [items] while preserving the clear flag of any
     * item that is still present. New items default to blurred; removed items drop out.
     */
    private fun reconcileRenderStates(items: List<MediaItem>) {
        val previouslyClear = renderStateHolder.renderStates()
            .filter { it.isClear }
            .mapTo(HashSet()) { it.itemId }
        renderStateHolder.load(items.map { it.id })
        previouslyClear.forEach { id -> renderStateHolder.setClear(id, isClear = true) }
    }

    /**
     * Launches the import of the picked [uris]. Each source is imported independently, so a
     * single failure never aborts the batch; the resulting per-file failures are surfaced in
     * [VaultUiState.importErrors] (Req 4.3). Successful imports flow back into the grid
     * through [observeItems], rendered blurred by default (Req 4.2). Originals are removed
     * only when [VaultUiState.removeOriginals] is enabled (Req 4.4).
     */
    fun importFromPicker(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val removeOriginals = _uiState.value.removeOriginals
        _uiState.value = _uiState.value.copy(isImporting = true)
        viewModelScope.launch {
            val report = try {
                repository.importItems(uris.map(importSourceFactory), removeOriginals)
            } catch (t: Throwable) {
                // A wholesale failure (e.g. session locked before encryption) is reported as
                // a single batch-level error rather than crashing the screen.
                _uiState.value = _uiState.value.copy(
                    isImporting = false,
                    importErrors = listOf(
                        FailedImport("Import", t.message ?: t.javaClass.simpleName),
                    ),
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isImporting = false,
                importErrors = report.failed,
            )
        }
    }

    /**
     * Mirrors the shared remove-originals preference into the UI state so the import flow
     * (and any UI hint) reads the same value the settings screen writes (Req 4.4).
     */
    private fun observeRemoveOriginals() {
        viewModelScope.launch {
            settingsStore.removeOriginals.collect { enabled ->
                if (_uiState.value.removeOriginals != enabled) {
                    _uiState.value = _uiState.value.copy(removeOriginals = enabled)
                }
            }
        }
    }

    /** Sets whether successful imports should delete their source originals (Req 4.4). */
    fun setRemoveOriginals(enabled: Boolean) {
        settingsStore.setRemoveOriginals(enabled)
    }

    /** Dismisses the surfaced import failures after the user has acknowledged them. */
    fun dismissImportErrors() {
        if (_uiState.value.importErrors.isEmpty()) return
        _uiState.value = _uiState.value.copy(importErrors = emptyList())
    }

    /**
     * Decrypts the thumbnail bytes for [id] to be shown (under the blur) in the grid.
     * Session-gated through the repository; returns `null` when the session is locked or the
     * thumbnail cannot be read, so the grid can fall back to a placeholder rather than fail.
     */
    suspend fun loadThumbnail(id: String): ByteArray? =
        runCatching { repository.decryptedThumbnail(id) }.getOrNull()

    /** Rebuilds the UI item list from the latest items and current render states. */
    private fun publish() {
        val renderById = renderStateHolder.renderStates().associateBy { it.itemId }
        // The reveal-all override only applies while unlocked; a locked session always blurs.
        val revealAllActive = revealAll && !sessionLocked
        val gridItems = latestItems.map { item ->
            val base = renderById[item.id] ?: MediaRenderState(item.id, isClear = false)
            val effective = if (revealAllActive) base.copy(isClear = true) else base
            VaultGridItem(
                item = item,
                renderState = effective,
            )
        }
        _uiState.value = _uiState.value.copy(items = gridItems)
    }

    /**
     * Constructs [VaultViewModel] with its dependencies. The activity-level wiring (task
     * 10.1) supplies the concrete [MediaRepository], [SessionManager], and the `Uri` ->
     * [ImportSource] bridge.
     */
    class Factory(
        private val repository: MediaRepository,
        private val sessionManager: SessionManager,
        private val importSourceFactory: (Uri) -> ImportSource,
        private val settingsStore: SettingsStore = InMemorySettingsStore(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VaultViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return VaultViewModel(
                repository,
                sessionManager,
                importSourceFactory,
                settingsStore = settingsStore,
            ) as T
        }
    }
}
