package com.privatemediavault.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.privatemediavault.domain.AuthService
import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.AuthResult
import com.privatemediavault.domain.model.CreateResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * View state observed by `PinScreen`. The screen renders one of these modes and routes
 * away once [Unlocked] is reached.
 *
 * Mirrors the authentication flow from the requirements:
 *  - [Creating]  — first launch, no PIN set yet (Req 1.1).
 *  - [Entering]  — a PIN is set; the user is entering it (Req 2.1).
 *  - [LockedOut] — too many wrong attempts; entry is rejected and a live countdown of
 *                  remaining seconds is shown (Req 2.4, 2.5).
 *  - [Unlocked]  — authentication succeeded and a session has started (Req 2.2).
 */
sealed interface AuthUiState {

    /**
     * First-launch PIN creation. [message] carries validation feedback such as the
     * too-short or mismatch prompts (Req 1.2, 1.4).
     */
    data class Creating(val message: String? = null) : AuthUiState

    /**
     * PIN entry for an existing vault. [message] carries the incorrect-PIN message when a
     * previous attempt failed (Req 2.3).
     */
    data class Entering(val message: String? = null) : AuthUiState

    /**
     * An active lockout. [remainingSeconds] is the live, ticking remainder shown to the
     * user; while in this state PIN entry is rejected (Req 2.4, 2.5).
     */
    data class LockedOut(val remainingSeconds: Int) : AuthUiState

    /** Authentication succeeded; an authenticated session is active (Req 2.2). */
    data object Unlocked : AuthUiState
}

/**
 * Bridges the PIN screen to [AuthService] (PIN creation/validation) and [SessionManager]
 * (authentication and session start).
 *
 * Responsibilities:
 *  - Decide between creation and entry at launch based on [AuthService.isPinSet] (Req 1.1, 2.1).
 *  - Create the first PIN, surfacing too-short and mismatch feedback (Req 1.2, 1.4).
 *  - Authenticate entered PINs, surfacing the incorrect-PIN message (Req 2.3).
 *  - On lockout, run a coroutine that ticks the remaining seconds down to zero and then
 *    re-enables entry (Req 2.4, 2.5).
 *
 * PIN [CharArray]s passed in are zeroed after use so the plaintext PIN does not linger in
 * memory longer than necessary.
 *
 * @param minPinLength the minimum number of digits, used only for the user-facing
 *   too-short message; the authoritative length check lives in [AuthService.createPin].
 */
class AuthViewModel(
    private val authService: AuthService,
    private val sessionManager: SessionManager,
    private val minPinLength: Int = MIN_PIN_LENGTH,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(initialState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var lockoutJob: Job? = null

    private fun initialState(): AuthUiState =
        if (authService.isPinSet()) AuthUiState.Entering() else AuthUiState.Creating()

    /**
     * Submits a newly created PIN and its confirmation (Req 1.1–1.4). On success the PIN
     * record has been written, so we immediately start a session with the same PIN and
     * transition to [AuthUiState.Unlocked]. Both arrays are zeroed before returning.
     */
    fun submitNewPin(pin: CharArray, confirm: CharArray) {
        try {
            when (authService.createPin(pin, confirm)) {
                CreateResult.TooShort ->
                    _uiState.value = AuthUiState.Creating(
                        message = "PIN must be at least $minPinLength digits.",
                    )

                CreateResult.Mismatch ->
                    _uiState.value = AuthUiState.Creating(
                        message = "PINs did not match. Enter and confirm again.",
                    )

                CreateResult.Success -> startSession(pin)
            }
        } finally {
            pin.fill('\u0000')
            confirm.fill('\u0000')
        }
    }

    /**
     * Submits a PIN for entry into an existing vault (Req 2.1–2.5). Ignored while a
     * lockout is active. The array is zeroed before returning.
     */
    fun submitPin(pin: CharArray) {
        if (_uiState.value is AuthUiState.LockedOut) {
            pin.fill('\u0000')
            return
        }
        try {
            applyAuthResult(sessionManager.authenticate(pin), onWrong = {
                AuthUiState.Entering(message = "Incorrect PIN. Try again.")
            })
        } finally {
            pin.fill('\u0000')
        }
    }

    private fun startSession(pin: CharArray) {
        // The PIN was just created and stored, so a wrong-PIN result is not expected here;
        // surface it defensively as an entry prompt rather than silently failing.
        applyAuthResult(sessionManager.authenticate(pin), onWrong = {
            AuthUiState.Entering(message = "Could not unlock with the new PIN. Enter it again.")
        })
    }

    private fun applyAuthResult(result: AuthResult, onWrong: () -> AuthUiState) {
        when (result) {
            AuthResult.Success -> {
                lockoutJob?.cancel()
                lockoutJob = null
                _uiState.value = AuthUiState.Unlocked
            }

            AuthResult.WrongPin -> _uiState.value = onWrong()

            is AuthResult.LockedOut -> startLockoutCountdown(result.remainingSeconds)
        }
    }

    /**
     * Drives a live lockout countdown (Req 2.5): publishes [AuthUiState.LockedOut] with a
     * remainder that decrements every second, then re-enables entry by returning to
     * [AuthUiState.Entering] when it reaches zero (Req 2.4).
     */
    private fun startLockoutCountdown(seconds: Int) {
        lockoutJob?.cancel()
        if (seconds <= 0) {
            _uiState.value = AuthUiState.Entering()
            lockoutJob = null
            return
        }
        _uiState.value = AuthUiState.LockedOut(seconds)
        lockoutJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(ONE_SECOND_MILLIS)
                remaining -= 1
                _uiState.value = if (remaining > 0) {
                    AuthUiState.LockedOut(remaining)
                } else {
                    AuthUiState.Entering()
                }
            }
        }
    }

    override fun onCleared() {
        lockoutJob?.cancel()
        super.onCleared()
    }

    /**
     * Constructs [AuthViewModel] with its domain dependencies. The activity-level wiring
     * (task 10.1) provides the concrete [AuthService] and [SessionManager].
     */
    class Factory(
        private val authService: AuthService,
        private val sessionManager: SessionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return AuthViewModel(authService, sessionManager) as T
        }
    }

    private companion object {
        const val MIN_PIN_LENGTH = 4
        const val ONE_SECOND_MILLIS = 1_000L
    }
}
