package com.privatemediavault.data

import com.privatemediavault.domain.model.ArgonParams
import com.privatemediavault.domain.model.VaultKeyRecord
import java.io.File
import java.nio.ByteBuffer

/**
 * [SecurePrefs] backed by a single app-private file whose contents are encrypted at rest
 * by the Android Keystore master key through [keyStoreProvider].
 *
 * The [VaultKeyRecord] is serialized to a compact, length-prefixed binary form
 * ([VaultKeyRecordCodec]), then handed to [KeyStoreProvider.encryptBlob] before being
 * written to [recordFile]. Reading reverses the process: the file bytes are decrypted by
 * the Keystore key and then decoded. Because the master key lives in the Keystore
 * (hardware-backed where available), the file on disk is useless without the device key
 * (Req 5.1, 5.2).
 *
 * @param recordFile        the app-private file that holds the encrypted record (e.g.
 *   `context.filesDir/vault/key_record.bin`). Created on first write; its parent
 *   directory is created if needed.
 * @param keyStoreProvider  provides authenticated encryption of the serialized record at
 *   rest.
 */
class EncryptedFileSecurePrefs(
    private val recordFile: File,
    private val keyStoreProvider: KeyStoreProvider,
) : SecurePrefs {

    override fun hasKeyRecord(): Boolean = recordFile.isFile && recordFile.length() > 0

    override fun readKeyRecord(): VaultKeyRecord? {
        if (!hasKeyRecord()) return null
        val ciphertext = recordFile.readBytes()
        val plaintext = keyStoreProvider.decryptBlob(ciphertext)
        try {
            return VaultKeyRecordCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun writeKeyRecord(record: VaultKeyRecord) {
        val plaintext = VaultKeyRecordCodec.encode(record)
        try {
            val ciphertext = keyStoreProvider.encryptBlob(plaintext)
            recordFile.parentFile?.mkdirs()
            // Write to a temp file then atomically replace, so a crash mid-write cannot
            // leave a half-written (and thus undecryptable) record behind.
            val tmp = File(recordFile.parentFile, recordFile.name + ".tmp")
            tmp.writeBytes(ciphertext)
            if (!tmp.renameTo(recordFile)) {
                recordFile.writeBytes(ciphertext)
                tmp.delete()
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override fun clearKeyRecord() {
        recordFile.delete()
    }
}

/**
 * Compact, dependency-free binary (de)serialization for [VaultKeyRecord].
 *
 * Layout (all integers big-endian):
 * ```
 * | version:int |
 * | memoryKib:int | iterations:int | parallelism:int | hashLengthBytes:int |
 * | pinSalt.len:int    | pinSalt bytes    |
 * | pinHash.len:int    | pinHash bytes    |
 * | kekSalt.len:int    | kekSalt bytes    |
 * | wrappedDek.len:int | wrappedDek bytes |
 * ```
 */
internal object VaultKeyRecordCodec {

    private const val VERSION = 1

    fun encode(record: VaultKeyRecord): ByteArray {
        val params = record.argonParams
        val capacity = Int.SIZE_BYTES + // version
            Int.SIZE_BYTES * 4 + // argon params
            Int.SIZE_BYTES + record.pinSalt.size +
            Int.SIZE_BYTES + record.pinHash.size +
            Int.SIZE_BYTES + record.kekSalt.size +
            Int.SIZE_BYTES + record.wrappedDek.size
        val buffer = ByteBuffer.allocate(capacity)
            .putInt(VERSION)
            .putInt(params.memoryKib)
            .putInt(params.iterations)
            .putInt(params.parallelism)
            .putInt(params.hashLengthBytes)
        buffer.putLengthPrefixed(record.pinSalt)
        buffer.putLengthPrefixed(record.pinHash)
        buffer.putLengthPrefixed(record.kekSalt)
        buffer.putLengthPrefixed(record.wrappedDek)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): VaultKeyRecord {
        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.int
        require(version == VERSION) { "Unsupported VaultKeyRecord version: $version" }
        val argonParams = ArgonParams(
            memoryKib = buffer.int,
            iterations = buffer.int,
            parallelism = buffer.int,
            hashLengthBytes = buffer.int,
        )
        val pinSalt = buffer.getLengthPrefixed()
        val pinHash = buffer.getLengthPrefixed()
        val kekSalt = buffer.getLengthPrefixed()
        val wrappedDek = buffer.getLengthPrefixed()
        return VaultKeyRecord(
            pinSalt = pinSalt,
            pinHash = pinHash,
            kekSalt = kekSalt,
            wrappedDek = wrappedDek,
            argonParams = argonParams,
        )
    }

    private fun ByteBuffer.putLengthPrefixed(value: ByteArray): ByteBuffer {
        putInt(value.size)
        put(value)
        return this
    }

    private fun ByteBuffer.getLengthPrefixed(): ByteArray {
        val length = int
        require(length in 0..remaining()) { "Corrupt record: invalid field length $length" }
        return ByteArray(length).also { get(it) }
    }
}
