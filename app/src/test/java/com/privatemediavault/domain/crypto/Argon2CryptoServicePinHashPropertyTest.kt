package com.privatemediavault.domain.crypto

import com.privatemediavault.domain.model.ArgonParams
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Property-based tests for [Argon2CryptoService] PIN hashing.
 *
 * The slow, native Argon2id binding is replaced with [FakeArgon2idHasher] - a fast,
 * deterministic, salt- and password-sensitive one-way stand-in - so the real salted /
 * one-way logic in [Argon2CryptoService] (UTF-8 encoding of the PIN, threading the salt
 * through the hash, and the constant-time hash comparison in `verifyPinHash`) is what is
 * exercised here. See the design's guidance on isolating slow native crypto.
 */
class Argon2CryptoServicePinHashPropertyTest {

    private val service = Argon2CryptoService(
        params = ArgonParams(),
        hasher = FakeArgon2idHasher()
    )

    // Feature: private-media-vault, Property 3: PIN hashing is salted and one-way
    // Validates: Requirements 1.5
    // Clause 1: for any PIN and any two distinct salts, hashPin produces different hashes.
    @Property(tries = 100)
    fun `distinct salts produce distinct hashes for the same pin`(
        @ForAll("pins") pin: String,
        @ForAll("salts") saltA: ByteArray,
        @ForAll("salts") saltB: ByteArray
    ) {
        Assume.that(!saltA.contentEquals(saltB))

        val hashA = service.hashPin(pin.toCharArray(), saltA)
        val hashB = service.hashPin(pin.toCharArray(), saltB)

        assertFalse(
            "hashing the same PIN under distinct salts must yield distinct hashes",
            hashA.contentEquals(hashB)
        )
    }

    // Feature: private-media-vault, Property 3: PIN hashing is salted and one-way
    // Validates: Requirements 1.5
    // Clause 2: the stored value never equals the plaintext PIN.
    @Property(tries = 100)
    fun `stored hash never equals the plaintext pin`(
        @ForAll("pins") pin: String,
        @ForAll("salts") salt: ByteArray
    ) {
        val hash = service.hashPin(pin.toCharArray(), salt)
        val plaintextBytes = pin.toByteArray(Charsets.UTF_8)

        assertFalse(
            "the stored hash must not equal the plaintext PIN bytes",
            hash.contentEquals(plaintextBytes)
        )
    }

    // Feature: private-media-vault, Property 3: PIN hashing is salted and one-way
    // Validates: Requirements 1.5
    // Clause 3a: verifyPinHash returns true for the original PIN.
    @Property(tries = 100)
    fun `verifyPinHash accepts the original pin`(
        @ForAll("pins") pin: String,
        @ForAll("salts") salt: ByteArray
    ) {
        val hash = service.hashPin(pin.toCharArray(), salt)

        assertTrue(
            "verifyPinHash must accept the PIN that produced the hash",
            service.verifyPinHash(pin.toCharArray(), salt, hash)
        )
    }

    // Feature: private-media-vault, Property 3: PIN hashing is salted and one-way
    // Validates: Requirements 1.5
    // Clause 3b: verifyPinHash returns false for every PIN that differs from the original.
    @Property(tries = 100)
    fun `verifyPinHash rejects any differing pin`(
        @ForAll("pins") pin: String,
        @ForAll("pins") other: String,
        @ForAll("salts") salt: ByteArray
    ) {
        Assume.that(pin != other)

        val hash = service.hashPin(pin.toCharArray(), salt)

        assertFalse(
            "verifyPinHash must reject a PIN different from the one that produced the hash",
            service.verifyPinHash(other.toCharArray(), salt, hash)
        )
    }

    @Provide
    fun pins(): Arbitrary<String> =
        Arbitraries.strings().numeric().ofMinLength(4).ofMaxLength(12)

    @Provide
    fun salts(): Arbitrary<ByteArray> =
        Arbitraries.bytes().list().ofSize(SALT_LENGTH_BYTES).map { it.toByteArray() }

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

    private companion object {
        const val SALT_LENGTH_BYTES = 16
    }
}
