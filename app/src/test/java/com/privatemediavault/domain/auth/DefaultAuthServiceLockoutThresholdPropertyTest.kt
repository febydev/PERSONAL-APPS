package com.privatemediavault.domain.auth

import com.privatemediavault.data.LockoutStore
import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.domain.crypto.Argon2CryptoService
import com.privatemediavault.domain.crypto.Argon2idHasher
import com.privatemediavault.domain.model.ArgonParams
import com.privatemediavault.domain.model.CreateResult
import com.privatemediavault.domain.model.LockoutState
import com.privatemediavault.domain.model.VaultKeyRecord
import com.privatemediavault.domain.model.VerifyResult
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Property-based tests for [DefaultAuthService.verifyPin] lockout thresholding.
 *
 * Feature: private-media-vault, Property 5: Lockout triggers at the fifth consecutive
 * failure. Statement: for any sequence of attempts, a `LockedOut` state begins precisely
 * when consecutive incorrect attempts reach 5, and a single correct attempt before that
 * threshold resets the failure count (Req 2.4).
 *
 * The slow native Argon2id binding is replaced with [FakeArgon2idHasher] - a fast,
 * deterministic, salt- and password-sensitive one-way stand-in - so the real
 * lockout-counting logic in [DefaultAuthService] is what is exercised (hashing/verifying
 * still runs through the production [Argon2CryptoService] code). Persistence uses
 * [InMemorySecurePrefs] and [InMemoryLockoutStore], and the time source is pinned to a
 * fixed instant ([FIXED_NOW]) so once a lockout window opens it stays open for the rest
 * of the sequence - isolating *threshold* behavior from the *countdown* (Property 6).
 * The fakes are nested here (rather than shared top-level types) to avoid collisions with
 * parallel tasks.
 */
class DefaultAuthServiceLockoutThresholdPropertyTest {

    private val crypto = Argon2CryptoService(
        params = ArgonParams(),
        hasher = FakeArgon2idHasher()
    )

    // Feature: private-media-vault, Property 5: Lockout triggers at the fifth consecutive failure
    // Validates: Requirements 2.4
    // For any sequence of correct/incorrect attempts, LockedOut begins precisely on the
    // attempt where the consecutive-incorrect count first reaches 5; a correct attempt
    // before that threshold resets the count (so failures must again reach 5 to lock).
    @Property(tries = 100)
    fun `lockout begins exactly at the fifth consecutive incorrect attempt`(
        @ForAll("pins") correctPin: String,
        @ForAll("attemptSequences") attempts: List<Boolean>
    ) {
        // A clearly-different, still length-valid wrong PIN: prefixing a digit changes the
        // length (and value), so it can never equal the stored PIN.
        val wrongPin = "0$correctPin"

        val prefs = InMemorySecurePrefs()
        val lockoutStore = InMemoryLockoutStore()
        val service = DefaultAuthService(
            crypto = crypto,
            securePrefs = prefs,
            lockoutStore = lockoutStore,
            now = { FIXED_NOW }
        )

        assertEquals(
            "test setup: the known PIN must be creatable",
            CreateResult.Success,
            service.createPin(correctPin.toCharArray(), correctPin.toCharArray())
        )

        // Model the expected lockout policy. With time pinned, once a lockout opens it
        // stays open for the remainder of the sequence.
        var consecutiveFailures = 0
        var lockedOut = false

        attempts.forEachIndexed { index, isCorrect ->
            val pin = if (isCorrect) correctPin else wrongPin
            val result = service.verifyPin(pin.toCharArray())

            val expectedLockedOut: Boolean
            val expectedCorrect: Boolean
            when {
                // An active lockout is enforced before the PIN is even checked, so even a
                // correct entry is rejected while locked.
                lockedOut -> {
                    expectedLockedOut = true
                    expectedCorrect = false
                }
                // A correct entry before the threshold resets the failure count.
                isCorrect -> {
                    consecutiveFailures = 0
                    expectedLockedOut = false
                    expectedCorrect = true
                }
                // An incorrect entry advances the count; the lockout opens precisely when
                // it reaches 5.
                else -> {
                    consecutiveFailures += 1
                    if (consecutiveFailures >= LOCKOUT_THRESHOLD) {
                        lockedOut = true
                        expectedLockedOut = true
                        expectedCorrect = false
                    } else {
                        expectedLockedOut = false
                        expectedCorrect = false
                    }
                }
            }

            val where = "attempt #${index + 1} (isCorrect=$isCorrect, " +
                "modeledConsecutiveFailures=$consecutiveFailures)"
            when {
                expectedLockedOut -> assertTrue(
                    "$where: expected LockedOut but was $result",
                    result is VerifyResult.LockedOut
                )
                expectedCorrect -> assertEquals(
                    "$where: a correct PIN before the threshold must verify",
                    VerifyResult.Correct,
                    result
                )
                else -> assertEquals(
                    "$where: an incorrect PIN below the threshold must be Incorrect",
                    VerifyResult.Incorrect,
                    result
                )
            }
        }
    }

    @Provide
    fun pins(): Arbitrary<String> =
        Arbitraries.strings().numeric().ofMinLength(MIN_PIN_DIGITS).ofMaxLength(12)

    /**
     * Sequences of attempt outcomes (`true` = enter the correct PIN, `false` = enter the
     * wrong PIN). Biased two-to-one toward incorrect entries so runs frequently reach the
     * five-failure threshold, and long enough (up to 20) to interleave resets and lockouts.
     */
    @Provide
    fun attemptSequences(): Arbitrary<List<Boolean>> =
        Arbitraries.of(true, false, false).list().ofMinSize(1).ofMaxSize(20)

    /**
     * Deterministic, fast, salt- and password-sensitive one-way stand-in for the native
     * Argon2id binding. Built from SHA-256 in counter mode so the output is a pure
     * function of `(salt, password)` and fills [ArgonParams.hashLengthBytes] regardless
     * of the configured length, while remaining genuinely one-way.
     */
    private class FakeArgon2idHasher : Argon2idHasher {
        override fun hash(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray {
            val out = ByteArray(params.hashLengthBytes)
            var filled = 0
            var counter = 0
            while (filled < out.size) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(intToBytes(salt.size))
                md.update(salt)
                md.update(intToBytes(password.size))
                md.update(password)
                md.update(intToBytes(counter))
                val block = md.digest()
                val n = minOf(block.size, out.size - filled)
                System.arraycopy(block, 0, out, filled, n)
                filled += n
                counter++
            }
            return out
        }

        private fun intToBytes(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    /**
     * In-memory [SecurePrefs] fake holding at most one [VaultKeyRecord], so a known PIN
     * can be created and later verified without touching the Android Keystore or disk.
     */
    private class InMemorySecurePrefs : SecurePrefs {
        private var record: VaultKeyRecord? = null

        override fun hasKeyRecord(): Boolean = record != null

        override fun readKeyRecord(): VaultKeyRecord? = record

        override fun writeKeyRecord(record: VaultKeyRecord) {
            this.record = record
        }

        override fun clearKeyRecord() {
            record = null
        }
    }

    /**
     * In-memory [LockoutStore] fake. Holds the [LockoutState] across `verifyPin` calls so
     * the consecutive-failure count and any active lockout window persist exactly as the
     * production file-backed store would, but without the file system.
     */
    private class InMemoryLockoutStore : LockoutStore {
        private var state: LockoutState = LockoutState()

        override fun read(): LockoutState = state

        override fun write(state: LockoutState) {
            this.state = state
        }
    }

    private companion object {
        const val MIN_PIN_DIGITS = 4
        /** Req 2.4: a lockout begins on the 5th consecutive incorrect entry. */
        const val LOCKOUT_THRESHOLD = 5
        /** Pinned epoch-millis time source: keeps any opened lockout active for the run. */
        const val FIXED_NOW = 1_000_000L
    }
}
