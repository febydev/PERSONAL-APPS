package com.privatemediavault.data

import com.privatemediavault.domain.model.LockoutState
import java.io.File
import java.nio.ByteBuffer

/**
 * [LockoutStore] backed by a single app-private file (per the design data model, under
 * `context.filesDir/lockout`).
 *
 * The lockout record is not secret — it holds only a failure count and an expiry
 * timestamp — so it is stored as a compact, fixed-size binary blob rather than encrypted.
 * Writes are atomic (temp file + rename) so a crash mid-write cannot leave a half-written
 * record. A missing or corrupt file is treated as a clean slate so a damaged file can
 * never lock the user out permanently.
 *
 * @param lockoutFile the app-private file that holds the record (e.g.
 *   `context.filesDir/lockout`). Created on first write; its parent directory is created
 *   if needed.
 */
class FileLockoutStore(
    private val lockoutFile: File,
) : LockoutStore {

    override fun read(): LockoutState {
        if (!lockoutFile.isFile || lockoutFile.length() == 0L) return LockoutState()
        return try {
            val buffer = ByteBuffer.wrap(lockoutFile.readBytes())
            val version = buffer.int
            require(version == VERSION) { "Unsupported lockout record version: $version" }
            val failures = buffer.int
            val until = buffer.long
            LockoutState(
                consecutiveFailures = failures,
                lockoutUntil = if (until == NO_LOCKOUT) null else until,
            )
        } catch (_: Exception) {
            // A corrupt lockout file must never block the user permanently.
            LockoutState()
        }
    }

    override fun write(state: LockoutState) {
        val bytes = ByteBuffer.allocate(Int.SIZE_BYTES * 2 + Long.SIZE_BYTES)
            .putInt(VERSION)
            .putInt(state.consecutiveFailures)
            .putLong(state.lockoutUntil ?: NO_LOCKOUT)
            .array()
        lockoutFile.parentFile?.mkdirs()
        val tmp = File(lockoutFile.parentFile, lockoutFile.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(lockoutFile)) {
            lockoutFile.writeBytes(bytes)
            tmp.delete()
        }
    }

    private companion object {
        const val VERSION = 1
        /** Sentinel meaning "no active lockout" (i.e. [LockoutState.lockoutUntil] is null). */
        const val NO_LOCKOUT = Long.MIN_VALUE
    }
}
