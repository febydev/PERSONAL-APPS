package com.privatemediavault.viewmodel

import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.domain.AuthService
import com.privatemediavault.domain.CryptoService
import com.privatemediavault.domain.model.ChangeResult
import com.privatemediavault.domain.model.CreateResult
import com.privatemediavault.domain.model.SessionState
import com.privatemediavault.domain.model.VaultKeyRecord
import com.privatemediavault.domain.model.VerifyResult
import com.privatemediavault.domain.session.DefaultSessionManager
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Property-based test for [MediaRenderStateHolder] re-blur on session end.
 *
 * Feature: private-media-vault, Property 14: Session end returns everything to blurred.
 * Statement: for any set of items with an arbitrary subset previously cleared, after
 * `endSession()` every [MediaRenderState.isClear] is `false` (Req 6.3, 9.1, 9.2).
 *
 * The test drives the re-blur through the real [DefaultSessionManager]: the holder's
 * total re-blur ([MediaRenderStateHolder.resetAllToBlurred]) is wired as the manager's
 * `onSessionEnd` hook, and the post-condition is asserted after calling `endSession()`.
 * `endSession()` zeroes the DEK and flips the session to [SessionState.Locked] without
 * touching authentication or cryptography, so those collaborators are supplied as
 * construction-only fakes that throw if ever invoked — proving the re-blur path is
 * independent of the auth/crypto seams. The generated input also models the partial-failure
 * scenario of Req 9.2 (an interrupted protective pass that re-blurred only some items),
 * confirming the session-end signal still drives everything to blurred.
 */
class MediaRenderStateSessionEndPropertyTest {

    // Feature: private-media-vault, Property 14: Session end returns everything to blurred
    // Validates: Requirements 6.3, 9.1, 9.2
    // For any item set with an arbitrary cleared subset, and even after a partial protective
    // re-blur that leaves some items clear (Req 9.2), ending the session must leave no item
    // in Clear State.
    @Property(tries = 100)
    fun `ending the session returns every item to blurred even after a partial re-blur`(
        @ForAll("clearedFlagsByItem") clearedByItem: Map<String, Boolean>,
        @ForAll partialSeed: Long,
    ) {
        val holder = MediaRenderStateHolder()
        holder.load(clearedByItem.keys.toList())

        // (1) Model the user having un-blurred an arbitrary subset during the session (Req 7.1).
        clearedByItem.forEach { (id, clear) -> if (clear) holder.setClear(id, true) }

        // (2) Model a protective action that partially fails: it re-blurs only some items
        //     before being interrupted, leaving an arbitrary subset still in Clear State
        //     (the partial-failure scenario of Req 9.2).
        val rng = Random(partialSeed)
        clearedByItem.keys.forEach { id -> if (rng.nextBoolean()) holder.setClear(id, false) }

        // (3) End the session through the real SessionManager. Flipping to Locked is the
        //     session-end signal; the wired onSessionEnd hook must complete the remaining
        //     protective action regardless of the partial state (Req 6.3, 9.1, 9.2).
        val session = DefaultSessionManager(
            authService = UnusedAuthService,
            crypto = UnusedCryptoService,
            securePrefs = UnusedSecurePrefs,
            now = { FIXED_NOW },
            onSessionEnd = holder::resetAllToBlurred,
        )

        session.endSession()

        // Post-condition: no item remains clear, and the session is locked.
        val stillClear = holder.renderStates().filter { it.isClear }
        assertTrue(
            "after endSession() no item may remain in Clear State, but these did: " +
                stillClear.map { it.itemId },
            stillClear.isEmpty(),
        )
        assertEquals(
            "endSession() must leave the session locked",
            SessionState.Locked,
            session.sessionState.value,
        )
    }

    /**
     * A set of media items (unique ids) each tagged with whether it was un-blurred during
     * the session. A map keeps ids unique and lets the cleared subset be arbitrary, including
     * the empty set (vacuously satisfying the property) and the all-clear set.
     */
    @Provide
    fun clearedFlagsByItem(): Arbitrary<Map<String, Boolean>> {
        val itemIds: Arbitrary<String> =
            Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12)
        val clearedFlags: Arbitrary<Boolean> = Arbitraries.of(true, false)
        return Arbitraries.maps(itemIds, clearedFlags).ofMinSize(0).ofMaxSize(30)
    }

    /**
     * Construction-only [AuthService] fake. `endSession()` never authenticates, so any call
     * here is a regression in the session-end path.
     */
    private object UnusedAuthService : AuthService {
        override fun isPinSet(): Boolean = unused()
        override fun createPin(pin: CharArray, confirm: CharArray): CreateResult = unused()
        override fun verifyPin(pin: CharArray): VerifyResult = unused()
        override fun changePin(
            current: CharArray,
            newPin: CharArray,
            confirm: CharArray,
        ): ChangeResult = unused()

        private fun unused(): Nothing =
            error("AuthService must not be invoked while ending a session")
    }

    /**
     * Construction-only [CryptoService] fake. `endSession()` performs no cryptography, so
     * any call here is a regression in the session-end path.
     */
    private object UnusedCryptoService : CryptoService {
        override fun hashPin(pin: CharArray, salt: ByteArray): ByteArray = unused()
        override fun verifyPinHash(pin: CharArray, salt: ByteArray, hash: ByteArray): Boolean =
            unused()

        override fun deriveKek(pin: CharArray, salt: ByteArray): SecretKey = unused()
        override fun wrapDek(dek: SecretKey, kek: SecretKey): ByteArray = unused()
        override fun unwrapDek(wrapped: ByteArray, kek: SecretKey): SecretKey = unused()
        override fun encryptStream(
            input: InputStream,
            output: OutputStream,
            dek: SecretKey,
            aad: ByteArray,
        ) = unused()

        override fun decryptStream(
            input: InputStream,
            output: OutputStream,
            dek: SecretKey,
            aad: ByteArray,
        ) = unused()

        private fun unused(): Nothing =
            error("CryptoService must not be invoked while ending a session")
    }

    /**
     * Construction-only [SecurePrefs] fake. `endSession()` reads no persisted record, so any
     * call here is a regression in the session-end path.
     */
    private object UnusedSecurePrefs : SecurePrefs {
        override fun hasKeyRecord(): Boolean = unused()
        override fun readKeyRecord(): VaultKeyRecord? = unused()
        override fun writeKeyRecord(record: VaultKeyRecord) = unused()
        override fun clearKeyRecord() = unused()

        private fun unused(): Nothing =
            error("SecurePrefs must not be invoked while ending a session")
    }

    private companion object {
        /** Pinned epoch-millis time source; the value is irrelevant to a session that never unlocks. */
        const val FIXED_NOW = 1_000_000L
    }
}
