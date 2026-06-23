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
 * Property-based test for [DefaultAuthService.verifyPin] lockout countdown reporting.
 *
 * The slow native Argon2id binding is replaced with [FastArgon2idHasher] - a fast,
 * deterministic, salt- and password-sensitive one-way stand-in - and the data-layer
 * seams (`SecurePrefs`, `LockoutStore`) are in-memory fakes, so no Android/Keystore
 * dependency is required. Time is supplied through a mutable clock captured by the
 * injectable `now` source, letting the test observe an active lockout at a sequence of
 * increasing timestamps.
 *
 * To observe an active lockout: a PIN is created, then 5 consecutive incorrect attempts
 * are driven at a fixed start time so the 30-second lockout window begins; subsequent
 * `verifyPin` calls during that window return [VerifyResult.LockedOut] with the remaining
 * seconds. Because an active lockout short-circuits before the PIN is checked or the
 * failure count is touched, these read-only observations do not disturb the window.
 */
class DefaultAuthServiceLockoutCountdownPropertyTest {

    // Feature: private-media-vault, Property 6: Lockout countdown is bounded and monotonic
    // Validates: Requirements 2.4, 2.5
    // For any active lockout observed at increasing timestamps, the reported remaining
    // seconds is always within [0, 30] and never increases as time advances.
    @Property(tries = 100)
    fun `reported lockout countdown is bounded to 0_30 and non-increasing over time`(
        @ForAll("baseTimes") baseTime: Long,
        @ForAll("observationOffsets") offsets: List<Long>
    ) {
        var clock = baseTime
        val service = DefaultAuthService(
            crypto = Argon2CryptoService(params = ArgonParams(), hasher = FastArgon2idHasher()),
            securePrefs = InMemorySecurePrefs(),
            lockoutStore = InMemoryLockoutStore(),
            now = { clock },
            argonParams = ArgonParams(),
        )

        // Establish a known PIN, then drive exactly MAX_CONSECUTIVE_FAILURES incorrect
        // attempts at the fixed start time so the lockout window begins at baseTime.
        assertEquals(
            CreateResult.Success,
            service.createPin(CORRECT_PIN.toCharArray(), CORRECT_PIN.toCharArray())
        )

        clock = baseTime
        lateinit var lockoutStart: VerifyResult
        repeat(MAX_CONSECUTIVE_FAILURES) {
            lockoutStart = service.verifyPin(WRONG_PIN.toCharArray())
        }
        assertTrue(
            "the ${MAX_CONSECUTIVE_FAILURES}th consecutive failure must begin a lockout",
            lockoutStart is VerifyResult.LockedOut
        )

        // Observe the active lockout at increasing timestamps within the window. Offsets
        // are sorted ascending; each stays strictly below the 30s window so the lockout
        // remains active and verifyPin reports a countdown rather than re-checking the PIN.
        var previousRemaining = Int.MAX_VALUE
        for (offset in offsets) {
            clock = baseTime + offset
            val result = service.verifyPin(WRONG_PIN.toCharArray())

            assertTrue(
                "an observation at offset $offset ms (within the 30s window) must still be LockedOut, was $result",
                result is VerifyResult.LockedOut
            )
            val remaining = (result as VerifyResult.LockedOut).remainingSeconds

            // Req 2.5: the reported countdown is bounded to [0, 30].
            assertTrue(
                "remaining seconds $remaining must be within [0, 30] (offset $offset ms)",
                remaining in 0..30
            )
            // Property 6: as time advances the countdown never increases.
            assertTrue(
                "remaining seconds must be non-increasing: $remaining followed $previousRemaining (offset $offset ms)",
                remaining <= previousRemaining
            )
            previousRemaining = remaining
        }
    }

    /**
     * Generates base (lockout-start) epoch-millis values spanning zero and large
     * positive instants, so the countdown logic is exercised independent of any absolute
     * time origin.
     */
    @Provide
    fun baseTimes(): Arbitrary<Long> =
        Arbitraries.longs().between(0L, 10_000_000_000L)

    /**
     * Generates a non-empty, ascending sequence of observation offsets (milliseconds
     * after the lockout start), each strictly within the 30-second window
     * `[0, LOCKOUT_DURATION_MS)`, so every observation falls during an active lockout.
     * Sorting ascending models "observed at increasing timestamps".
     */
    @Provide
    fun observationOffsets(): Arbitrary<List<Long>> =
        Arbitraries.longs().between(0L, LOCKOUT_DURATION_MS - 1)
            .list().ofMinSize(1).ofMaxSize(20)
            .map { it.sorted() }

    /** In-memory [SecurePrefs] that holds the record in a field; no file/Keystore use. */
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

    /** In-memory [LockoutStore] backed by a single field. */
    private class InMemoryLockoutStore : LockoutStore {
        private var state: LockoutState = LockoutState()
        override fun read(): LockoutState = state
        override fun write(state: LockoutState) {
            this.state = state
        }
    }

    /**
     * Fast, deterministic, salt- and password-sensitive one-way stand-in for the native
     * Argon2id binding (mirrors the fake used in the sibling auth property tests). Built
     * from SHA-256 in counter mode so the output fills [ArgonParams.hashLengthBytes] and
     * the wrong PIN never collides with the correct one.
     */
    private class FastArgon2idHasher : Argon2idHasher {
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

    private companion object {
        /** A valid stored PIN (>= 4 numeric digits). */
        const val CORRECT_PIN = "1234"
        /** A length-valid PIN that differs from [CORRECT_PIN], so every attempt fails. */
        const val WRONG_PIN = "9999"
        /** Req 2.4: a lockout begins on the 5th consecutive incorrect entry. */
        const val MAX_CONSECUTIVE_FAILURES = 5
        /** Req 2.4: lockout window length in milliseconds (30 seconds). */
        const val LOCKOUT_DURATION_MS = 30_000L
    }
}
