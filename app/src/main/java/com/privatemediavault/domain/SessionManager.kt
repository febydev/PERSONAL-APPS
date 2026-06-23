package com.privatemediavault.domain

import com.privatemediavault.domain.model.AuthResult
import com.privatemediavault.domain.model.SessionState
import kotlinx.coroutines.flow.StateFlow
import javax.crypto.SecretKey

/**
 * Owns session state and the in-memory data-encryption key (DEK). The single source of
 * truth for "is a session active".
 *
 * The decrypted DEK lives in memory only while a session is [SessionState.Unlocked]; it
 * is never persisted and is never stored inside the [SessionState] object itself. On
 * background, explicit lock, or timeout the session returns to [SessionState.Locked],
 * the DEK bytes are zeroed, and every media item's render state must reset to blurred
 * (Requirements 5.3, 5.4, 6.3, 9.1, 9.4).
 *
 * Observers (view models / the activity) watch [sessionState] to react to lock/unlock
 * transitions — in particular, a transition to [SessionState.Locked] is the signal that
 * all clear (un-blurred) media must return to blurred.
 */
interface SessionManager {

    /**
     * The current session state as an observable stream. Emits [SessionState.Unlocked]
     * with the session start time while authenticated and [SessionState.Locked]
     * otherwise. The DEK is intentionally absent from these values so observers can
     * react to lock/unlock without ever touching key material.
     */
    val sessionState: StateFlow<SessionState>

    /**
     * Verifies [pin] and, on success, derives the key-encryption key, unwraps the DEK
     * into memory, and transitions to [SessionState.Unlocked] (Req 2.2, 5.3).
     *
     * @return [AuthResult.Success] when the PIN is correct and the DEK is unwrapped,
     *   [AuthResult.WrongPin] when the PIN does not match, or
     *   [AuthResult.LockedOut] with the remaining seconds during an active lockout.
     */
    fun authenticate(pin: CharArray): AuthResult

    /** Returns `true` while a session is active and the DEK is held in memory. */
    fun isUnlocked(): Boolean

    /**
     * Runs [block] with the in-memory DEK while the session is unlocked. The key is
     * provided only for the duration of the call and is never returned or stored outside
     * the session (Req 5.3, 5.4).
     *
     * @throws IllegalStateException when the session is locked.
     */
    fun withDek(block: (SecretKey) -> Unit)

    /**
     * Ends the session: zeroes the in-memory DEK and transitions to
     * [SessionState.Locked]. The state transition signals observers that all media render
     * state must reset to blurred (Req 5.4, 6.3, 9.1, 9.4).
     */
    fun endSession()
}
