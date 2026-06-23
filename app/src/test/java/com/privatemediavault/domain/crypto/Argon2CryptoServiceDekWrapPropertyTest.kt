package com.privatemediavault.domain.crypto

import com.privatemediavault.domain.model.ArgonParams
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows

/**
 * Property-based tests for [Argon2CryptoService] DEK wrapping ([wrapDek]/[unwrapDek]).
 *
 * Wrapping and unwrapping are pure JVM AES-256-GCM (`javax.crypto`) and do not touch the
 * Argon2id path, so the native binding is replaced with the trivial [NoopArgon2idHasher]
 * purely to satisfy the constructor; the hashing seam is irrelevant to what is exercised
 * here. The tests run as plain jqwik JVM unit tests.
 */
class Argon2CryptoServiceDekWrapPropertyTest {

    private val service = Argon2CryptoService(
        params = ArgonParams(),
        hasher = NoopArgon2idHasher()
    )

    // Feature: private-media-vault, Property 9: DEK wrapping round-trips and is key-bound
    // Validates: Requirements 5.2, 12.3
    // Clause 1: for any DEK and KEK, unwrapDek(wrapDek(dek, kek), kek) yields a key equal to dek.
    @Property(tries = 100)
    fun `unwrapping with the wrapping key recovers the original dek`(
        @ForAll("aesKeyBytes") dekBytes: ByteArray,
        @ForAll("aesKeyBytes") kekBytes: ByteArray
    ) {
        val dek = dekBytes.asAesKey()
        val kek = kekBytes.asAesKey()

        val wrapped = service.wrapDek(dek, kek)
        val unwrapped = service.unwrapDek(wrapped, kek)

        assertArrayEquals(
            "unwrapping with the wrapping KEK must recover the original DEK bytes",
            dek.encoded,
            unwrapped.encoded
        )
    }

    // Feature: private-media-vault, Property 9: DEK wrapping round-trips and is key-bound
    // Validates: Requirements 5.2, 12.3
    // Clause 2: unwrapping with any different KEK fails (GCM tag verification rejects the wrong key).
    @Property(tries = 100)
    fun `unwrapping with a different key fails`(
        @ForAll("aesKeyBytes") dekBytes: ByteArray,
        @ForAll("aesKeyBytes") kekBytes: ByteArray,
        @ForAll("aesKeyBytes") otherKekBytes: ByteArray
    ) {
        Assume.that(!kekBytes.contentEquals(otherKekBytes))

        val dek = dekBytes.asAesKey()
        val kek = kekBytes.asAesKey()
        val otherKek = otherKekBytes.asAesKey()

        val wrapped = service.wrapDek(dek, kek)

        // GCM tag verification throws AEADBadTagException (a GeneralSecurityException)
        // when the unwrapping key differs from the wrapping key.
        assertThrows(GeneralSecurityException::class.java) {
            service.unwrapDek(wrapped, otherKek)
        }
    }

    private fun ByteArray.asAesKey(): SecretKey = SecretKeySpec(this, "AES")

    /**
     * Generates raw 256-bit (32-byte) AES key material. The byte values span the full
     * range so distinct keys are easy to draw for the key-binding property.
     */
    @Provide
    fun aesKeyBytes(): Arbitrary<ByteArray> =
        Arbitraries.bytes().list().ofSize(AES_KEY_LENGTH_BYTES).map { it.toByteArray() }

    /**
     * Trivial deterministic [Argon2idHasher] used only to construct the service; the
     * wrap/unwrap code under test never invokes the hashing path.
     */
    private class NoopArgon2idHasher : Argon2idHasher {
        override fun hash(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray =
            ByteArray(params.hashLengthBytes)
    }

    private companion object {
        const val AES_KEY_LENGTH_BYTES = 32
    }
}
