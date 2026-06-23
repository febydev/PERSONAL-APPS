package com.privatemediavault.domain.model

/**
 * Whether an authenticated session is currently active.
 *
 * The decrypted data-encryption key lives in memory only while [Unlocked]; on
 * background, explicit lock, or timeout the session returns to [Locked]
 * (Requirements 5.3, 5.4, 9.1, 9.4).
 */
sealed interface SessionState {
    /** No authenticated session; media stays encrypted and blurred. */
    data object Locked : SessionState

    /** Authenticated session active since [startedAt] (epoch millis). */
    data class Unlocked(val startedAt: Long) : SessionState
}
