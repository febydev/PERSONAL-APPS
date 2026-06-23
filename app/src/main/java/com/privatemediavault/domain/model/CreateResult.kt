package com.privatemediavault.domain.model

/**
 * Outcome of creating a new PIN on first launch.
 *
 * Requirements: 1.2 (at least 4 digits), 1.3/1.4 (confirmation must match).
 */
sealed interface CreateResult {
    /** The PIN was created and stored as a salted one-way hash. */
    data object Success : CreateResult

    /** The PIN had fewer than the required number of numeric digits. */
    data object TooShort : CreateResult

    /** The PIN and its confirmation entry did not match. */
    data object Mismatch : CreateResult
}
