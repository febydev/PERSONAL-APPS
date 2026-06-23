package com.privatemediavault.domain.crypto

import com.privatemediavault.domain.model.ArgonParams
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals

/**
 * Property-based test for [Argon2CryptoService] streaming media encryption.
 *
 * The streaming methods ([Argon2CryptoService.encryptStream] /
 * [Argon2CryptoService.decryptStream]) are backed by Tink's subtle `AesGcmHkdfStreaming`
 * primitive, which is a pure-JVM AEAD that needs no Tink registration/initialisation, so
 * this runs as a plain unit test. The service is still constructed with a fast, trivial
 * [Argon2idHasher] stand-in so the slow native argon2kt `.so` is never loaded - the
 * hasher plays no part in stream encryption.
 */
class Argon2CryptoServiceEncryptionRoundTripPropertyTest {

    private val service = Argon2CryptoService(
        params = ArgonParams(),
        hasher = NoopArgon2idHasher()
    )

    // Feature: private-media-vault, Property 7: Media encryption round-trips
    // Validates: Requirements 5.2, 5.3
    // For any byte payload and any valid DEK and AAD,
    // decryptStream(encryptStream(payload)) == payload.
    @Property(tries = 100)
    fun `encrypt then decrypt yields the original payload`(
        @ForAll("payloads") payload: ByteArray,
        @ForAll("deks") dekBytes: ByteArray,
        @ForAll("aads") aad: ByteArray
    ) {
        val dek = SecretKeySpec(dekBytes, "AES")

        val ciphertext = ByteArrayOutputStream()
        service.encryptStream(ByteArrayInputStream(payload), ciphertext, dek, aad)

        val decrypted = ByteArrayOutputStream()
        service.decryptStream(ByteArrayInputStream(ciphertext.toByteArray()), decrypted, dek, aad)

        assertArrayEquals(
            "decryptStream(encryptStream(payload)) must equal the original payload",
            payload,
            decrypted.toByteArray()
        )
    }

    /**
     * Varied-size byte payloads. Sizes deliberately span the empty payload, small
     * sub-buffer payloads, a few hundred KB, and payloads larger than the 1 MiB
     * ciphertext segment so multiple Tink streaming segments are exercised. Each array is
     * filled deterministically from a generated seed rather than element-by-element, which
     * keeps generation and shrinking cheap even for the large sizes.
     */
    @Provide
    fun payloads(): Arbitrary<ByteArray> {
        val sizes = Arbitraries.oneOf(
            Arbitraries.just(0),
            Arbitraries.integers().between(1, 8 * 1024),
            Arbitraries.integers().between(200 * 1024, 400 * 1024),
            Arbitraries.integers().between(1_100_000, 1_300_000)
        )
        return Combinators.combine(sizes, Arbitraries.longs()).`as` { size, seed ->
            ByteArray(size).also { Random(seed).nextBytes(it) }
        }
    }

    /** Random 256-bit (32-byte) AES DEKs. */
    @Provide
    fun deks(): Arbitrary<ByteArray> =
        Arbitraries.longs().map { seed ->
            ByteArray(AES_KEY_SIZE_BYTES).also { Random(seed).nextBytes(it) }
        }

    /** Random associated data, including the empty AAD. */
    @Provide
    fun aads(): Arbitrary<ByteArray> =
        Combinators.combine(
            Arbitraries.integers().between(0, 64),
            Arbitraries.longs()
        ).`as` { size, seed ->
            ByteArray(size).also { Random(seed).nextBytes(it) }
        }

    /**
     * Trivial, fast [Argon2idHasher] stand-in. It is required only to satisfy the
     * [Argon2CryptoService] constructor without loading the native argon2kt binding; the
     * streaming encryption path under test never invokes it.
     */
    private class NoopArgon2idHasher : Argon2idHasher {
        override fun hash(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray =
            ByteArray(params.hashLengthBytes)
    }

    private companion object {
        const val AES_KEY_SIZE_BYTES = 32
    }
}
