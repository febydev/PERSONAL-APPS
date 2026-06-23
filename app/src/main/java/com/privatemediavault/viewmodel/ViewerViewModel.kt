package com.privatemediavault.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.privatemediavault.data.MediaItem
import com.privatemediavault.data.MediaRepository
import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.SessionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * View state observed by `MediaViewerScreen` for a single media item.
 *
 * The viewer renders the item blurred by default (Req 6.1); the User unblurs it to reach
 * Clear State (Req 7.1) and re-blurs to return to Blurred State (Req 8.1). The decrypted
 * [mediaBytes] exist only while [isClear] is `true` and are dropped the moment the item
 * returns to blurred or the session ends, so clear content never lingers outside a
 * session (Req 5.4, 6.3).
 *
 * @param item         the metadata of the item being viewed.
 * @param isClear      `true` while the item is shown unblurred (Clear State).
 * @param mediaBytes   the decrypted image/video bytes; non-null only while [isClear].
 * @param isLoading    `true` while decryption of the clear content is in flight.
 * @param errorMessage a user-facing failure message (e.g. a failed re-blur, Req 8.2);
 *                     `null` when there is nothing to report.
 */
data class ViewerUiState(
    val item: MediaItem,
    val isClear: Boolean = false,
    val mediaBytes: ByteArray? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    // ByteArray needs structural equals/hashCode so StateFlow de-duplication compares the
    // decrypted content rather than the array reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewerUiState) return false
        return item == other.item &&
            isClear == other.isClear &&
            mediaBytes.contentEqualsNullable(other.mediaBytes) &&
            isLoading == other.isLoading &&
            errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = item.hashCode()
        result = 31 * result + isClear.hashCode()
        result = 31 * result + (mediaBytes?.contentHashCode() ?: 0)
        result = 31 * result + isLoading.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null) other == null else other != null && this.contentEquals(other)
}

/**
 * One-shot navigation effects emitted by [ViewerViewModel] that the screen/host consumes
 * exactly once (they are not part of the persistent UI state).
 */
sealed interface ViewerEvent {
    /**
     * The vault must show the PIN entry screen: either an unblur was attempted with no
     * active session (Req 7.2) or the User explicitly locked the vault (Req 9.4).
     */
    data object NavigateToPin : ViewerEvent
}

/**
 * Backs `MediaViewerScreen` for a single [MediaItem]. Owns the blur/clear render state of
 * the viewed item and the decrypted bytes shown while clear.
 *
 * Unblur (Req 7.1, 7.2): unblurring is allowed only while the session is unlocked. When a
 * session is active the item's clear bytes are decrypted (session-gated through the
 * repository) and the item moves to Clear State; for a video the screen then plays the
 * bytes with ExoPlayer (Req 7.3). When no session is active the action is denied — the
 * item stays blurred and a [ViewerEvent.NavigateToPin] is emitted so the host shows the
 * PIN entry screen (Req 7.2).
 *
 * Re-blur (Req 8.1, 8.2): re-blurring runs an injectable [reblurAction] that models the
 * blur rendering step. On success the item returns to Blurred State and the decrypted
 * bytes are dropped. If that step fails, the item is **kept in Clear State** and the
 * failure is reported via [ViewerUiState.errorMessage] (Req 8.2).
 *
 * Session end: the view model observes [SessionManager.sessionState] and returns the item
 * to Blurred State, dropping its decrypted bytes, whenever the session locks (Req 6.3).
 *
 * @param item         the item being viewed.
 * @param repository   session-gated source of the decrypted media bytes.
 * @param sessionManager source of truth for "is a session active" (unblur gating, Req 7.2).
 * @param reblurAction the blur rendering step invoked on re-blur; may throw to signal a
 *                     blur failure that must keep the item clear (Req 8.2). Defaults to a
 *                     no-op success.
 */
class ViewerViewModel(
    val item: MediaItem,
    private val repository: MediaRepository,
    private val sessionManager: SessionManager,
    private val reblurAction: (MediaItem) -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState(item = item))
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ViewerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ViewerEvent> = _events.asSharedFlow()

    init {
        observeSession()
    }

    /**
     * Returns the item to Blurred State whenever the session locks (background, explicit
     * lock, or timeout), dropping any decrypted bytes so no clear content survives the
     * lock (Req 6.3, 9.1, 9.2).
     */
    private fun observeSession() {
        viewModelScope.launch {
            sessionManager.sessionState.collect { state ->
                if (state is SessionState.Locked && _uiState.value.isClear) {
                    _uiState.value = _uiState.value.copy(
                        isClear = false,
                        mediaBytes = null,
                        isLoading = false,
                    )
                }
            }
        }
    }

    /**
     * Unblurs the item (Req 7.1). Denied while the session is locked: the item stays
     * blurred and a [ViewerEvent.NavigateToPin] is emitted so the host routes to the PIN
     * entry screen (Req 7.2). While unlocked, the clear bytes are decrypted (session-gated)
     * and the item moves to Clear State; a video is then playable by the screen (Req 7.3).
     */
    fun unblur() {
        if (!sessionManager.isUnlocked()) {
            // Req 7.2: deny and route to PIN entry; never produce clear content.
            denyToPin()
            return
        }
        if (_uiState.value.isClear || _uiState.value.isLoading) return

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            runCatching { repository.decryptedMedia(item.id) }
                .onSuccess { bytes ->
                    _uiState.value = _uiState.value.copy(
                        isClear = true,
                        mediaBytes = bytes,
                        isLoading = false,
                    )
                }
                .onFailure { t ->
                    if (t is IllegalStateException) {
                        // The session locked between the gate check and the decrypt:
                        // treat as a denial and route to PIN entry (Req 7.2).
                        denyToPin()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isClear = false,
                            mediaBytes = null,
                            isLoading = false,
                            errorMessage = "Could not open item: " +
                                (t.message ?: t.javaClass.simpleName),
                        )
                    }
                }
        }
    }

    /**
     * Re-blurs the item (Req 8.1). Runs [reblurAction]; on success the item returns to
     * Blurred State and the decrypted bytes are released. If the blur step fails, the item
     * is kept in Clear State and the failure is surfaced (Req 8.2).
     */
    fun reblur() {
        if (!_uiState.value.isClear) return
        try {
            reblurAction(item)
            _uiState.value = _uiState.value.copy(
                isClear = false,
                mediaBytes = null,
                errorMessage = null,
            )
        } catch (t: Throwable) {
            // Req 8.2: a failed blur must not leave the user thinking the item is hidden —
            // keep it clear and tell them it is still visible.
            _uiState.value = _uiState.value.copy(
                isClear = true,
                errorMessage = "Could not re-blur this item; it is still visible. " +
                    (t.message ?: t.javaClass.simpleName),
            )
        }
    }

    /**
     * Explicitly locks the vault (Req 9.4): ends the session and routes to PIN entry. The
     * session observer returns the item to Blurred State in response to the lock.
     */
    fun lock() {
        sessionManager.endSession()
        _events.tryEmit(ViewerEvent.NavigateToPin)
    }

    /** Clears the surfaced error message after the user has acknowledged it. */
    fun dismissError() {
        if (_uiState.value.errorMessage == null) return
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Decrypts the thumbnail bytes for [id] to be shown (under the blur) while the item is
     * in Blurred State. Session-gated through the repository; returns `null` when the
     * session is locked or the thumbnail cannot be read, so the viewer can fall back to a
     * placeholder rather than fail.
     */
    suspend fun loadBlurredThumbnail(id: String): ByteArray? =
        runCatching { repository.decryptedThumbnail(id) }.getOrNull()

    /** Denies the unblur: keep the item blurred, drop any bytes, and route to PIN entry. */
    private fun denyToPin() {
        _uiState.value = _uiState.value.copy(
            isClear = false,
            mediaBytes = null,
            isLoading = false,
        )
        _events.tryEmit(ViewerEvent.NavigateToPin)
    }

    /**
     * Constructs [ViewerViewModel] for a specific [item]. The activity-level wiring (task
     * 10.1) supplies the concrete [MediaRepository] and [SessionManager].
     */
    class Factory(
        private val item: MediaItem,
        private val repository: MediaRepository,
        private val sessionManager: SessionManager,
        private val reblurAction: (MediaItem) -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ViewerViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return ViewerViewModel(item, repository, sessionManager, reblurAction) as T
        }
    }
}
