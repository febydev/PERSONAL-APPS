package com.privatemediavault.domain.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.privatemediavault.domain.CryptoService
import com.privatemediavault.domain.model.ArgonParams
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [CryptoService] implementation backed by Argon2id (via the argon2kt native binding)
 * for PIN hashing and key derivation.
 *
 * This task (2.1) implements [hashPin], [verifyPinHash] and [deriveKek]; task 2.3 adds
 * the AES-256-GCM DEK wrapping ([wrapDek]/[unwrapDek]); task 2.5 adds the streaming media
 * encryption methods ([encryptStream]/[decryptStream]) backed by Tink streaming AEAD.
 *
 * @param params Argon2id cost parameters. The same parameters must be supplied when
 *   verifying a hash or re-deriving a KEK, so they are persisted with the vault key
 *   record in task 4.1.
 * @param hasher the Argon2id hashing primitive. Defaults to [Argon2KtHasher], which is
 *   backed by the slow, native argon2kt binding. It is abstracted behind the
 *   [Argon2idHasher] seam so the native dependency can be isolated in tests (the design
 *   calls for isolating slow native crypto); the real salted, one-way logic still lives
 *   in this service.
 */
class Argon2CryptoService(
    private val params: ArgonParams = ArgonParams(),
    private val hasher: Argon2idHasher = Argon2KtHasher()
) : CryptoService {

    private val secureRandom = SecureRandom()

    override fun hashPin(pin: CharArray, salt: ByteArray): ByteArray {
        val passwordBytes = pin.toUtf8Bytes()
        try {
            return rawArgon2idHash(passwordBytes, salt)
        } finally {
            passwordBytes.fill(0)
        }
    }

    override fun verifyPinHash(pin: CharArray, salt: ByteArray, hash: ByteArray): Boolean {
        val candidate = hashPin(pin, salt)
        try {
            // MessageDigest.isEqual is constant-time, avoiding timing side channels.
            return MessageDigest.isEqual(candidate, hash)
        } finally {
            candidate.fill(0)
        }
    }

    override fun deriveKek(pin: CharArray, salt: ByteArray): SecretKey {
        val passwordBytes = pin.toUtf8Bytes()
        var keyBytes: ByteArray? = null
        try {
            keyBytes = rawArgon2idHash(passwordBytes, salt)
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            passwordBytes.fill(0)
            // SecretKeySpec copies the bytes, so the local copy can be zeroed.
            keyBytes?.fill(0)
        }
    }

    /**
     * Wraps [dek] under [kek] with AES-256-GCM.
     *
     * A fresh random 12-byte IV is generated for every call and prepended to the GCM
     * output (ciphertext + 128-bit authentication tag). The resulting layout is
     * `[ 12-byte IV | ciphertext | 16-byte tag ]`. Because the IV is random per call,
     * wrapping the same DEK under the same KEK twice yields distinct blobs, while the
     * GCM tag binds the ciphertext to [kek] so [unwrapDek] can detect a wrong key
     * (Req 5.2, 12.3).
     */
    override fun wrapDek(dek: SecretKey, kek: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val dekBytes = dek.encoded
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, kek.asAesKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
            val ciphertext = cipher.doFinal(dekBytes)
            return iv + ciphertext
        } finally {
            // The DEK's encoded bytes are a copy; zero them once wrapped.
            dekBytes.fill(0)
        }
    }

    /**
     * Unwraps a DEK previously produced by [wrapDek] using [kek].
     *
     * The leading [GCM_IV_LENGTH_BYTES] bytes are read back as the IV and the remainder
     * is decrypted and tag-verified. GCM tag verification fails (throwing a
     * [javax.crypto.AEADBadTagException]) whenever [kek] differs from the wrapping key
     * or the blob has been tampered with, making the operation key-bound (Req 5.2, 12.3).
     *
     * @throws IllegalArgumentException if [wrapped] is too short to contain an IV and tag.
     */
    override fun unwrapDek(wrapped: ByteArray, kek: SecretKey): SecretKey {
        require(wrapped.size > GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
            "wrapped DEK is too short to contain an IV, ciphertext, and tag"
        }
        val iv = wrapped.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = wrapped.copyOfRange(GCM_IV_LENGTH_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, kek.asAesKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        val dekBytes = cipher.doFinal(ciphertext)
        try {
            return SecretKeySpec(dekBytes, "AES")
        } finally {
            // SecretKeySpec copies the bytes, so the local copy can be zeroed.
            dekBytes.fill(0)
        }
    }

    /**
     * Encrypts [input] to [output] with [dek] using Tink streaming AEAD
     * (`AesGcmHkdfStreaming`, AES-256-GCM with HKDF-SHA256), binding [aad] to the
     * ciphertext.
     *
     * The plaintext is consumed in fixed-size segments rather than buffered whole, so a
     * large video is never fully resident in memory. Each segment is independently
     * authenticated, and [aad] is bound to the stream as associated data, so decryption
     * fails if the ciphertext is tampered with, a different DEK is used, or a different
     * AAD is supplied (Req 5.2, 5.3).
     *
     * The Tink encrypting wrapper is closed when this method returns, which flushes and
     * authenticates the final segment; closing the wrapper also closes [output]. [input]
     * is read to completion but left for the caller to close.
     */
    override fun encryptStream(input: InputStream, output: OutputStream, dek: SecretKey, aad: ByteArray) {
        val streamingAead = newStreamingAead(dek)
        streamingAead.newEncryptingStream(output, aad).use { encryptingStream ->
            input.copyTo(encryptingStream, STREAM_BUFFER_SIZE_BYTES)
        }
    }

    /**
     * Decrypts [input] (produced by [encryptStream]) to [output] with [dek], requiring
     * the same [aad] used at encryption time.
     *
     * Ciphertext is read and verified segment by segment, so the cleartext is never fully
     * buffered in memory. A tampered ciphertext, a wrong DEK, or a mismatched [aad] causes
     * the underlying read to fail (throwing an [java.io.IOException] wrapping a
     * [java.security.GeneralSecurityException]) rather than yielding incorrect plaintext
     * (Req 5.2, 5.3).
     *
     * The Tink decrypting wrapper is closed when this method returns, which also closes
     * [input]. [output] is flushed but left for the caller to close.
     */
    override fun decryptStream(input: InputStream, output: OutputStream, dek: SecretKey, aad: ByteArray) {
        val streamingAead = newStreamingAead(dek)
        streamingAead.newDecryptingStream(input, aad).use { decryptingStream ->
            decryptingStream.copyTo(output, STREAM_BUFFER_SIZE_BYTES)
        }
        output.flush()
    }

    /**
     * Builds an `AesGcmHkdfStreaming` primitive keyed by [dek].
     *
     * The DEK's raw bytes are used as HKDF input key material; a fresh per-segment AES key
     * is derived for AES-256-GCM ([AES_KEY_SIZE_BYTES] = 32). The encoded key copy is zeroed
     * once the primitive has captured it.
     */
    private fun newStreamingAead(dek: SecretKey): AesGcmHkdfStreaming {
        val keyBytes = dek.encoded
        try {
            return AesGcmHkdfStreaming(
                keyBytes,
                HKDF_ALGORITHM,
                AES_KEY_SIZE_BYTES,
                CIPHERTEXT_SEGMENT_SIZE_BYTES,
                FIRST_SEGMENT_OFFSET
            )
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * Runs Argon2id over [password] with [salt] and the configured [params], returning
     * the raw (un-encoded) hash bytes. Delegates to the injected [hasher] so the native
     * binding can be isolated in tests.
     */
    private fun rawArgon2idHash(password: ByteArray, salt: ByteArray): ByteArray =
        hasher.hash(password, salt, params)

    /**
     * Returns [this] as a key usable by an AES cipher. When the key already reports the
     * `AES` algorithm it is used directly; otherwise its encoded bytes are re-wrapped as
     * an AES [SecretKeySpec] so a KEK supplied with a different algorithm tag still works.
     */
    private fun SecretKey.asAesKey(): SecretKey =
        if (algorithm == "AES") this else SecretKeySpec(encoded, "AES")

    private companion object {
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8

        // Streaming AEAD (AesGcmHkdfStreaming) parameters.
        /** HKDF pseudo-random function used to derive per-segment keys from the DEK. */
        const val HKDF_ALGORITHM = "HmacSha256"
        /** 32 bytes -> AES-256-GCM for each segment. */
        const val AES_KEY_SIZE_BYTES = 32
        /** 1 MiB ciphertext segments keep large media off the heap while streaming. */
        const val CIPHERTEXT_SEGMENT_SIZE_BYTES = 1 shl 20
        /** No leading offset before the first ciphertext segment. */
        const val FIRST_SEGMENT_OFFSET = 0
        /** Copy buffer size for moving bytes between the plain and cipher streams. */
        const val STREAM_BUFFER_SIZE_BYTES = 8 * 1024
    }
}

/**
 * Encodes a [CharArray] to UTF-8 bytes without materialising an intermediate
 * [String], so the sensitive value is not interned or left on the JVM heap. The
 * temporary backing buffer is zeroed before returning.
 */
private fun CharArray.toUtf8Bytes(): ByteArray {
    val charBuffer = CharBuffer.wrap(this)
    val byteBuffer = Charsets.UTF_8.encode(charBuffer)
    val bytes = ByteArray(byteBuffer.remaining())
    byteBuffer.get(bytes)
    if (byteBuffer.hasArray()) {
        Arrays.fill(byteBuffer.array(), 0.toByte())
    }
    return bytes
}

/**
 * Seam over the Argon2id hashing primitive used by [Argon2CryptoService].
 *
 * Abstracting the raw hash lets the slow, native-backed binding be swapped for a fast,
 * deterministic stand-in in unit tests while the salted, one-way logic under test stays
 * in [Argon2CryptoService]. Production uses [Argon2KtHasher].
 */
fun interface Argon2idHasher {
    /**
     * Computes the raw Argon2id hash of [password] salted with [salt] under [params].
     * The result must be deterministic for a given `(password, salt, params)` and have a
     * length of [ArgonParams.hashLengthBytes].
     */
    fun hash(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray
}

/**
 * Production [Argon2idHasher] backed by the native argon2kt binding. The binding loads
 * its `.so` on construction, so this is only instantiated when the real
 * [Argon2CryptoService] is used on-device.
 *
 * @param argon2Kt the native Argon2 binding; injectable for testing.
 */
class Argon2KtHasher(
    private val argon2Kt: Argon2Kt = Argon2Kt()
) : Argon2idHasher {
    override fun hash(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray =
        argon2Kt.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = salt,
            tCostInIterations = params.iterations,
            mCostInKibibyte = params.memoryKib,
            parallelism = params.parallelism,
            hashLengthInBytes = params.hashLengthBytes
        ).rawHashAsByteArray()
}
