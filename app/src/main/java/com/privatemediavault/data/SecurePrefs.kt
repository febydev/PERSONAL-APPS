package com.privatemediavault.data

import com.privatemediavault.domain.model.VaultKeyRecord

/**
 * App-private persistence for the [VaultKeyRecord] (PIN hash, salts, wrapped DEK, and
 * Argon2id parameters).
 *
 * The record is stored in application-private storage that other apps cannot read
 * (Req 5.1) and is additionally encrypted at rest by the Android Keystore master key via
 * [KeyStoreProvider], so even on-device extraction of the file yields only ciphertext.
 *
 * Implementations must treat a missing record as "no PIN set" so first-launch detection
 * (Req 1.1) works before any PIN exists.
 */
interface SecurePrefs {

    /** Returns `true` when a [VaultKeyRecord] has been persisted (i.e. a PIN is set). */
    fun hasKeyRecord(): Boolean

    /** Reads and decrypts the persisted [VaultKeyRecord], or `null` when none exists. */
    fun readKeyRecord(): VaultKeyRecord?

    /** Encrypts and persists [record], replacing any previously stored record. */
    fun writeKeyRecord(record: VaultKeyRecord)

    /** Removes any persisted record, returning the store to the "no PIN set" state. */
    fun clearKeyRecord()
}
