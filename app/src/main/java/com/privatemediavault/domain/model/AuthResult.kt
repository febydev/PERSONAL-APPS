package com.privatemediavault.domain.model

/**
 * Outcome of attempting to start an authenticated session via the PIN.
 *
 * Requirements: 2.2 (success grants access), 2.3 (wrong PIN denies), 2.4/2.5 (lockout).
 */
sealed interface AuthResult {
    /** PIN matched; an authenticated session was started. */
    data object Success : AuthResult

    /** PIN did not match the stored hash. */
    data object WrongPin : AuthResult

    /** Entry is currently rejected; [remainingSeconds] of lockout remain. */
    data class LockedOut(val remainingSeconds: Int) : AuthResult
}
