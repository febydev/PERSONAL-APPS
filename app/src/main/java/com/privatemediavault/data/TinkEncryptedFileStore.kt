package com.privatemediavault.data

import com.privatemediavault.domain.CryptoService
import com.privatemediavault.domain.SessionManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * [EncryptedFileStore] backed by the filesystem and the streaming AEAD primitive exposed
 * by [CryptoService].
 *
 * Encrypted blobs live as individual files under [vaultDir] (in production
 * `context.filesDir/vault/`, which is application-private — Req 5.1). Each blob's name is
 * derived from its item id ([encryptedFileFor]) and the same item id is bound into the
 * stream as associated data ([aadFor]), so the ciphertext for one item cannot be
 * substituted for another's without decryption failing (Req 5.2).
 *
 * The DEK is obtained exclusively through [SessionManager.withDek], which hands the key to
 * a block only while the session is unlocked and never lets it escape. Each
 * decrypt/encrypt/export operation first asserts [SessionManager.isUnlocked] so a locked
 * session yields a clear, uniform failure rather than relying on the DEK access throwing
 * deeper in the call (Req 5.3, 5.4).
 *
 * @param vaultDir the application-private directory that holds encrypted blobs; created on
 *   construction if it does not yet exist.
 * @param crypto   performs the streaming AES-256-GCM encryption/decryption.
 * @param sessionManager source of truth for "is a session active" and the in-memory DEK.
 */
class TinkEncryptedFileStore(
    private val vaultDir: File,
    private val crypto: CryptoService,
    private val sessionManager: SessionManager,
) : EncryptedFileStore {

    init {
        // Ensure the vault directory exists so the first import has somewhere to write.
        vaultDir.mkdirs()
    }

    override fun importFrom(source: () -> InputStream, itemId: String): String {
        requireUnlockedSession()
        val target = encryptedFileFor(itemId)
        // Encrypt into a temp file first, then atomically swap it into place so a crash or
        // failure mid-write cannot leave a truncated, undecryptable blob behind.
        val tmp = File(vaultDir, target.name + TEMP_SUFFIX)
        try {
            sessionManager.withDek { dek ->
                source().use { input ->
                    tmp.outputStream().use { output ->
                        crypto.encryptStream(input, output, dek, aadFor(itemId))
                    }
                }
            }
            if (target.exists() && !target.delete()) {
                throw java.io.IOException("Could not replace existing blob for item $itemId")
            }
            if (!tmp.renameTo(target)) {
                // renameTo can fail across some filesystems; fall back to copy + delete.
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return target.name
        } catch (t: Throwable) {
            // Never leave a partial temp blob around on failure.
            tmp.delete()
            throw t
        }
    }

    override fun openDecrypted(itemId: String): InputStream {
        requireUnlockedSession()
        val source = existingBlobFor(itemId)
        // Decrypt within the session-scoped DEK block. The plaintext is buffered and
        // returned as a self-contained stream so the DEK never has to outlive the block
        // (and the synchronized session lock is released promptly rather than being held
        // for as long as a caller keeps the stream open).
        val buffer = ByteArrayOutputStream()
        sessionManager.withDek { dek ->
            source.inputStream().use { input ->
                crypto.decryptStream(input, buffer, dek, aadFor(itemId))
            }
        }
        return ByteArrayInputStream(buffer.toByteArray())
    }

    override fun exportTo(itemId: String, destination: () -> OutputStream) {
        requireUnlockedSession()
        val source = existingBlobFor(itemId)
        // Stream straight from the encrypted blob to the destination; nothing is buffered
        // whole in memory, so large videos export without heap pressure.
        sessionManager.withDek { dek ->
            source.inputStream().use { input ->
                destination().use { output ->
                    crypto.decryptStream(input, output, dek, aadFor(itemId))
                }
            }
        }
    }

    override fun delete(itemId: String): Boolean = encryptedFileFor(itemId).delete()

    /**
     * Asserts an Authenticated Session is active before any operation that needs the DEK.
     * Refusing here keeps blobs encrypted on disk when locked and gives every gated
     * operation the same failure mode (Req 5.3, 5.4, 11.1).
     */
    private fun requireUnlockedSession() {
        check(sessionManager.isUnlocked()) {
            "Vault session is locked; decryption and export are refused"
        }
    }

    /** Resolves the existing encrypted blob for [itemId] or fails if none is stored. */
    private fun existingBlobFor(itemId: String): File {
        val file = encryptedFileFor(itemId)
        if (!file.isFile) {
            throw FileNotFoundException("No encrypted blob for item $itemId")
        }
        return file
    }

    /** Maps an item id to its encrypted blob under [vaultDir]. */
    private fun encryptedFileFor(itemId: String): File = File(vaultDir, itemId + ENC_SUFFIX)

    /**
     * Per-item associated data bound into the stream. Using the item id ties each
     * ciphertext to its logical item so a blob swapped for another item's ciphertext (a
     * different id, hence different AAD) fails authentication on decrypt (Req 5.2).
     */
    private fun aadFor(itemId: String): ByteArray = itemId.toByteArray(Charsets.UTF_8)

    private companion object {
        /** Suffix for encrypted media blobs under the vault directory. */
        const val ENC_SUFFIX = ".enc"
        /** Suffix for the transient file used while writing a new blob. */
        const val TEMP_SUFFIX = ".tmp"
    }
}
