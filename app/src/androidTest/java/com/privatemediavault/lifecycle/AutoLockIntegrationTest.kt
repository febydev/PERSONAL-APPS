package com.privatemediavault.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.privatemediavault.domain.session.DefaultSessionManager
import com.privatemediavault.viewmodel.MediaRenderStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Integration tests for auto-lock on background (Requirements 9.1, 9.2).
 *
 * These exercise the exact wiring that `VaultActivity` installs: a process-lifecycle
 * observer that calls [com.privatemediavault.domain.SessionManager.endSession] on
 * `ON_STOP`. The test drives a real [LifecycleRegistry] through the same observer predicate
 * the activity uses, against a **real** [DefaultSessionManager], and asserts that:
 *
 *  - moving the app to the background (`ON_STOP`) ends the session (Req 9.1), and
 *  - ending the session returns every previously-cleared media item to blurred via the
 *    production re-blur seam ([MediaRenderStateHolder.resetAllToBlurred] wired as the
 *    `onSessionEnd` hook), with no item left clear (Req 9.2).
 *
 * Lifecycle events drive the registry, so this is an Android instrumentation test
 * (`@RunWith(AndroidJUnit4::class)`) and requires a device/emulator to run. Lightweight,
 * deterministic fakes stand in for Argon2id/Keystore-backed collaborators so the test
 * stays focused on the lifecycle -> session-end -> re-blur path; [DefaultSessionManager]
 * itself is the real production code under test.
 */
@RunWith(AndroidJUnit4::class)
class AutoLockIntegrationTest {

    /**
     * Builds a session manager whose `onSessionEnd` hook re-blurs [renderStateHolder],
     * mirroring how a non-observing component is wired to the session lifecycle.
     */
    private fun newManager(renderStateHolder: MediaRenderStateHolder): DefaultSessionManager =
        DefaultSessionManager(
            authService = AlwaysCorrectAuthService(),
            crypto = FixedKeyCryptoService(),
            securePrefs = InMemorySecurePrefs(),
            now = { FIXED_NOW },
            onSessionEnd = renderStateHolder::resetAllToBlurred,
        )

    /**
     * Installs the same `ON_STOP -> endSession()` observer that `VaultActivity` registers
     * on the process lifecycle. `createUnsafe` is used so the registry can be driven from
     * the test thread without the main-thread enforcement that the real process lifecycle
     * applies; the observer logic exercised is identical to production.
     */
    private fun lifecycleDriving(manager: DefaultSessionManager): LifecycleRegistry {
        val owner = TestLifecycleOwner()
        val registry = LifecycleRegistry.createUnsafe(owner)
        owner.registry = registry
        val autoLockObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                manager.endSession()
            }
        }
        registry.addObserver(autoLockObserver)
        return registry
    }

    @Test
    fun background_endsSession_andReturnsEveryItemToBlurred() {
        val renderStateHolder = MediaRenderStateHolder().apply {
            // Three items loaded (blurred by default) with two of them cleared during the
            // active session — the state that must NOT survive a background event.
            load(listOf("a", "b", "c"))
            setClear("a", isClear = true)
            setClear("c", isClear = true)
        }
        val manager = newManager(renderStateHolder)
        val registry = lifecycleDriving(manager)

        // Foreground the app and unlock a session.
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(AuthResult.Success, manager.authenticate("1234".toCharArray()))
        assertTrue("session should be unlocked after authenticate", manager.isUnlocked())
        assertTrue(
            "precondition: some items were cleared during the session",
            renderStateHolder.renderStates().any { it.isClear },
        )

        // Move the app to the background.
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        // Req 9.1: the session must have ended.
        assertFalse("session must lock when backgrounded", manager.isUnlocked())
        assertEquals(SessionState.Locked, manager.sessionState.value)
        // Req 9.2: every item must be blurred again, regardless of prior clear state.
        assertTrue(
            "no item may remain clear after backgrounding: " +
                "${renderStateHolder.renderStates()}",
            renderStateHolder.renderStates().none { it.isClear },
        )
    }

    @Test
    fun background_whenEveryItemWasCleared_stillBlursAll() {
        val renderStateHolder = MediaRenderStateHolder().apply {
            val ids = (0 until 8).map { "item-$it" }
            load(ids)
            ids.forEach { setClear(it, isClear = true) }
        }
        val manager = newManager(renderStateHolder)
        val registry = lifecycleDriving(manager)

        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(AuthResult.Success, manager.authenticate("1234".toCharArray()))
        assertTrue(renderStateHolder.renderStates().all { it.isClear })

        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals(SessionState.Locked, manager.sessionState.value)
        assertTrue(
            "worst case (all cleared) must still end fully blurred",
            renderStateHolder.renderStates().none { it.isClear },
        )
    }

    @Test
    fun foregroundLifecycleEvents_doNotEndSession() {
        val renderStateHolder = MediaRenderStateHolder().apply {
            load(listOf("x"))
            setClear("x", isClear = true)
        }
        val manager = newManager(renderStateHolder)
        val registry = lifecycleDriving(manager)

        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(AuthResult.Success, manager.authenticate("1234".toCharArray()))

        // ON_RESUME (foreground) must NOT lock the vault: only backgrounding does.
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertTrue("foregrounding must keep the session unlocked", manager.isUnlocked())
        assertTrue(
            "a cleared item stays clear while still in the foreground",
            renderStateHolder.renderStates().single { it.itemId == "x" }.isClear,
        )
    }

    // --- Deterministic fakes (no Argon2id binding, Keystore, or disk needed) ---

    /** Minimal [LifecycleOwner] whose lifecycle is the driving [LifecycleRegistry]. */
    private class TestLifecycleOwner : LifecycleOwner {
        lateinit var registry: LifecycleRegistry
        override val lifecycle: Lifecycle get() = registry
    }

    /** Always reports the PIN as correct so a session can be unlocked deterministically. */
    private class AlwaysCorrectAuthService : AuthService {
        override fun isPinSet(): Boolean = true
        override fun createPin(pin: CharArray, confirm: CharArray): CreateResult =
            throw UnsupportedOperationException("not used by auto-lock tests")
        override fun verifyPin(pin: CharArray): VerifyResult = VerifyResult.Correct
        override fun changePin(current: CharArray, newPin: CharArray, confirm: CharArray): ChangeResult =
            throw UnsupportedOperationException("not used by auto-lock tests")
    }

    /** KEK derivation and DEK unwrap return a fixed dummy AES key; rest is unused. */
    private class FixedKeyCryptoService : CryptoService {
        private fun dummyKey(): SecretKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        override fun hashPin(pin: CharArray, salt: ByteArray): ByteArray =
            throw UnsupportedOperationException("not used by auto-lock tests")
        override fun verifyPinHash(pin: CharArray, salt: ByteArray, hash: ByteArray): Boolean =
            throw UnsupportedOperationException("not used by auto-lock tests")
        override fun deriveKek(pin: CharArray, salt: ByteArray): SecretKey = dummyKey()
        override fun wrapDek(dek: SecretKey, kek: SecretKey): ByteArray =
            throw UnsupportedOperationException("not used by auto-lock tests")
        override fun unwrapDek(wrapped: ByteArray, kek: SecretKey): SecretKey = dummyKey()
        override fun encryptStream(input: InputStream, output: OutputStream, dek: SecretKey, aad: ByteArray) =
            throw UnsupportedOperationException("not used by auto-lock tests")
        override fun decryptStream(input: InputStream, output: OutputStream, dek: SecretKey, aad: ByteArray) =
            throw UnsupportedOperationException("not used by auto-lock tests")
    }

    /** In-memory [SecurePrefs] returning a single dummy [VaultKeyRecord] to unwrap. */
    private class InMemorySecurePrefs : SecurePrefs {
        private var record: VaultKeyRecord? = VaultKeyRecord(
            pinSalt = ByteArray(16) { 1 },
            pinHash = ByteArray(32) { 2 },
            kekSalt = ByteArray(16) { 3 },
            wrappedDek = ByteArray(60) { 4 },
            argonParams = ArgonParams(),
        )
        override fun hasKeyRecord(): Boolean = record != null
        override fun readKeyRecord(): VaultKeyRecord? = record
        override fun writeKeyRecord(record: VaultKeyRecord) { this.record = record }
        override fun clearKeyRecord() { record = null }
    }

    private companion object {
        const val FIXED_NOW = 1_000L
    }
}
