package com.privatemediavault.domain.auth

import com.privatemediavault.data.LockoutStore
import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.domain.AuthService
import com.privatemediavault.domain.CryptoService
import com.privatemediavault.domain.model.ArgonParams
import com.privatemediavault.domain.model.ChangeResult
import com.privatemediavault.domain.model.CreateResult
import com.privatemediavault.domain.model.LockoutState
import com.privatemediavault.domain.model.VaultKeyRecord
import com.privatemediavault.domain.model.VerifyResult
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * Default [AuthService] implementation.
 *
 * Tasks 4.1 and 4.4 implement [isPinSet], [createPin], and [verifyPin] (with lockout).
 * Task 4.10 implements [changePin], which re-wraps the existing DEK under a new
 * PIN-derived KEK without re-encrypting media.
 *
 * @param crypto       cryptographic primitives (PIN hashing, KEK derivation, DEK wrapping).
 * @param securePrefs  persistence for the [VaultKeyRecord] in app-private storage.
 * @param lockoutStore persistence for the [LockoutState] so the consecutive-failure count
 *   and any active lockout window survive across calls and process restarts (Req 2.4, 2.5).
 * @param now          monotonic-ish epoch-millis time source; injectable so the lockout
 *   countdown can be driven deterministically in tests.
 * @param argonParams  Argon2id cost parameters; persisted with the record so the same
 *   values are used to verify the PIN and re-derive the KEK later.
 * @param secureRandom source of salts and the random DEK; injectable for tests.
 */
class DefaultAuthService(
    private val crypto: CryptoService,
    private val securePrefs: SecurePrefs,
    private val lockoutStore: LockoutStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val argonParams: ArgonParams = ArgonParams(),
    private val secureRandom: SecureRandom = SecureRandom(),
) : AuthService {

    override fun isPinSet(): Boolean = securePrefs.hasKeyRecord()

    override fun createPin(pin: CharArray, confirm: CharArray): CreateResult {
        // Req 1.2: at least MIN_PIN_DIGITS numeric digits. Checked first so a too-short
        // PIN is rejected for length regardless of whether the confirmation matches
        // (Property 1: length validation is total).
        if (numericDigitCount(pin) < MIN_PIN_DIGITS) return CreateResult.TooShort

        // Req 1.3/1.4: the confirmation entry must match exactly, else no record is
        // written (Property 2).
        if (!pin.contentEquals(confirm)) return CreateResult.Mismatch

        val pinSalt = randomBytes(SALT_LENGTH_BYTES)
        val kekSalt = randomBytes(SALT_LENGTH_BYTES)

        // Req 1.5: store the PIN only as a salted one-way hash.
        val pinHash = crypto.hashPin(pin, pinSalt)

        // Derive the KEK from the PIN, generate a fresh random 256-bit DEK, and wrap it.
        val kek = crypto.deriveKek(pin, kekSalt)
        val dekBytes = randomBytes(DEK_LENGTH_BYTES)
        val wrappedDek = try {
            val dek = SecretKeySpec(dekBytes, "AES")
            crypto.wrapDek(dek, kek)
        } finally {
            // The random DEK exists in plaintext only transiently; zero it once wrapped.
            dekBytes.fill(0)
        }

        securePrefs.writeKeyRecord(
            VaultKeyRecord(
                pinSalt = pinSalt,
                pinHash = pinHash,
                kekSalt = kekSalt,
                wrappedDek = wrappedDek,
                argonParams = argonParams,
            )
        )
        return CreateResult.Success
    }

    override fun verifyPin(pin: CharArray): VerifyResult {
        val currentTime = now()
        var state = lockoutStore.read()

        // Req 2.4/2.5: while a lockout is active, reject entry without checking the PIN
        // and report the remaining seconds. Once the window has elapsed, clear the
        // lockout and start the user with a fresh failure count.
        val activeUntil = state.lockoutUntil
        if (activeUntil != null) {
            if (currentTime < activeUntil) {
                return VerifyResult.LockedOut(remainingSeconds(activeUntil, currentTime))
            }
            state = LockoutState()
            lockoutStore.write(state)
        }

        // Defensive: with no record there is nothing to match against (Req 2.1 ensures a
        // PIN exists before this screen is shown).
        val record = securePrefs.readKeyRecord() ?: return VerifyResult.Incorrect

        // Req 2.2/2.3: exact match against the stored salted hash.
        if (crypto.verifyPinHash(pin, record.pinSalt, record.pinHash)) {
            // A correct entry resets the failure count (Property 5).
            if (state != EMPTY_LOCKOUT) lockoutStore.write(EMPTY_LOCKOUT)
            return VerifyResult.Correct
        }

        // Req 2.4: an incorrect entry advances the consecutive-failure count; the lockout
        // begins precisely when it reaches the threshold (Property 5).
        val failures = state.consecutiveFailures + 1
        return if (failures >= MAX_CONSECUTIVE_FAILURES) {
            val lockoutUntil = currentTime + LOCKOUT_DURATION_MS
            lockoutStore.write(LockoutState(consecutiveFailures = failures, lockoutUntil = lockoutUntil))
            VerifyResult.LockedOut(remainingSeconds(lockoutUntil, currentTime))
        } else {
            lockoutStore.write(LockoutState(consecutiveFailures = failures, lockoutUntil = null))
            VerifyResult.Incorrect
        }
    }

    override fun changePin(current: CharArray, newPin: CharArray, confirm: CharArray): ChangeResult {
        // Defensive: with no record there is nothing to authenticate against; treat it as
        // a failed current-PIN check (the change UI is only reachable once a PIN exists).
        val record = securePrefs.readKeyRecord() ?: return ChangeResult.WrongCurrentPin

        // Req 12.1/12.2: the current PIN must be entered and must match the stored hash
        // before any new PIN is accepted; otherwise the change is denied.
        if (!crypto.verifyPinHash(current, record.pinSalt, record.pinHash)) {
            return ChangeResult.WrongCurrentPin
        }

        // Req 12.1: the new PIN is validated with the same rules as creation — at least
        // MIN_PIN_DIGITS numeric digits, checked before the confirmation match.
        if (numericDigitCount(newPin) < MIN_PIN_DIGITS) return ChangeResult.TooShort

        // Req 12.1: the confirmation entry must match the new PIN exactly.
        if (!newPin.contentEquals(confirm)) return ChangeResult.Mismatch

        // Req 12.3: re-wrap the existing DEK rather than re-encrypting media. Derive the
        // OLD KEK from the current PIN + stored kekSalt to unwrap the DEK, then derive a
        // NEW KEK from the new PIN + a fresh kekSalt and re-wrap the same DEK. The media
        // ciphertext is untouched because the DEK itself never changes.
        val oldKek = crypto.deriveKek(current, record.kekSalt)
        var newKek: javax.crypto.SecretKey? = null
        var dek: javax.crypto.SecretKey? = null
        try {
            dek = crypto.unwrapDek(record.wrappedDek, oldKek)

            val newPinSalt = randomBytes(SALT_LENGTH_BYTES)
            val newKekSalt = randomBytes(SALT_LENGTH_BYTES)
            newKek = crypto.deriveKek(newPin, newKekSalt)

            val newWrappedDek = crypto.wrapDek(dek, newKek)
            // Req 12.3: replace the stored hash with the new PIN's salted one-way hash.
            val newPinHash = crypto.hashPin(newPin, newPinSalt)

            securePrefs.writeKeyRecord(
                VaultKeyRecord(
                    pinSalt = newPinSalt,
                    pinHash = newPinHash,
                    kekSalt = newKekSalt,
                    wrappedDek = newWrappedDek,
                    // Keep the same Argon2id cost parameters so the new hash and KEK are
                    // produced and later re-derived under identical settings.
                    argonParams = record.argonParams,
                )
            )
            return ChangeResult.Success
        } finally {
            // Zero transient key material where practical: the DEK and both KEKs only
            // need to exist for the duration of the re-wrap.
            zeroKey(dek)
            zeroKey(newKek)
            zeroKey(oldKek)
        }
    }

    /** Counts the numeric digits (`0`-`9`) in [pin] (Req 1.2 / Property 1). */
    private fun numericDigitCount(pin: CharArray): Int = pin.count { it in '0'..'9' }

    /**
     * Reports the seconds left in an active lockout that ends at [lockoutUntil], observed
     * at [observedAt]. The value is rounded up so any remaining time shows at least one
     * second, and is bounded to `[0, LOCKOUT_SECONDS]`. Because it is a non-increasing
     * function of [observedAt] for a fixed [lockoutUntil], the reported countdown never
     * grows as time advances (Property 6).
     */
    private fun remainingSeconds(lockoutUntil: Long, observedAt: Long): Int {
        val remainingMillis = lockoutUntil - observedAt
        if (remainingMillis <= 0L) return 0
        return ceil(remainingMillis / 1000.0).toInt().coerceIn(0, LOCKOUT_SECONDS)
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { secureRandom.nextBytes(it) }

    /**
     * Best-effort wipe of transient key material. Some [SecretKey] implementations (e.g.
     * `SecretKeySpec`) do not support destruction and throw; those cases are swallowed
     * since there is nothing further we can safely do without provider-internal access.
     */
    private fun zeroKey(key: javax.crypto.SecretKey?) {
        if (key == null) return
        try {
            if (key is javax.security.auth.Destroyable && !key.isDestroyed) key.destroy()
        } catch (_: javax.security.auth.DestroyFailedException) {
            // No-op: key implementation does not support explicit destruction.
        }
    }

    private companion object {
        /** Req 1.2: a PIN must have at least 4 numeric digits. */
        const val MIN_PIN_DIGITS = 4
        /** 16-byte (128-bit) salts for Argon2id. */
        const val SALT_LENGTH_BYTES = 16
        /** 32-byte (256-bit) random data-encryption key. */
        const val DEK_LENGTH_BYTES = 32
        /** Req 2.4: a lockout begins on the 5th consecutive incorrect entry. */
        const val MAX_CONSECUTIVE_FAILURES = 5
        /** Req 2.4: lockout window length in milliseconds (30 seconds). */
        const val LOCKOUT_DURATION_MS = 30_000L
        /** Req 2.5: maximum reported lockout countdown in seconds. */
        const val LOCKOUT_SECONDS = 30
        /** Cleared lockout state used to reset the failure count after a correct entry. */
        val EMPTY_LOCKOUT = LockoutState()
    }
}
