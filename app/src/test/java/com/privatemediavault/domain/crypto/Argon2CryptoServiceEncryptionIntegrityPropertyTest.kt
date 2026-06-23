package com.privatemediavault.domain.crypto

import com.privatemediavault.domain.model.ArgonParams
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Property-based tests for [Argon2CryptoService] streaming-media encryption integrity
 * (Tink `AesGcmHkdfStreaming`, AES-256-GCM with HKDF-SHA256).
 *
 * These cover **Property 8 — Encryption provides integrity**: for any encrypted payload,
 * decryption must fail (throw) when the ciphertext is tampered with, when a different DEK
 * is used, or when the AAD differs from the one used at encryption.
 *
 * Only the streaming AEAD methods ([Argon2CryptoService.encryptStream] /
 * [Argon2CryptoService.decryptStream]) are exercised here; they are independent of the
 * native Argon2id binding, but the service is still constructed with the fast
 * [FakeArgon2idHasher] so no `.so` is loaded during the test.
 */
class Argon2CryptoServiceEncryptionIntegrityPropertyTest {

    private val service = Argon2CryptoService(
        params = ArgonParams(),
        hasher = FakeArgon2idHasher()
    )

    // Feature: private-media-vault, Property 8: Encryption provides integrity
    // Validates: Requirements 5.2
    // Case (a): flipping any byte of the ciphertext causes decryption to fail.
    @Property(tries = 100)
    fun `tampered ciphertext fails to decrypt`(
        @ForAll("payloads") payload: ByteArray,
        @ForAll("keys") dekBytes: ByteArray,
        @ForAll("aads") aad: ByteArray,
        @ForAll @IntRange(min = 0, max = Int.MAX_VALUE - 1) rawFlipIndex: Int
    ) {
        val dek = aesKey(dekBytes)
        val ciphertext = encrypt(payload, dek, aad)

        // Tink streaming output always contains a header, so it is never empty; guard
        // anyway and pick a valid in-range index for tiny outputs.
        Assume.that(ciphertext.isNotEmpty())
        val flipIndex = rawFlipIndex % ciphertext.size
        val tampered = ciphertext.copyOf()
        tampered[flipIndex] = (tampered[flipIndex].toInt() xor 0xFF).toByte()

        assertDecryptFails(
            "decrypting ciphertext with a flipped byte must throw",
            tampered, dek, aad
        )
    }

    // Feature: private-media-vault, Property 8: Encryption provides integrity
    // Validates: Requirements 5.2
    // Case (b): decrypting with a different DEK than the one used to encrypt fails.
    @Property(tries = 100)
    fun `wrong dek fails to decrypt`(
        @ForAll("payloads") payload: ByteArray,
        @ForAll("keys") dekBytes: ByteArray,
        @ForAll("keys") otherDekBytes: ByteArray,
        @ForAll("aads") aad: ByteArray
    ) {
        Assume.that(!dekBytes.contentEquals(otherDekBytes))

        val dek = aesKey(dekBytes)
        val wrongDek = aesKey(otherDekBytes)
        val ciphertext = encrypt(payload, dek, aad)

        assertDecryptFails(
            "decrypting with a different DEK must throw",
            ciphertext, wrongDek, aad
        )
    }

    // Feature: private-media-vault, Property 8: Encryption provides integrity
    // Validates: Requirements 5.2
    // Case (c): decrypting with an AAD different from the one bound at encryption fails.
    @Property(tries = 100)
    fun `mismatched aad fails to decrypt`(
        @ForAll("payloads") payload: ByteArray,
        @ForAll("keys") dekBytes: ByteArray,
        @ForAll("aads") aad: ByteArray,
        @ForAll("aads") otherAad: ByteArray
    ) {
        Assume.that(!aad.contentEquals(otherAad))

        val dek = aesKey(dekBytes)
        val ciphertext = encrypt(payload, dek, aad)

        assertDecryptFails(
            "decrypting with a different AAD must throw",
            ciphertext, dek, otherAad
        )
    }

    /** Encrypts [payload] under [dek] binding [aad], returning the full ciphertext blob. */
    private fun encrypt(payload: ByteArray, dek: SecretKey, aad: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ByteArrayInputStream(payload).use { input ->
            service.encryptStream(input, output, dek, aad)
        }
        return output.toByteArray()
    }

    /**
     * Asserts that decrypting [ciphertext] with [dek] and [aad] fails by throwing an
     * [IOException] or [GeneralSecurityException] (the design specifies an [IOException]
     * wrapping a [GeneralSecurityException]), rather than silently yielding plaintext.
     */
    private fun assertDecryptFails(message: String, ciphertext: ByteArray, dek: SecretKey, aad: ByteArray) {
        val threw = try {
            ByteArrayInputStream(ciphertext).use { input ->
                service.decryptStream(input, ByteArrayOutputStream(), dek, aad)
            }
            false
        } catch (_: IOException) {
            true
        } catch (_: GeneralSecurityException) {
            true
        }
        assertTrue(message, threw)
    }

    private fun aesKey(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")

    @Provide
    fun payloads(): Arbitrary<ByteArray> =
        Arbitraries.bytes().list().ofMinSize(0).ofMaxSize(MAX_PAYLOAD_BYTES).map { it.toByteArray() }

    @Provide
    fun aads(): Arbitrary<ByteArray> =
        Arbitraries.bytes().list().ofMinSize(0).ofMaxSize(MAX_AAD_BYTES).map { it.toByteArray() }

    /**
     * 32-byte (256-bit) key material. `AesGcmHkdfStreaming` derives a per-segment
     * AES-256 key from the DEK and requires input key material at least as long as the
     * derived key, so DEKs are fixed at [DEK_LENGTH_BYTES].
     */
    @Provide
    fun keys(): Arbitrary<ByteArray> =
        Arbitraries.bytes().list().ofSize(DEK_LENGTH_BYTES).map { it.toByteArray() }

    /**
     * Deterministic, fast, salt- and password-sensitive one-way stand-in for the native
     * Argon2id binding so the test avoids loading the native `.so`. Streaming encryption
     * does not use the hasher, but the service constructor requires one.
     */
    private class FakeArgon2idHasher : Argon2idHasher {
        override fun hash(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray {
            val out = ByteArray(params.hashLengthBytes)
            var filled = 0
            var counter = 0
            while (filled < out.size) {
                val md = java.security.MessageDigest.getInstance("SHA-256")
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

    private companion object {
        const val DEK_LENGTH_BYTES = 32
        const val MAX_PAYLOAD_BYTES = 2048
        const val MAX_AAD_BYTES = 64
    }
}
