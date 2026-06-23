package com.privatemediavault.domain.auth

import com.privatemediavault.data.LockoutStore
import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.domain.crypto.Argon2CryptoService
import com.privatemediavault.domain.crypto.Argon2idHasher
import com.privatemediavault.domain.model.ArgonParams
import com.privatemediavault.domain.model.ChangeResult
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Property-based test for [DefaultAuthService.changePin] (Property 15).
 *
 * As in the sibling verification/confirmation tests, the slow native Argon2id binding is
 * replaced with [FakeArgon2idHasher] - a fast, deterministic, salt- and password-sensitive
 * one-way stand-in - so the real [DefaultAuthService.changePin] logic and the production
 * [Argon2CryptoService.deriveKek]/[Argon2CryptoService.wrapDek]/[Argon2CryptoService.unwrapDek]
 * path are exercised. DEK wrapping uses the real AES-256-GCM cipher and media encryption
 * uses the real Tink streaming AEAD; only the Argon2id cost function is faked. Persistence
 * uses [InMemorySecurePrefs] and [InMemoryLockoutStore] so each case runs against fresh,
 * isolated state with no Android Keystore or disk. The fakes are nested here (rather than
 * shared top-level types) to avoid collisions with parallel tasks.
 */
class DefaultAuthServicePinChangePropertyTest {

    private val crypto = Argon2CryptoService(
        params = ArgonParams(),
        hasher = FakeArgon2idHasher()
    )

    // Feature: private-media-vault, Property 15: PIN change re-wraps the DEK without re-encrypting media
    // Validates: Requirements 12.1, 12.2, 12.3
    // For any distinct length-valid old/new PIN pair, after a successful changePin:
    //   (a) the DEK unwrapped via the NEW KEK equals the original DEK (re-wrap, not re-key);
    //   (b) media encrypted under the original DEK still decrypts after the change, i.e. the
    //       ciphertext was never touched / media was not re-encrypted;
    //   (c) the old PIN no longer verifies; and
    //   (d) the new PIN verifies.
    @Property(tries = 100)
    fun `changePin re-wraps the same DEK and leaves media decryptable`(
        @ForAll("pins") oldPin: String,
        @ForAll("pins") newPin: String,
        @ForAll("plaintexts") sampleMedia: ByteArray
    ) {
        // The statement quantifies over distinct old/new PINs.
        Assume.that(oldPin != newPin)

        // Fresh, isolated stores per try (jqwik reuses the test instance across tries, so
        // these must be local rather than fields to avoid leaking state between cases).
        val prefs = InMemorySecurePrefs()
        val service = DefaultAuthService(crypto, prefs, InMemoryLockoutStore())
        assertEquals(
            "createPin must succeed so there is a vault key record to change",
            CreateResult.Success,
            service.createPin(oldPin.toCharArray(), oldPin.toCharArray())
        )

        // Capture the ORIGINAL DEK by reproducing the unlock path: derive the old KEK from
        // the old PIN + stored kekSalt and unwrap the stored wrappedDek.
        val oldRecord = requireNotNull(prefs.readKeyRecord()) {
            "a key record must exist after createPin"
        }
        val originalDek = crypto.unwrapDek(
            oldRecord.wrappedDek,
            crypto.deriveKek(oldPin.toCharArray(), oldRecord.kekSalt)
        )
        val originalDekBytes = originalDek.encoded

        // Encrypt a sample of "media" under the original DEK BEFORE the change. If changePin
        // only re-wraps (and never re-encrypts media), this exact ciphertext must remain
        // valid and decrypt to the same plaintext under the post-change DEK.
        val mediaCiphertext = ByteArrayOutputStream().also { out ->
            crypto.encryptStream(ByteArrayInputStream(sampleMedia), out, originalDek, MEDIA_AAD)
        }.toByteArray()

        // Perform the PIN change.
        assertEquals(
            "changePin must succeed for a correct current PIN and a valid, confirmed new PIN",
            ChangeResult.Success,
            service.changePin(oldPin.toCharArray(), newPin.toCharArray(), newPin.toCharArray())
        )

        // (a) The DEK unwrapped via the NEW KEK equals the original DEK byte-for-byte.
        val newRecord = requireNotNull(prefs.readKeyRecord()) {
            "a key record must still exist after changePin"
        }
        val rewrappedDek = crypto.unwrapDek(
            newRecord.wrappedDek,
            crypto.deriveKek(newPin.toCharArray(), newRecord.kekSalt)
        )
        assertArrayEquals(
            "the DEK unwrapped with the new KEK must equal the original DEK (re-wrapped, not regenerated)",
            originalDekBytes,
            rewrappedDek.encoded
        )

        // (b) Media encrypted under the original DEK still decrypts after the change, proving
        // the media bytes were not re-encrypted.
        val decrypted = ByteArrayOutputStream().also { out ->
            crypto.decryptStream(ByteArrayInputStream(mediaCiphertext), out, rewrappedDek, MEDIA_AAD)
        }.toByteArray()
        assertArrayEquals(
            "media encrypted under the original DEK must decrypt unchanged after the PIN change",
            sampleMedia,
            decrypted
        )

        // (c) The old PIN no longer verifies (a single failure stays below the lockout threshold).
        assertEquals(
            "the old PIN must no longer verify after the change",
            VerifyResult.Incorrect,
            service.verifyPin(oldPin.toCharArray())
        )

        // (d) The new PIN verifies.
        assertEquals(
            "the new PIN must verify after the change",
            VerifyResult.Correct,
            service.verifyPin(newPin.toCharArray())
        )
    }

    @Provide
    fun pins(): Arbitrary<String> =
        Arbitraries.strings().numeric().ofMinLength(MIN_PIN_DIGITS).ofMaxLength(12)

    @Provide
    fun plaintexts(): Arbitrary<ByteArray> =
        Arbitraries.bytes().array(ByteArray::class.java).ofMinSize(0).ofMaxSize(256)

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
     * In-memory [SecurePrefs] fake holding at most one [VaultKeyRecord], so the change
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
     * In-memory [LockoutStore] fake starting from a fresh zero-failure [LockoutState], so a
     * single incorrect attempt stays below the lockout threshold.
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

        /** Fixed associated data bound to the sample media stream for the round-trip check. */
        val MEDIA_AAD = "media-aad".toByteArray()
    }
}
