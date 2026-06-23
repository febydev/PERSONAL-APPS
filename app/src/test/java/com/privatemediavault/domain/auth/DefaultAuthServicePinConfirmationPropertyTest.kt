package com.privatemediavault.domain.auth

import com.privatemediavault.data.LockoutStore
import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.domain.crypto.Argon2CryptoService
import com.privatemediavault.domain.crypto.Argon2idHasher
import com.privatemediavault.domain.model.ArgonParams
import com.privatemediavault.domain.model.CreateResult
import com.privatemediavault.domain.model.LockoutState
import com.privatemediavault.domain.model.VaultKeyRecord
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Property-based tests for [DefaultAuthService.createPin] confirmation handling.
 *
 * The slow, native Argon2id binding is replaced with [FakeArgon2idHasher] - a fast,
 * deterministic, salt- and password-sensitive one-way stand-in - so the real
 * confirmation/record-writing logic in [DefaultAuthService] is what is exercised (the
 * actual hashing/wrapping still runs through the production [Argon2CryptoService] code).
 * Persistence goes to [InMemorySecurePrefs], an in-memory [SecurePrefs] fake, so the
 * test can assert whether a record was written. Both fakes are nested here (rather than
 * shared top-level types) to avoid collisions with parallel tasks.
 */
class DefaultAuthServicePinConfirmationPropertyTest {

    private val crypto = Argon2CryptoService(
        params = ArgonParams(),
        hasher = FakeArgon2idHasher()
    )

    // Feature: private-media-vault, Property 2: PIN creation requires matching confirmation
    // Validates: Requirements 1.3, 1.4
    // Clause 1: for length-valid inputs, createPin returns Success when pin == confirm,
    // and a key record is written.
    @Property(tries = 100)
    fun `matching confirmation succeeds and writes a record`(
        @ForAll("pins") pin: String
    ) {
        val prefs = InMemorySecurePrefs()
        val service = DefaultAuthService(crypto, prefs, InMemoryLockoutStore())

        val result = service.createPin(pin.toCharArray(), pin.toCharArray())

        assertEquals(
            "a length-valid PIN that matches its confirmation must be created",
            CreateResult.Success,
            result
        )
        assertTrue(
            "a successful createPin must write a key record",
            prefs.hasKeyRecord()
        )
    }

    // Feature: private-media-vault, Property 2: PIN creation requires matching confirmation
    // Validates: Requirements 1.3, 1.4
    // Clause 2: for length-valid inputs that differ, createPin returns Mismatch and no
    // PIN record is written.
    @Property(tries = 100)
    fun `mismatched confirmation returns Mismatch and writes no record`(
        @ForAll("pins") pin: String,
        @ForAll("pins") confirm: String
    ) {
        Assume.that(pin != confirm)

        val prefs = InMemorySecurePrefs()
        val service = DefaultAuthService(crypto, prefs, InMemoryLockoutStore())

        val result = service.createPin(pin.toCharArray(), confirm.toCharArray())

        assertEquals(
            "two length-valid PINs that differ must be rejected as a mismatch",
            CreateResult.Mismatch,
            result
        )
        assertFalse(
            "a mismatched confirmation must not write any key record",
            prefs.hasKeyRecord()
        )
    }

    // Feature: private-media-vault, Property 2: PIN creation requires matching confirmation
    // Validates: Requirements 1.3, 1.4
    // Combined iff: across a mix of equal and unequal length-valid pairs, createPin
    // returns Success exactly when pin == confirm (else Mismatch), and a record exists
    // afterward exactly when it succeeded.
    @Property(tries = 100)
    fun `createPin succeeds iff pin equals confirm`(
        @ForAll("pins") pin: String,
        @ForAll shouldMatch: Boolean,
        @ForAll("pins") other: String
    ) {
        // Build a confirmation that is either an exact copy (match) or a guaranteed
        // different value (mismatch), so each try exercises one side of the iff.
        val confirm = if (shouldMatch) pin else differentPin(pin, other)

        val prefs = InMemorySecurePrefs()
        val service = DefaultAuthService(crypto, prefs, InMemoryLockoutStore())

        val result = service.createPin(pin.toCharArray(), confirm.toCharArray())

        val matched = pin == confirm
        if (matched) {
            assertEquals(
                "equal length-valid pin/confirm must succeed",
                CreateResult.Success,
                result
            )
        } else {
            assertEquals(
                "unequal length-valid pin/confirm must be a mismatch",
                CreateResult.Mismatch,
                result
            )
        }
        assertEquals(
            "a key record must exist after createPin exactly when it succeeded",
            matched,
            prefs.hasKeyRecord()
        )
    }

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
     * In-memory [SecurePrefs] fake holding at most one [VaultKeyRecord]. Lets the test
     * observe whether [DefaultAuthService.createPin] wrote a record without touching the
     * Android Keystore or disk.
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
     * In-memory [LockoutStore] fake. [DefaultAuthService.createPin] never reads or writes
     * the lockout state, so this exists only to satisfy the constructor; it starts from a
     * fresh zero-failure [LockoutState].
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
