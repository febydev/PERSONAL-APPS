package com.privatemediavault.domain.model

/**
 * Tracks consecutive failed PIN attempts and any active lockout window.
 *
 * A lockout begins when [consecutiveFailures] reaches 5; [lockoutUntil] is the epoch
 * millis at which entry is permitted again (Requirements 2.4, 2.5).
 */
data class LockoutState(
    val consecutiveFailures: Int = 0,
    val lockoutUntil: Long? = null
)
