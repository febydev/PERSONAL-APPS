package com.privatemediavault.domain.model

/**
 * Tunable cost parameters for the Argon2id password hash used for both the one-way
 * PIN hash (Req 1.5) and the PIN-derived key-encryption key (KEK).
 *
 * The same parameters must be used to produce and later verify/re-derive a value, so
 * they are persisted alongside the PIN hash in the vault key record (see
 * [com.privatemediavault.domain.model] / `VaultKeyRecord`, task 4.1).
 *
 * Defaults follow common interactive-login guidance for mobile devices: a 64 MiB
 * memory cost, three iterations, single-lane parallelism, and a 256-bit output so the
 * derived KEK is a valid AES-256 key.
 *
 * @property memoryKib    memory cost in kibibytes (`mCostInKibibyte`).
 * @property iterations   number of passes over memory (`tCostInIterations`).
 * @property parallelism  number of parallel lanes.
 * @property hashLengthBytes length of the produced hash / derived key in bytes.
 */
data class ArgonParams(
    val memoryKib: Int = 65_536,
    val iterations: Int = 3,
    val parallelism: Int = 1,
    val hashLengthBytes: Int = 32
) {
    init {
        require(memoryKib > 0) { "memoryKib must be positive" }
        require(iterations > 0) { "iterations must be positive" }
        require(parallelism > 0) { "parallelism must be positive" }
        require(hashLengthBytes >= 16) { "hashLengthBytes must be at least 16" }
    }
}
