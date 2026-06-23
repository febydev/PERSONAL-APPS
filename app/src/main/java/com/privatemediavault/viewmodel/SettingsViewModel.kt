package com.privatemediavault.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.privatemediavault.data.MediaItem
import com.privatemediavault.data.MediaRepository
import com.privatemediavault.data.SettingsStore
import com.privatemediavault.domain.AuthService
import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.ChangeResult
import com.privatemediavault.domain.model.ExportResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream

/** Minimum number of digits a new PIN must have; mirrors the creation rule (Req 1.2). */
private const val MIN_PIN_LENGTH = 4

/**
 * Feedback for the change-PIN form. The form starts [Idle]; submitting moves it to
 * [Success] (Req 12.3) or [Error] with a user-facing message covering the wrong-current-PIN
 * (Req 12.2), too-short, mismatch, and active-lockout cases.
 */
sealed interface ChangePinFeedback {
    /** No change-PIN attempt has been made (or feedback was dismissed). */
    data object Idle : ChangePinFeedback

    /** The PIN was changed and the stored hash replaced (Req 12.1, 12.3). */
    data object Success : ChangePinFeedback

    /** The change was rejected; [message] explains why (Req 12.2 and validation). */
    data class Error(val message: String) : ChangePinFeedback
}

/**
 * View state observed by `SettingsScreen`.
 *
 * @param removeOriginals  whether successful imports delete their source originals (Req 4.4),
 *                         mirrored from the shared [SettingsStore].
 * @param changePinFeedback feedback for the most recent change-PIN attempt (Req 12.x).
 * @param pendingDelete    the item awaiting delete confirmation, or `null` when no
 *                         confirmation dialog is showing (Req 10.1).
 * @param statusMessage    a transient status line for export/delete outcomes; `null` when
 *                         there is nothing to report.
 */
data class SettingsUiState(
    val removeOriginals: Boolean = false,
    val revealAll: Boolean = false,
    val changePinFeedback: ChangePinFeedback = ChangePinFeedback.Idle,
    val pendingDelete: MediaItem? = null,
    val statusMessage: String? = null,
)

/**
 * One-shot effects emitted by [SettingsViewModel] that the host consumes exactly once.
 */
sealed interface SettingsEvent {
    /**
     * The vault must show the PIN entry screen: the User explicitly locked the vault
     * (Req 9.4) or a session-gated action (export) was attempted with no active session
     * (Req 11.2).
     */
    data object NavigateToPin : SettingsEvent
}

/**
 * Backs `SettingsScreen`. Bridges the settings UI to [AuthService] (PIN change),
 * [SessionManager] (explicit lock), [MediaRepository] (delete/export), and the shared
 * [SettingsStore] (remove-originals toggle).
 *
 * Responsibilities:
 *  - Change PIN (Req 12.1–12.3): submit current/new/confirm to [AuthService.changePin] and
 *    surface the wrong-current-PIN, too-short, mismatch, lockout, and success feedback. PIN
 *    [CharArray]s are zeroed after use so plaintext does not linger in memory.
 *  - Explicit lock (Req 9.4): end the session and emit [SettingsEvent.NavigateToPin].
 *  - Remove-originals toggle (Req 4.4): write through to the shared [SettingsStore] so the
 *    import flow honours it.
 *  - Delete confirmation (Req 10.1): hold the item awaiting confirmation and only call
 *    [MediaRepository.deleteItem] once the User confirms.
 *  - Export (Req 11.1, 11.2): write a decrypted copy to a User-selected destination while a
 *    session is active; on a locked session deny and route to PIN entry.
 *
 * The toggle's value is observed from the [SettingsStore] rather than held privately so it
 * stays consistent with the value the vault grid's import reads.
 */
class SettingsViewModel(
    private val authService: AuthService,
    private val sessionManager: SessionManager,
    private val repository: MediaRepository,
    private val settingsStore: SettingsStore,
    private val minPinLength: Int = MIN_PIN_LENGTH,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            removeOriginals = settingsStore.removeOriginals.value,
            revealAll = settingsStore.revealAll.value,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        observeRemoveOriginals()
        observeRevealAll()
    }

    /** Keeps [SettingsUiState.removeOriginals] in sync with the shared store (Req 4.4). */
    private fun observeRemoveOriginals() {
        viewModelScope.launch {
            settingsStore.removeOriginals.collect { enabled ->
                _uiState.value = _uiState.value.copy(removeOriginals = enabled)
            }
        }
    }

    /**
     * Keeps [SettingsUiState.revealAll] in sync with the shared store so the toggle reflects
     * the live override that the vault grid honours (Req: Reveal All / Blur All).
     */
    private fun observeRevealAll() {
        viewModelScope.launch {
            settingsStore.revealAll.collect { enabled ->
                _uiState.value = _uiState.value.copy(revealAll = enabled)
            }
        }
    }

    /**
     * Changes the PIN (Req 12.1–12.3). Validates the current PIN, the new PIN length, and
     * the confirmation match through [AuthService.changePin], then maps the [ChangeResult]
     * to user-facing feedback. All three arrays are zeroed before returning so plaintext
     * PINs do not linger in memory.
     */
    fun changePin(current: CharArray, newPin: CharArray, confirm: CharArray) {
        try {
            val feedback = when (val result = authService.changePin(current, newPin, confirm)) {
                ChangeResult.Success -> ChangePinFeedback.Success

                ChangeResult.WrongCurrentPin ->
                    // Req 12.2: deny and show an incorrect-PIN message.
                    ChangePinFeedback.Error("Current PIN is incorrect.")

                ChangeResult.TooShort ->
                    ChangePinFeedback.Error("New PIN must be at least $minPinLength digits.")

                ChangeResult.Mismatch ->
                    ChangePinFeedback.Error("New PIN entries did not match. Try again.")

                is ChangeResult.LockedOut ->
                    ChangePinFeedback.Error(
                        "Too many incorrect attempts. Try again in " +
                            "${result.remainingSeconds}s.",
                    )
            }
            _uiState.value = _uiState.value.copy(changePinFeedback = feedback)
        } finally {
            current.fill('\u0000')
            newPin.fill('\u0000')
            confirm.fill('\u0000')
        }
    }

    /** Dismisses the change-PIN feedback after the User has acknowledged it. */
    fun dismissChangePinFeedback() {
        if (_uiState.value.changePinFeedback == ChangePinFeedback.Idle) return
        _uiState.value = _uiState.value.copy(changePinFeedback = ChangePinFeedback.Idle)
    }

    /**
     * Explicitly locks the vault (Req 9.4): ends the session — which zeroes the in-memory
     * DEK and returns every item to Blurred State — and routes to PIN entry.
     */
    fun lock() {
        sessionManager.endSession()
        _events.tryEmit(SettingsEvent.NavigateToPin)
    }

    /** Sets whether successful imports should delete their source originals (Req 4.4). */
    fun setRemoveOriginals(enabled: Boolean) {
        settingsStore.setRemoveOriginals(enabled)
    }

    /**
     * Sets the global reveal-all override (Req: Reveal All / Blur All): when `true` every
     * item is shown unblurred at once; when `false` the blurred-by-default behaviour holds.
     * Only effective while the vault is unlocked.
     */
    fun setRevealAll(enabled: Boolean) {
        settingsStore.setRevealAll(enabled)
    }

    /**
     * Begins a delete (Req 10.1): records the [item] awaiting confirmation so the screen
     * shows a confirmation dialog. Deletion does not happen until [confirmDelete].
     */
    fun requestDelete(item: MediaItem) {
        _uiState.value = _uiState.value.copy(pendingDelete = item)
    }

    /** Cancels a pending delete, dismissing the confirmation without removing anything. */
    fun cancelDelete() {
        if (_uiState.value.pendingDelete == null) return
        _uiState.value = _uiState.value.copy(pendingDelete = null)
    }

    /**
     * Confirms the pending delete (Req 10.2, 10.3): permanently removes the item from Vault
     * Storage and metadata; the grid drops it via the repository's observed stream.
     */
    fun confirmDelete() {
        val item = _uiState.value.pendingDelete ?: return
        _uiState.value = _uiState.value.copy(pendingDelete = null)
        viewModelScope.launch {
            val removed = runCatching { repository.deleteItem(item.id) }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(
                statusMessage = if (removed) {
                    "Deleted \"${item.displayName}\"."
                } else {
                    "Could not delete \"${item.displayName}\"."
                },
            )
        }
    }

    /**
     * Exports a decrypted copy of the item identified by [id] to the stream produced by
     * [destination] (Req 11.1). Session-gated: when the session is locked the export is
     * refused and the User is routed to PIN entry, where they must re-initiate the export
     * after unlocking (Req 11.2, 11.3).
     */
    fun exportItem(id: String, displayName: String, destination: () -> OutputStream) {
        viewModelScope.launch {
            val result = runCatching { repository.exportItem(id, destination) }
                .getOrElse { ExportResult.Failed(it.message ?: it.javaClass.simpleName) }
            when (result) {
                ExportResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Exported \"$displayName\".",
                    )

                ExportResult.SessionLocked ->
                    // Req 11.2: deny and route to PIN entry; do not resume automatically.
                    _events.tryEmit(SettingsEvent.NavigateToPin)

                is ExportResult.Failed ->
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Export failed: ${result.reason}",
                    )
            }
        }
    }

    /** Clears the transient status line after the User has acknowledged it. */
    fun dismissStatusMessage() {
        if (_uiState.value.statusMessage == null) return
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    /**
     * Constructs [SettingsViewModel] with its dependencies. The activity-level wiring (task
     * 10.1) supplies the concrete [AuthService], [SessionManager], [MediaRepository], and
     * the shared [SettingsStore] (the same instance handed to [VaultViewModel]).
     */
    class Factory(
        private val authService: AuthService,
        private val sessionManager: SessionManager,
        private val repository: MediaRepository,
        private val settingsStore: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return SettingsViewModel(authService, sessionManager, repository, settingsStore) as T
        }
    }
}
