package com.privatemediavault.domain.session

import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.domain.AuthService
import com.privatemediavault.domain.CryptoService
import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.model.AuthResult
import com.privatemediavault.domain.model.SessionState
import com.privatemediavault.domain.model.VerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Default [SessionManager] implementation.
 *
 * Authentication is delegated to [AuthService.verifyPin] for the PIN check (including
 * lockout); on a correct PIN this manager derives the key-encryption key (KEK) from the
 * same PIN and the stored `kekSalt`, then unwraps the DEK with [CryptoService]. The DEK's
 * raw bytes are held in memory only — never persisted and never placed inside the
 * [SessionState] — and are zeroed on [endSession] (Req 5.3, 5.4, 6.3, 9.1, 9.4).
 *
 * All mutation of the session is guarded by [lock] so the activity's lifecycle callbacks
 * (which may end the session from a different thread) cannot race with [authenticate] or
 * [withDek].
 *
 * @param authService verifies the entered PIN and reports lockout (Req 2.2, 2.4, 2.5).
 * @param crypto      derives the KEK and unwraps the DEK.
 * @param securePrefs supplies the persisted `kekSalt` and `wrappedDek` (the vault key
 *   record).
 * @param now         epoch-millis time source stamped into [SessionState.Unlocked];
 *   injectable for deterministic tests.
 * @param onSessionEnd optional imperative hook invoked after the session ends and the
 *   state has flipped to [SessionState.Locked]. Compose UI normally reacts to
 *   [sessionState] directly, but non-observing components can use this to force a re-blur
 *   of any clear media (Req 6.3, 9.1).
 */
class DefaultSessionManager(
    private val authService: AuthService,
    private val crypto: CryptoService,
    private val securePrefs: SecurePrefs,
    private val now: () -> Long = System::currentTimeMillis,
    private val onSessionEnd: () -> Unit = {},
) : SessionManager {

    private val lock = Any()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Locked)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /**
     * Raw DEK bytes, present only while unlocked. Held separately from any [SecretKey] so
     * the canonical key material can be zeroed in place on [endSession]; kept out of
     * [SessionState] so observers never see it.
     */
    private var dekBytes: ByteArray? = null

    override fun authenticate(pin: CharArray): AuthResult = synchronized(lock) {
        // Req 2.2/2.3/2.4/2.5: the PIN check and lockout accounting live in AuthService.
        when (val verify = authService.verifyPin(pin)) {
            is VerifyResult.LockedOut -> return AuthResult.LockedOut(verify.remainingSeconds)
            VerifyResult.Incorrect -> return AuthResult.WrongPin
            VerifyResult.Correct -> Unit // fall through to unwrap the DEK
        }

        // A correct PIN must have a persisted record to unwrap; treat a missing record
        // defensively as a failed attempt rather than crashing.
        val record = securePrefs.readKeyRecord() ?: return AuthResult.WrongPin

        // Derive the KEK from the same PIN and the record's dedicated kekSalt, then unwrap
        // the DEK into memory (Req 5.3). The KEK is transient: its bytes are zeroed once
        // the DEK is recovered.
        val kek = crypto.deriveKek(pin, record.kekSalt)
        val dek = crypto.unwrapDek(record.wrappedDek, kek)
        kek.zeroIfPossible()

        // Replace any prior key material before storing the new DEK.
        clearDekLocked()
        dekBytes = dek.encoded // SecretKeySpec.encoded returns a fresh copy we own.
        dek.zeroIfPossible()

        _sessionState.value = SessionState.Unlocked(startedAt = now())
        return AuthResult.Success
    }

    override fun isUnlocked(): Boolean = _sessionState.value is SessionState.Unlocked

    override fun withDek(block: (SecretKey) -> Unit) = synchronized(lock) {
        val bytes = dekBytes
        check(_sessionState.value is SessionState.Unlocked && bytes != null) {
            "Cannot access the data-encryption key while the session is locked"
        }
        // Build a short-lived AES key for the block; the canonical bytes stay in dekBytes.
        val dek = SecretKeySpec(bytes, "AES")
        try {
            block(dek)
        } finally {
            dek.zeroIfPossible()
        }
    }

    override fun endSession(): Unit = synchronized(lock) {
        // Req 5.4: zero the in-memory DEK so no clear key material survives the session.
        clearDekLocked()
        // Req 6.3/9.1/9.4: flipping to Locked is the signal every observer uses to reset
        // its media render state back to blurred.
        _sessionState.value = SessionState.Locked
        onSessionEnd()
    }

    /** Zeroes and releases the in-memory DEK bytes. Caller must hold [lock]. */
    private fun clearDekLocked() {
        dekBytes?.fill(0)
        dekBytes = null
    }

    /**
     * Best-effort wipe of a [SecretKey]'s material. [SecretKeySpec.encoded] hands back a
     * copy, so zeroing that copy releases the only extra reference this manager created;
     * the underlying spec is then left to garbage collection.
     */
    private fun SecretKey.zeroIfPossible() {
        try {
            encoded?.fill(0)
        } catch (_: Exception) {
            // Some key types refuse to expose bytes; nothing more we can do here.
        }
    }
}
