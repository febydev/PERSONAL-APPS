package com.privatemediavault.data

import com.privatemediavault.domain.model.LockoutState

/**
 * Persistence seam for the PIN [LockoutState] so the consecutive-failure count and any
 * active lockout window survive across `verifyPin` calls — and across process restarts —
 * preventing an attacker from resetting the counter by relaunching the app
 * (Requirements 2.4, 2.5).
 *
 * Kept deliberately small (read/write of a single value) so the lockout *policy* in
 * `AuthService.verifyPin` can be exercised with an in-memory implementation and an
 * injected time source, independent of any file system.
 */
interface LockoutStore {

    /** Returns the persisted [LockoutState], or a fresh zero-failure state when none exists. */
    fun read(): LockoutState

    /** Persists [state], replacing any previously stored value. */
    fun write(state: LockoutState)
}
