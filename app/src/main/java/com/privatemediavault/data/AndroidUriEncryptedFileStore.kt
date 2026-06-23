package com.privatemediavault.data

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/**
 * Thin Android adapter that bridges `android.net.Uri` values (as the design's
 * `EncryptedFileStore` signature describes) to the stream-based [EncryptedFileStore] core.
 *
 * The adapter resolves a `Uri` to an [java.io.InputStream]/[java.io.OutputStream] through a
 * [ContentResolver] and delegates all encryption, decryption, and session-gating to
 * [delegate]. Keeping this glue separate lets the cryptographic logic in
 * [TinkEncryptedFileStore] stay free of Android dependencies and unit-testable off-device.
 *
 * @param contentResolver opens streams for the import source and export destination URIs.
 * @param delegate        the stream-based store that performs the real work.
 */
class AndroidUriEncryptedFileStore(
    private val contentResolver: ContentResolver,
    private val delegate: EncryptedFileStore,
) {

    /**
     * Imports the content at [uri] into Vault Storage under [itemId], returning the name
     * of the encrypted blob. Mirrors [EncryptedFileStore.importFrom] (Req 4.1, 5.2).
     */
    fun importFrom(uri: Uri, itemId: String): String =
        delegate.importFrom({ openInput(uri) }, itemId)

    /** Session-gated decrypted stream for [itemId]. See [EncryptedFileStore.openDecrypted]. */
    fun openDecrypted(itemId: String): InputStream = delegate.openDecrypted(itemId)

    /**
     * Exports a decrypted copy of [itemId] to the User-selected [destUri]. Session-gated;
     * mirrors [EncryptedFileStore.exportTo] (Req 11.1, 5.4).
     */
    fun exportTo(itemId: String, destUri: Uri) =
        delegate.exportTo(itemId) {
            contentResolver.openOutputStream(destUri)
                ?: throw IOException("Could not open destination for export: $destUri")
        }

    /** Permanently removes the encrypted blob for [itemId] (Req 10.2). */
    fun delete(itemId: String): Boolean = delegate.delete(itemId)

    private fun openInput(uri: Uri): InputStream =
        contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open import source: $uri")
}
