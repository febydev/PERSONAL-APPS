package com.privatemediavault.domain.session

import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.domain.AuthService
import com.privatemediavault.domain.CryptoService
import com.privatemediavault.domain.model.ArgonParams
import com.privatemediavault.domain.model.AuthResult
import com.privatemediavault.domain.model.ChangeResult
import com.privatemediavault.domain.model.CreateResult
import com.privatemediavault.domain.model.SessionState
import com.privatemediavault.domain.model.VaultKeyRecord
import com.privatemediavault.domain.model.VerifyResult
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Property-based tests for Property 14 — Session end returns everything to blurred.
 *
 * The blurred/clear render state is runtime UI state, never owned by [DefaultSessionManager]
 * itself: the manager flips [SessionState] to [SessionState.Locked] on [DefaultSessionManager.endSession]
 * and fires its `onSessionEnd` re-blur hook, and the UI reacts by resetting every item's
 * render state to blurred (design: "flipping to Locked is the signal every observer uses
 * to reset its media render state back to blurred"; Req 6.3, 9.1). This test therefore
 * *models* that UI reset locally and drives it from the real manager's `onSessionEnd` hook,
 * exercising the production session-end path while standing in for the Compose observer.
 *
 * Real collaborators are replaced with fast, deterministic fakes so the test stays focused
 * on the session-end re-blur behaviour and runs without the native Argon2id binding, the
 * Android Keystore, or disk:
 *  - [FakeAuthService] always reports [VerifyResult.Correct] so a session can be unlocked.
 *  - [FakeCryptoService] returns a fixed dummy key from `deriveKek`/`unwrapDek`.
 *  - [InMemorySecurePrefs] returns a single in-memory [VaultKeyRecord].
 */
class DefaultSessionManagerReblurPropertyTest {

    // Feature: private-media-vault, Property 14: Session end returns everything to blurred
    // Validates: Requirements 6.3, 9.1, 9.2
    // For any set of items with an arbitrary subset previously cleared, after endSession()
    // every modeled render state's isClear is false; the session is Locked and the re-blur
    // signal has incremented.
    @Property(tries = 100)
    fun `endSession returns every item to blurred for any cleared subset`(
        @ForAll("clearedFlagSets") clearedFlags: List<Boolean>
    ) {
        // Model the UI-side render state for this set of items (some arbitrary subset clear).
        val renderModel = RenderStateModel(clearedFlags)

        // The re-blur "signal": incremented by the manager's onSessionEnd hook, mirroring
        // the increment the UI observes to reset all items to blurred.
        var reblurSignal = 0L

        val manager = DefaultSessionManager(
            authService = FakeAuthService(),
            crypto = FakeCryptoService(),
            securePrefs = InMemorySecurePrefs(),
            now = { FIXED_NOW },
            onSessionEnd = {
                reblurSignal += 1
                renderModel.reblurAll()
            }
        )

        // Unlock so we have an active session whose items can be cleared.
        assertEquals(
            "authenticate must succeed so there is an active session to end",
            AuthResult.Success,
            manager.authenticate("1234".toCharArray())
        )
        assertTrue("session must be unlocked after a successful authenticate", manager.isUnlocked())

        val signalBefore = reblurSignal

        // End the session — the single signal every observer uses to re-blur.
        manager.endSession()

        // Req 6.3/9.1/9.2: nothing may remain clear, regardless of which subset was clear.
        assertTrue(
            "after endSession no modeled item may remain clear (isClear=true): ${renderModel.snapshot()}",
            renderModel.allBlurred()
        )
        // The lock transition that drives the re-blur must have occurred.
        assertEquals(
            "session state must be Locked after endSession",
            SessionState.Locked,
            manager.sessionState.value
        )
        assertFalse("isUnlocked must be false after endSession", manager.isUnlocked())
        // The re-blur signal must have advanced exactly once for the one endSession call.
        assertEquals(
            "endSession must fire the re-blur signal exactly once",
            signalBefore + 1,
            reblurSignal
        )
    }

    // Feature: private-media-vault, Property 14: Session end returns everything to blurred
    // Validates: Requirements 6.3, 9.1, 9.2
    // Partial-failure facet of Req 9.2: even when every item was previously cleared, the
    // re-blur forces ALL items back to blurred unconditionally — no item is left clear.
    @Property(tries = 100)
    fun `endSession forces all items blurred even when every item was cleared`(
        @ForAll @IntRange(min = 0, max = 30) itemCount: Int
    ) {
        // Worst case for re-blur: the whole set was cleared.
        val renderModel = RenderStateModel(List(itemCount) { true })
        assertTrue(
            "precondition: every modeled item starts clear",
            renderModel.snapshot().all { it }
        )

        val manager = DefaultSessionManager(
            authService = FakeAuthService(),
            crypto = FakeCryptoService(),
            securePrefs = InMemorySecurePrefs(),
            now = { FIXED_NOW },
            onSessionEnd = { renderModel.reblurAll() }
        )

        assertEquals(
            "authenticate must succeed so there is an active session to end",
            AuthResult.Success,
            manager.authenticate("1234".toCharArray())
        )

        manager.endSession()

        assertTrue(
            "after endSession every previously-cleared item must be blurred again: ${renderModel.snapshot()}",
            renderModel.allBlurred()
        )
        assertEquals(SessionState.Locked, manager.sessionState.value)
    }

    /**
     * A list of arbitrary cleared/blurred flags (0..30 items) representing the runtime
     * render state of a set of media items. Each `true` is an item the user had cleared
     * (un-blurred) during the session; the empty list models a vault with no items.
     */
    @Provide
    fun clearedFlagSets(): Arbitrary<List<Boolean>> =
        Arbitraries.of(true, false).list().ofMinSize(0).ofMaxSize(30)

    /**
     * Local stand-in for the UI's per-item render state (the real `MediaRenderState` is
     * runtime Compose state introduced with the view models in a later task). Built from a
     * set of cleared flags; [reblurAll] is the reset the UI applies when the session ends.
     */
    private class RenderStateModel(clearedFlags: List<Boolean>) {
        private val isClear: BooleanArray = BooleanArray(clearedFlags.size) { clearedFlags[it] }

        /** Force every item back to blurred, regardless of its prior state (Req 9.2). */
        fun reblurAll() {
            for (i in isClear.indices) isClear[i] = false
        }

        /** True when no item remains clear. */
        fun allBlurred(): Boolean = isClear.none { it }

        fun snapshot(): List<Boolean> = isClear.toList()
    }

    /** Always reports the PIN as correct so a session can be unlocked deterministically. */
    private class FakeAuthService : AuthService {
        override fun isPinSet(): Boolean = true
        override fun createPin(pin: CharArray, confirm: CharArray): CreateResult =
            throw UnsupportedOperationException("not used by Property 14")
        override fun verifyPin(pin: CharArray): VerifyResult = VerifyResult.Correct
        override fun changePin(current: CharArray, newPin: CharArray, confirm: CharArray): ChangeResult =
            throw UnsupportedOperationException("not used by Property 14")
    }

    /**
     * Minimal [CryptoService] fake: KEK derivation and DEK unwrap return a fixed dummy
     * AES key so the manager can complete authentication. The streaming/hashing operations
     * are irrelevant to session-end re-blur and are left unimplemented.
     */
    private class FakeCryptoService : CryptoService {
        private fun dummyKey(): SecretKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")

        override fun hashPin(pin: CharArray, salt: ByteArray): ByteArray =
            throw UnsupportedOperationException("not used by Property 14")
        override fun verifyPinHash(pin: CharArray, salt: ByteArray, hash: ByteArray): Boolean =
            throw UnsupportedOperationException("not used by Property 14")
        override fun deriveKek(pin: CharArray, salt: ByteArray): SecretKey = dummyKey()
        override fun wrapDek(dek: SecretKey, kek: SecretKey): ByteArray =
            throw UnsupportedOperationException("not used by Property 14")
        override fun unwrapDek(wrapped: ByteArray, kek: SecretKey): SecretKey = dummyKey()
        override fun encryptStream(input: InputStream, output: OutputStream, dek: SecretKey, aad: ByteArray) =
            throw UnsupportedOperationException("not used by Property 14")
        override fun decryptStream(input: InputStream, output: OutputStream, dek: SecretKey, aad: ByteArray) =
            throw UnsupportedOperationException("not used by Property 14")
    }

    /** In-memory [SecurePrefs] returning a single dummy [VaultKeyRecord] to unwrap. */
    private class InMemorySecurePrefs : SecurePrefs {
        private var record: VaultKeyRecord? = VaultKeyRecord(
            pinSalt = ByteArray(16) { 1 },
            pinHash = ByteArray(32) { 2 },
            kekSalt = ByteArray(16) { 3 },
            wrappedDek = ByteArray(60) { 4 },
            argonParams = ArgonParams()
        )

        override fun hasKeyRecord(): Boolean = record != null
        override fun readKeyRecord(): VaultKeyRecord? = record
        override fun writeKeyRecord(record: VaultKeyRecord) {
            this.record = record
        }
        override fun clearKeyRecord() {
            record = null
        }
    }

    private companion object {
        const val FIXED_NOW = 1_000L
    }
}
