package com.privatemediavault.data

import javax.crypto.SecretKey

/**
 * Wraps the Android Keystore. Generates and retrieves the device-bound master
 * key that encrypts the wrapped-DEK blob at rest, and provides authenticated
 * encryption of small blobs (the [com.privatemediavault.domain.model] key
 * record) with that key.
 *
 * The master key is AES-256-GCM and is hardware-backed on devices that support
 * it. The key material itself never leaves the Keystore: [encryptBlob] and
 * [decryptBlob] operate through Keystore-managed [javax.crypto.Cipher]
 * instances, so plaintext key bytes are never exposed to the app process
 * (Requirements 5.1, 5.2).
 */
interface KeyStoreProvider {

    /**
     * Returns the Keystore-resident master key, creating it on first use.
     *
     * The key is generated as AES-256-GCM inside the `AndroidKeyStore`
     * provider, which places it in secure hardware (TEE/StrongBox) where the
     * device supports it. Subsequent calls return the existing key.
     */
    fun getOrCreateMasterKey(): SecretKey

    /**
     * Encrypts [plaintext] with the master key using AES-256-GCM and returns a
     * self-describing blob containing the randomly generated IV followed by the
     * ciphertext and authentication tag. Suitable for protecting the
     * wrapped-DEK record at rest.
     */
    fun encryptBlob(plaintext: ByteArray): ByteArray

    /**
     * Decrypts a blob previously produced by [encryptBlob], verifying its GCM
     * authentication tag. Throws if the blob is malformed, truncated, or has
     * been tampered with.
     */
    fun decryptBlob(ciphertext: ByteArray): ByteArray
}
