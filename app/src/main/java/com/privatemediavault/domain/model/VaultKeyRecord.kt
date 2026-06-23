package com.privatemediavault.domain.model

/**
 * Persisted vault key material. Holds everything needed to (a) verify a PIN as a
 * salted one-way hash (Req 1.5) and (b) re-derive the key-encryption key (KEK) that
 * unwraps the data-encryption key (DEK).
 *
 * No plaintext key ever lives here: [pinHash] is a one-way Argon2id digest and
 * [wrappedDek] is the DEK encrypted under the PIN-derived KEK (AES-256-GCM). The whole
 * record is additionally protected at rest by the Android Keystore master key before it
 * is written to app-private storage (see `SecurePrefs`, task 4.1).
 *
 * @property pinSalt     salt for the Argon2id authentication hash.
 * @property pinHash     Argon2id one-way hash of the PIN (Req 1.5).
 * @property kekSalt     salt for the Argon2id KEK derivation; distinct from [pinSalt]
 *   so the stored hash and the KEK can never collide.
 * @property wrappedDek  the DEK encrypted by the KEK with AES-256-GCM.
 * @property argonParams Argon2id cost parameters used to produce [pinHash] and the KEK;
 *   the same values are required to verify the PIN and re-derive the KEK.
 */
data class VaultKeyRecord(
    val pinSalt: ByteArray,
    val pinHash: ByteArray,
    val kekSalt: ByteArray,
    val wrappedDek: ByteArray,
    val argonParams: ArgonParams
) {
    // ByteArray uses reference identity for equals/hashCode by default; override so two
    // records with equal contents compare equal (useful for round-trip persistence tests).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultKeyRecord) return false
        return pinSalt.contentEquals(other.pinSalt) &&
            pinHash.contentEquals(other.pinHash) &&
            kekSalt.contentEquals(other.kekSalt) &&
            wrappedDek.contentEquals(other.wrappedDek) &&
            argonParams == other.argonParams
    }

    override fun hashCode(): Int {
        var result = pinSalt.contentHashCode()
        result = 31 * result + pinHash.contentHashCode()
        result = 31 * result + kekSalt.contentHashCode()
        result = 31 * result + wrappedDek.contentHashCode()
        result = 31 * result + argonParams.hashCode()
        return result
    }
}
