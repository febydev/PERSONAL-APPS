package com.privatemediavault.domain.model

/**
 * Outcome of changing the PIN.
 *
 * Requirements: 12.1 (current PIN required), 12.2 (wrong current PIN denied),
 * 12.3 (valid current + confirmed new PIN replaces the stored hash).
 */
sealed interface ChangeResult {
    /** Current PIN verified and the stored hash was replaced with the new PIN's hash. */
    data object Success : ChangeResult

    /** The supplied current PIN did not match the stored hash. */
    data object WrongCurrentPin : ChangeResult

    /** The new PIN had fewer than the required number of numeric digits. */
    data object TooShort : ChangeResult

    /** The new PIN and its confirmation entry did not match. */
    data object Mismatch : ChangeResult

    /** Entry is rejected during an active lockout; [remainingSeconds] remain. */
    data class LockedOut(val remainingSeconds: Int) : ChangeResult
}
