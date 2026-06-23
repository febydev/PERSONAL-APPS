package com.privatemediavault.domain

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey

/**
 * Pure cryptographic operations for the vault. No Android UI dependencies, so the
 * service is testable in isolation.
 *
 * Responsibilities span three independent concerns, implemented across several tasks:
 *  - PIN hashing and key derivation with Argon2id (task 2.1).
 *  - Data-encryption-key (DEK) wrapping with AES-256-GCM (task 2.3).
 *  - Streaming media encryption with Tink streaming AEAD (task 2.5).
 */
interface CryptoService {

    /**
     * Computes a one-way Argon2id hash of [pin] salted with [salt].
     *
     * The returned bytes never contain the plaintext PIN and differ for distinct
     * salts, satisfying the salted one-way storage requirement (Req 1.5).
     */
    fun hashPin(pin: CharArray, salt: ByteArray): ByteArray

    /**
     * Returns `true` when [pin] hashed with [salt] equals [hash], using a constant-time
     * comparison to avoid leaking match progress through timing.
     */
    fun verifyPinHash(pin: CharArray, salt: ByteArray, hash: ByteArray): Boolean

    /**
     * Derives a 256-bit AES key-encryption key from [pin] and [salt] via Argon2id.
     *
     * Uses a salt distinct from the PIN-hash salt so the stored hash and the KEK can
     * never be the same value (Req 2.2).
     */
    fun deriveKek(pin: CharArray, salt: ByteArray): SecretKey

    /** Wraps [dek] under [kek] with AES-256-GCM (task 2.3). */
    fun wrapDek(dek: SecretKey, kek: SecretKey): ByteArray

    /** Unwraps a DEK previously produced by [wrapDek] using [kek] (task 2.3). */
    fun unwrapDek(wrapped: ByteArray, kek: SecretKey): SecretKey

    /** Encrypts [input] to [output] with [dek] and [aad] using streaming AEAD (task 2.5). */
    fun encryptStream(input: InputStream, output: OutputStream, dek: SecretKey, aad: ByteArray)

    /** Decrypts [input] to [output] with [dek] and [aad] using streaming AEAD (task 2.5). */
    fun decryptStream(input: InputStream, output: OutputStream, dek: SecretKey, aad: ByteArray)
}
