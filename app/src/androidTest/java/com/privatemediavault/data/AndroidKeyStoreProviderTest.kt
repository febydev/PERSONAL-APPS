package com.privatemediavault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * Instrumentation tests for [AndroidKeyStoreProvider].
 *
 * These run on a device/emulator because [AndroidKeyStoreProvider] depends on
 * the real `AndroidKeyStore` provider, which is not available in plain JVM
 * unit tests. They validate the encrypt/decrypt blob round-trip and that GCM
 * integrity protection causes decryption of a tampered blob to fail
 * (Requirement 5.2).
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeyStoreProviderTest {

    private val keyAlias = "pmv_test_master_key_${System.nanoTime()}"
    private lateinit var provider: AndroidKeyStoreProvider

    @Before
    fun setUp() {
        provider = AndroidKeyStoreProvider(keyAlias)
    }

    @After
    fun tearDown() {
        // Remove the per-test key so repeated runs start from a clean state.
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(keyAlias)
        }
    }

    @Test
    fun encryptDecrypt_roundTrips_forTypicalPayload() {
        val plaintext = "wrapped-DEK record \u00e9\u00f1\u2603".toByteArray()

        val blob = provider.encryptBlob(plaintext)
        val decrypted = provider.decryptBlob(blob)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecrypt_roundTrips_forEmptyPayload() {
        val plaintext = ByteArray(0)

        val blob = provider.encryptBlob(plaintext)
        val decrypted = provider.decryptBlob(blob)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecrypt_roundTrips_forLargeBinaryPayload() {
        val plaintext = ByteArray(4096) { (it * 31 + 7).toByte() }

        val blob = provider.encryptBlob(plaintext)
        val decrypted = provider.decryptBlob(blob)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptBlob_usesRandomizedIv_soIdenticalPlaintextsYieldDistinctBlobs() {
        val plaintext = "same input".toByteArray()

        val first = provider.encryptBlob(plaintext)
        val second = provider.encryptBlob(plaintext)

        // Randomized GCM IV means the two blobs must differ even though they
        // encrypt identical plaintext, yet both must still decrypt correctly.
        assertFalse("blobs should differ due to randomized IV", first.contentEquals(second))
        assertArrayEquals(plaintext, provider.decryptBlob(first))
        assertArrayEquals(plaintext, provider.decryptBlob(second))
    }

    @Test
    fun decryptBlob_throws_whenCiphertextByteIsTampered() {
        val plaintext = "integrity-protected payload".toByteArray()
        val blob = provider.encryptBlob(plaintext)

        // Flip a bit in the last byte, which lies in the ciphertext+GCM-tag
        // region, so the authentication tag check must fail.
        val tampered = blob.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            provider.decryptBlob(tampered)
        }
    }

    @Test
    fun decryptBlob_throws_whenAnyCiphertextByteIsFlipped() {
        val plaintext = ByteArray(64) { it.toByte() }
        val blob = provider.encryptBlob(plaintext)

        // The IV is stored as: [4-byte length][12-byte IV], so the ciphertext
        // begins at offset 16. Tampering with any ciphertext/tag byte must be
        // detected by GCM.
        val ciphertextStart = 4 + 12
        for (index in ciphertextStart until blob.size) {
            val tampered = blob.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0xFF).toByte()
            assertThrows(
                "flipping byte at index $index should fail authentication",
                GeneralSecurityException::class.java,
            ) {
                provider.decryptBlob(tampered)
            }
        }
    }

    @Test
    fun decryptBlob_throws_whenBlobIsTruncated() {
        val blob = provider.encryptBlob("payload".toByteArray())

        // Drop the trailing GCM tag bytes so authentication cannot succeed.
        val truncated = blob.copyOf(blob.size - 8)

        assertThrows(GeneralSecurityException::class.java) {
            provider.decryptBlob(truncated)
        }
    }

    @Test
    fun decryptBlob_throws_whenBlobIsTooShortToContainIvLength() {
        // Fewer than 4 bytes cannot encode the IV length header.
        assertThrows(IllegalArgumentException::class.java) {
            provider.decryptBlob(byteArrayOf(0x00, 0x01))
        }
    }

    @Test
    fun decryptBlob_throws_whenIvLengthHeaderIsInvalid() {
        // A 4-byte header declaring an absurd IV length with no payload behind
        // it must be rejected before reaching the cipher.
        val bogus = byteArrayOf(0x7F, 0x7F.toByte(), 0x7F.toByte(), 0x7F.toByte())

        assertThrows(IllegalArgumentException::class.java) {
            provider.decryptBlob(bogus)
        }
    }

    @Test
    fun decryptBlob_throws_whenIvIsTampered() {
        val plaintext = "iv tamper test".toByteArray()
        val blob = provider.encryptBlob(plaintext)

        // The IV occupies bytes [4, 16). Corrupting it yields a different IV,
        // so GCM authentication of the tag must fail.
        val tampered = blob.copyOf()
        tampered[4] = (tampered[4].toInt() xor 0xFF).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            provider.decryptBlob(tampered)
        }
    }

    @Test
    fun getOrCreateMasterKey_isStableAcrossCalls() {
        val first = provider.getOrCreateMasterKey()
        val second = provider.getOrCreateMasterKey()

        // Same alias must resolve to the same Keystore-resident key, so a blob
        // encrypted before re-fetching the key still decrypts afterwards.
        assertTrue(first.algorithm == second.algorithm)
        val blob = provider.encryptBlob("stable".toByteArray())
        assertArrayEquals("stable".toByteArray(), provider.decryptBlob(blob))
    }
}
