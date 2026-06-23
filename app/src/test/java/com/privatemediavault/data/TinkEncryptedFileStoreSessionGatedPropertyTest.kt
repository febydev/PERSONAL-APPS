package com.privatemediavault.data

import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.crypto.Argon2CryptoService
import com.privatemediavault.domain.crypto.Argon2idHasher
import com.privatemediavault.domain.model.ArgonParams
import com.privatemediavault.domain.model.AuthResult
import com.privatemediavault.domain.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Random
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Property-based test for Property 10 — Decryption is session-gated.
 *
 * Exercises the real [TinkEncryptedFileStore] over the real streaming AEAD path
 * ([Argon2CryptoService.encryptStream] / [decryptStream], backed by Tink's pure-JVM
 * `AesGcmHkdfStreaming` primitive — no Android runtime, no native argon2kt binding, no
 * Keystore). The vault directory is a throwaway temp directory so encrypt/decrypt
 * round-trips run entirely on the JVM.
 *
 * The session is driven through a controllable [ToggleSessionManager] whose
 * `isUnlocked()` / `withDek` can be flipped locked/unlocked at will. The DEK is a fixed
 * 32-byte [SecretKeySpec]; the store never sees key material except through `withDek`,
 * exactly as it would in production via the real `DefaultSessionManager`.
 *
 * Scope: the file-store gated operations [TinkEncryptedFileStore.openDecrypted] and
 * [TinkEncryptedFileStore.exportTo]. `decryptedThumbnail` named in Property 10 lives on
 * `MediaRepository` (task 6.4) — its session gating is covered where `decryptedThumbnail`
 * is defined (task 6.4 / its property test). This test fully covers the file-store half
 * of Property 10 (Req 5.3, 5.4, 11.2; 7.2 is the UI-deny facet built on this gate).
 */
class TinkEncryptedFileStoreSessionGatedPropertyTest {

    // Feature: private-media-vault, Property 10: Decryption is session-gated
    // Validates: Requirements 5.3, 5.4, 7.2, 11.2
    // For any payload imported while unlocked: while unlocked openDecrypted/exportTo
    // succeed and return the original bytes; while locked both refuse (throw) and produce
    // no plaintext (the export destination is never written), and the blob stays on disk.
    @Property(tries = 100)
    fun `openDecrypted and exportTo are gated on an unlocked session`(
        @ForAll("itemIds") itemId: String,
        @ForAll("payloads") payload: ByteArray
    ) {
        val vaultDir = Files.createTempDirectory("pmv-vault").toFile()
        try {
            val session = ToggleSessionManager(unlocked = true)
            val store = TinkEncryptedFileStore(
                vaultDir = vaultDir,
                crypto = crypto,
                sessionManager = session
            )

            // Import while unlocked so an encrypted blob exists for this item.
            val blobName = store.importFrom(source = { payload.inputStream() }, itemId = itemId)
            val blob = File(vaultDir, blobName)
            assertTrue("import while unlocked must produce an encrypted blob on disk", blob.isFile)

            // --- While UNLOCKED: both gated ops succeed and return the original bytes ---
            val decryptedWhileUnlocked = store.openDecrypted(itemId).use { it.readBytes() }
            assertArrayEquals(
                "openDecrypted while unlocked must return the original bytes",
                payload,
                decryptedWhileUnlocked
            )

            val exportedWhileUnlocked = ByteArrayOutputStream()
            store.exportTo(itemId, destination = { exportedWhileUnlocked })
            assertArrayEquals(
                "exportTo while unlocked must write the original bytes",
                payload,
                exportedWhileUnlocked.toByteArray()
            )

            // --- While LOCKED: both gated ops refuse and produce no plaintext ---
            session.lock()
            assertFalse("precondition: session must report locked", session.isUnlocked())

            // openDecrypted must throw and yield no plaintext stream.
            var openDecryptedRefused = false
            try {
                store.openDecrypted(itemId).use { it.readBytes() }
            } catch (expected: IllegalStateException) {
                openDecryptedRefused = true
            }
            assertTrue("openDecrypted while locked must throw IllegalStateException", openDecryptedRefused)

            // exportTo must throw, and the destination must never be opened or written:
            // no plaintext may be produced when locked.
            val lockedExportTarget = ByteArrayOutputStream()
            var destinationOpened = false
            var exportRefused = false
            try {
                store.exportTo(itemId, destination = {
                    destinationOpened = true
                    lockedExportTarget
                })
            } catch (expected: IllegalStateException) {
                exportRefused = true
            }
            assertTrue("exportTo while locked must throw IllegalStateException", exportRefused)
            assertFalse(
                "exportTo while locked must not open the destination (no plaintext produced)",
                destinationOpened
            )
            assertEquals(
                "exportTo while locked must leave the destination unwritten (empty)",
                0,
                lockedExportTarget.size()
            )

            // The encrypted blob remains untouched on disk while the session is locked.
            assertTrue("the encrypted blob must remain on disk when the session is locked", blob.isFile)
        } finally {
            vaultDir.deleteRecursively()
        }
    }

    /**
     * Item ids that are valid single-segment file names (alphanumeric, non-empty). The
     * store maps an id directly to `"<id>.enc"` under the vault dir, so path separators or
     * empty ids are out of the meaningful input space.
     */
    @Provide
    fun itemIds(): Arbitrary<String> =
        Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(24)

    /**
     * Varied-size byte payloads: the empty payload, small sub-buffer payloads, a few
     * hundred KB, and payloads larger than the 1 MiB streaming segment so multiple Tink
     * segments are exercised. Filled deterministically from a seed to keep generation and
     * shrinking cheap.
     */
    @Provide
    fun payloads(): Arbitrary<ByteArray> {
        val sizes = Arbitraries.oneOf(
            Arbitraries.just(0),
            Arbitraries.integers().between(1, 8 * 1024),
            Arbitraries.integers().between(200 * 1024, 400 * 1024),
            Arbitraries.integers().between(1_100_000, 1_300_000)
        )
        return Combinators.combine(sizes, Arbitraries.longs()).`as` { size, seed ->
            ByteArray(size).also { Random(seed).nextBytes(it) }
        }
    }

    /**
     * Controllable [SessionManager]: `isUnlocked()` reflects [unlocked] and `withDek`
     * hands a fixed 32-byte DEK to its block only while unlocked, throwing
     * [IllegalStateException] when locked — exactly the contract [TinkEncryptedFileStore]
     * relies on. PIN/auth lifecycle methods are irrelevant to the gate and are stubbed.
     */
    private class ToggleSessionManager(unlocked: Boolean) : SessionManager {
        private val dek: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        private val state = MutableStateFlow<SessionState>(
            if (unlocked) SessionState.Unlocked(0L) else SessionState.Locked
        )

        override val sessionState: StateFlow<SessionState> = state.asStateFlow()

        fun lock() {
            state.value = SessionState.Locked
        }

        override fun authenticate(pin: CharArray): AuthResult =
            throw UnsupportedOperationException("not used by Property 10")

        override fun isUnlocked(): Boolean = state.value is SessionState.Unlocked

        override fun withDek(block: (SecretKey) -> Unit) {
            check(isUnlocked()) { "session locked" }
            block(dek)
        }

        override fun endSession() {
            state.value = SessionState.Locked
        }
    }

    /**
     * Trivial, fast [Argon2idHasher] stand-in required only to construct
     * [Argon2CryptoService] without loading the native argon2kt binding; the streaming
     * encrypt/decrypt path under test never invokes it.
     */
    private class NoopArgon2idHasher : Argon2idHasher {
        override fun hash(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray =
            ByteArray(params.hashLengthBytes)
    }

    private val crypto = Argon2CryptoService(
        params = ArgonParams(),
        hasher = NoopArgon2idHasher()
    )
}
