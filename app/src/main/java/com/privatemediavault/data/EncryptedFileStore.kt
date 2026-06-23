package com.privatemediavault.data

import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes encrypted media blobs in application-private Vault Storage
 * (`context.filesDir/vault/`). Every blob is encrypted at rest with AES-256-GCM via the
 * streaming AEAD primitive and the session data-encryption key (DEK); a per-item value is
 * bound into each stream as associated data so a blob cannot be silently swapped for
 * another item's ciphertext (Req 5.1, 5.2).
 *
 * Decryption and export are session-gated: they succeed only while an Authenticated
 * Session is active and refuse (throw) whenever the session is locked, keeping each item
 * encrypted on disk when no session is active (Req 5.3, 5.4). Encryption during
 * [importFrom] likewise requires an unlocked session because it needs the DEK.
 *
 * ### Why streams instead of `android.net.Uri`
 * The design sketches this interface in terms of `Uri`, but `Uri` is an Android-only
 * type that would force the whole store (and its tests) onto an Android runtime. To keep
 * the encryption/session logic verifiable without a device, the core contract is
 * expressed over [InputStream]/[OutputStream] supplied by source/destination lambdas. The
 * thin [AndroidUriEncryptedFileStore] adapter bridges `Uri` values to these lambdas via a
 * `ContentResolver` on-device.
 */
interface EncryptedFileStore {

    /**
     * Copies the bytes produced by [source] into Vault Storage, encrypting them under the
     * session DEK with [itemId] bound as associated data, and returns the name of the
     * encrypted blob created under the vault directory (suitable for
     * [MediaItem.encryptedFileName]).
     *
     * The [source] lambda is invoked exactly once and the returned stream is closed by
     * this method. Encryption requires the DEK, so the session must be unlocked.
     *
     * @throws IllegalStateException when the session is locked.
     */
    fun importFrom(source: () -> InputStream, itemId: String): String

    /**
     * Returns an [InputStream] over the decrypted bytes of the item identified by
     * [itemId]. Decryption happens within the Authenticated Session and the returned
     * stream holds only plaintext, never key material.
     *
     * @throws IllegalStateException when the session is locked (Req 5.3, 5.4).
     * @throws java.io.FileNotFoundException when no blob exists for [itemId].
     */
    fun openDecrypted(itemId: String): InputStream

    /**
     * Writes a decrypted copy of the item identified by [itemId] to the stream produced
     * by [destination]. The [destination] lambda is invoked exactly once and the returned
     * stream is closed by this method.
     *
     * @throws IllegalStateException when the session is locked (Req 5.4, 11.1).
     * @throws java.io.FileNotFoundException when no blob exists for [itemId].
     */
    fun exportTo(itemId: String, destination: () -> OutputStream)

    /**
     * Permanently removes the encrypted blob for [itemId] from Vault Storage. Deletion
     * does not require an active session because it touches no key material (Req 10.2).
     *
     * @return `true` if a blob existed and was removed, `false` otherwise.
     */
    fun delete(itemId: String): Boolean
}
