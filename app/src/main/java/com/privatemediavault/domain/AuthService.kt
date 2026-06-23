package com.privatemediavault.domain

import com.privatemediavault.domain.model.ChangeResult
import com.privatemediavault.domain.model.CreateResult
import com.privatemediavault.domain.model.VerifyResult

/**
 * Handles PIN creation, verification, lockout counting, and PIN change. Delegates the
 * cryptographic work (hashing, KEK derivation, DEK wrapping) to [CryptoService] and the
 * persistence of the vault key record to the data layer's `SecurePrefs`.
 *
 * Implemented incrementally across tasks:
 *  - [isPinSet] / [createPin] — task 4.1.
 *  - [verifyPin] (with lockout) — task 4.4.
 *  - [changePin] — task 4.10.
 */
interface AuthService {

    /**
     * Returns `true` when a PIN has already been created and a vault key record is
     * persisted. Used at launch to decide between the PIN-creation and PIN-entry screens
     * (Req 1.1, 2.1).
     */
    fun isPinSet(): Boolean

    /**
     * Creates the first PIN. Validates that [pin] has at least the required number of
     * numeric digits (Req 1.2) and that [confirm] matches [pin] (Req 1.3, 1.4). On
     * success, generates a random data-encryption key, derives the PIN key-encryption
     * key, wraps the DEK, and persists the salted one-way PIN hash and wrapped DEK as a
     * vault key record (Req 1.5).
     *
     * @return [CreateResult.TooShort] when too few digits, [CreateResult.Mismatch] when
     *   the entries differ, or [CreateResult.Success] when the record is written.
     */
    fun createPin(pin: CharArray, confirm: CharArray): CreateResult

    /**
     * Verifies an entered PIN against the stored hash, applying lockout after repeated
     * failures (Req 2.2, 2.3, 2.4, 2.5). Implemented in task 4.4.
     */
    fun verifyPin(pin: CharArray): VerifyResult

    /**
     * Changes the PIN: verifies the current PIN, validates the new PIN and confirmation,
     * and re-wraps the DEK under the new KEK without re-encrypting media
     * (Req 12.1, 12.2, 12.3). Implemented in task 4.10.
     */
    fun changePin(current: CharArray, newPin: CharArray, confirm: CharArray): ChangeResult
}
