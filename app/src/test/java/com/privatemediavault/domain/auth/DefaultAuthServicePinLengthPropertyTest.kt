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
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/**
 * Property-based test for [DefaultAuthService.createPin] PIN length validation.
 *
 * The slow native Argon2id binding is replaced with [FastArgon2idHasher] - a fast,
 * deterministic stand-in - and the data-layer seams (`SecurePrefs`, `LockoutStore`)
 * are in-memory fakes, so no Android/Keystore dependency is required. The confirmation
 * entry is always equal to the candidate PIN so the [CreateResult.Mismatch] branch is
 * never taken and the length check is what is exercised.
 */
class DefaultAuthServicePinLengthPropertyTest {

    private fun newService(): DefaultAuthService = DefaultAuthService(
        crypto = Argon2CryptoService(params = ArgonParams(), hasher = FastArgon2idHasher()),
        securePrefs = InMemorySecurePrefs(),
        lockoutStore = InMemoryLockoutStore(),
        now = { 0L },
        argonParams = ArgonParams(),
        secureRandom = SecureRandom(),
    )

    // Feature: private-media-vault, Property 1: PIN length validation is total
    // Validates: Requirements 1.2
    // createPin returns TooShort exactly when the numeric digit count is below 4, and
    // never rejects a PIN of 4 or more digits for length reasons.
    @Property(tries = 100)
    fun `createPin returns TooShort iff numeric digit count is below four`(
        @ForAll("candidatePins") candidate: String
    ) {
        val service = newService()
        val digitCount = candidate.count { it in '0'..'9' }

        // confirm == pin so the Mismatch branch is never the deciding factor.
        val result = service.createPin(candidate.toCharArray(), candidate.toCharArray())

        if (digitCount < 4) {
            assertEquals(
                "a PIN with $digitCount numeric digits (< 4) must be TooShort: '$candidate'",
                CreateResult.TooShort,
                result
            )
        } else {
            assertNotEquals(
                "a PIN with $digitCount numeric digits (>= 4) must not be rejected for length: '$candidate'",
                CreateResult.TooShort,
                result
            )
        }
    }

    /**
     * Generates candidate PIN strings mixing numeric digits and non-digit characters at
     * varied lengths (0..12), so the input space spans digit counts both below and at or
     * above the 4-digit threshold. Non-digit characters (letters, punctuation, spaces)
     * ensure the property distinguishes *digit count* from raw string length.
     */
    @Provide
    fun candidatePins(): Arbitrary<String> {
        val mixedChars = Arbitraries.oneOf(
            Arbitraries.chars().range('0', '9'),
            Arbitraries.chars().range('a', 'z'),
            Arbitraries.of('#', '*', ' ', '-', '.')
        )
        return mixedChars.list().ofMinSize(0).ofMaxSize(12).map { chars ->
            chars.joinToString(separator = "")
        }
    }

    /** In-memory [SecurePrefs] that holds the record in a field; no file/Keystore use. */
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

    /** In-memory [LockoutStore] backed by a single field. */
    private class InMemoryLockoutStore : LockoutStore {
        private var state: LockoutState = LockoutState()
        override fun read(): LockoutState = state
        override fun write(state: LockoutState) {
            this.state = state
        }
    }

    /**
     * Fast, deterministic, salt- and password-sensitive one-way stand-in for the native
     * Argon2id binding (mirrors the fake used in the crypto property tests). Built from
     * SHA-256 in counter mode so the output fills [ArgonParams.hashLengthBytes].
     */
    private class FastArgon2idHasher : Argon2idHasher {
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
}
