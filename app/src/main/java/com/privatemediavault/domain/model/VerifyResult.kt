package com.privatemediavault.domain.model

/**
 * Outcome of verifying an entered PIN against the stored hash.
 *
 * Requirements: 2.2 (correct), 2.3 (incorrect), 2.4/2.5 (lockout countdown).
 */
sealed interface VerifyResult {
    /** The entered PIN matches the stored hash. */
    data object Correct : VerifyResult

    /** The entered PIN does not match the stored hash. */
    data object Incorrect : VerifyResult

    /** Entry is rejected during an active lockout; [remainingSeconds] remain. */
    data class LockedOut(val remainingSeconds: Int) : VerifyResult
}
