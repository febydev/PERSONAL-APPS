package com.privatemediavault.domain.auth

import com.privatemediavault.data.LockoutStore
import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.domain.crypto.Argon2CryptoService
import com.privatemediavault.domain.crypto.Argon2idHasher
import com.privatemediavault.domain.model.ArgonParams
import com.privatemediavault.domain.model.CreateResult
import com.privatemediavault.domain.model.LockoutState
import com.privatemediavault.domain.model.VaultKeyRecord
import com.privatemediavault.domain.model.VerifyResult
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.security.MessageDigest
import org.junit.Assert.assertEquals

/**
 * Property-based tests for [DefaultAuthService.verifyPin] exactness.
 *
 * As in the sibling confirmation test, the slow native Argon2id binding is replaced with
 * [FakeArgon2idHasher] - a fast, deterministic, salt- and password-sensitive one-way
 * stand-in - so the real verification logic in [DefaultAuthService] (and the production
 * [Argon2CryptoService.hashPin]/[Argon2CryptoService.verifyPinHash] path) is what is
 * exercised. Persistence uses [InMemorySecurePrefs] and [InMemoryLockoutStore] so each
 * case runs against fresh, isolated state with no Android Keystore or disk. The fakes are
 * nested here (rather than shared top-level types) to avoid collisions with parallel tasks.
 *
 * Each case builds a *fresh* service so the consecutive-failure count starts at zero. A
 * single incorrect attempt therefore stays well below the 5-attempt lockout threshold,
 * guaranteeing the "not locked out" precondition of Property 4 and keeping lockout from
 * interfering with the Correct/Incorrect assertions.
 */
class DefaultAuthServicePinVerificationPropertyTest {

    private val crypto = Argon2CryptoService(
        params = ArgonParams(),
        hasher = FakeArgon2idHasher()
    )

    // Feature: private-media-vault, Property 4: PIN verification is exact
    // Validates: Requirements 2.2, 2.3
    // Clause 1: for any stored PIN, verifyPin returns Correct for an input equal to it.
    @Property(tries = 100)
    fun `verifyPin returns Correct for the stored PIN`(
        @ForAll("pins") pin: String
    ) {
        val service = freshService()
        assertEquals(
            "createPin must succeed so there is a stored PIN to verify against",
            CreateResult.Success,
            service.createPin(pin.toCharArray(), pin.toCharArray())
        )

        assertEquals(
            "an input equal to the stored PIN must verify as Correct",
            VerifyResult.Correct,
            service.verifyPin(pin.toCharArray())
        )
    }

    // Feature: private-media-vault, Property 4: PIN verification is exact
    // Validates: Requirements 2.2, 2.3
    // Clause 2: for any stored PIN, verifyPin returns Incorrect for an input that differs
    // (when not locked out - a fresh service keeps the single failure below the threshold).
    @Property(tries = 100)
    fun `verifyPin returns Incorrect for a differing input`(
        @ForAll("pins") pin: String,
        @ForAll("pins") other: String
    ) {
        Assume.that(pin != other)

        val service = freshService()
        assertEquals(
            "createPin must succeed so there is a stored PIN to verify against",
            CreateResult.Success,
            service.createPin(pin.toCharArray(), pin.toCharArray())
        )

        assertEquals(
            "an input that differs from the stored PIN must verify as Incorrect",
            VerifyResult.Incorrect,
            service.verifyPin(other.toCharArray())
        )
    }

    // Feature: private-media-vault, Property 4: PIN verification is exact
    // Validates: Requirements 2.2, 2.3
    // Combined iff: for the same stored PIN, verifyPin returns Correct exactly when the
    // input equals the stored PIN, and Incorrect otherwise (not locked out).
    @Property(tries = 100)
    fun `verifyPin returns Correct iff the input equals the stored PIN`(
        @ForAll("pins") pin: String,
        @ForAll shouldMatch: Boolean,
        @ForAll("pins") other: String
    ) {
        // Build an attempt that is either an exact copy (match) or a guaranteed different
        // value (mismatch), so each try exercises one side of the iff.
        val attempt = if (shouldMatch) pin else differentPin(pin, other)

        val service = freshService()
        assertEquals(
            "createPin must succeed so there is a stored PIN to verify against",
            CreateResult.Success,
            service.createPin(pin.toCharArray(), pin.toCharArray())
        )

        val expected = if (attempt == pin) VerifyResult.Correct else VerifyResult.Incorrect
        assertEquals(
            "verifyPin must return Correct exactly when the input equals the stored PIN",
            expected,
            service.verifyPin(attempt.toCharArray())
        )
    }

    /** A fresh service whose lockout failure count starts at zero (not locked out). */
    private fun freshService(): DefaultAuthService =
        DefaultAuthService(crypto, InMemorySecurePrefs(), InMemoryLockoutStore())

    /**
     * Returns a length-valid PIN guaranteed to differ from [pin]. Prefers [other] when it
     * already differs; otherwise appends a digit so the result stays numeric and >= 4
     * digits while being unequal to [pin].
     */
    private fun differentPin(pin: String, other: String): String =
        if (other != pin) other else pin + "0"

    @Provide
    fun pins(): Arbitrary<String> =
        Arbitraries.strings().numeric().ofMinLength(MIN_PIN_DIGITS).ofMaxLength(12)

    /**
     * Deterministic, fast, salt- and password-sensitive one-way stand-in for the native
     * Argon2id binding. Built from SHA-256 in counter mode so the output is a pure
     * function of `(salt, password)` and fills [ArgonParams.hashLengthBytes] regardless
     * of the configured length, while remaining genuinely one-way.
     */
    private class FakeArgon2idHasher : Argon2idHasher {
        override fun hash(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray {
            val out = ByteArray(params.hashLengthBytes)
            var filled = 0
            var counter = 0
            while (filled < out.size) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(intToBytes(salt.size))
                md.update(salt)
                md.update(intToBytes(password.size))
                md.update(password)
                md.update(intToBytes(counter))
                val block = md.digest()
                val n = minOf(block.size, out.size - filled)
                System.arraycopy(block, 0, out, filled, n)
                filled += n
                counter++
            }
            return out
        }

        private fun intToBytes(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    /**
     * In-memory [SecurePrefs] fake holding at most one [VaultKeyRecord], so verification
     * runs against a real stored record without touching the Android Keystore or disk.
     */
    private class InMemorySecurePrefs : SecurePrefs {
        private var record: VaultKeyRecord? = null

        override fun hasKeyRecord(): Boolean = record != null

        override fun readKeyRecord(): VaultKeyRecord? = record

        override fun writeKeyRecord(record: VaultKeyRecord) {
            this.record = record
        }

        override fun clearKeyRecord() {
            record = null
        }
    }

    /**
     * In-memory [LockoutStore] fake starting from a fresh zero-failure [LockoutState], so
     * a single incorrect attempt stays below the lockout threshold (Property 4's
     * "when not locked out" precondition).
     */
    private class InMemoryLockoutStore : LockoutStore {
        private var state: LockoutState = LockoutState()

        override fun read(): LockoutState = state

        override fun write(state: LockoutState) {
            this.state = state
        }
    }

    private companion object {
        const val MIN_PIN_DIGITS = 4
    }
}
