package com.privatemediavault.domain.model

/**
 * Result of importing a batch of media items.
 *
 * [succeeded] holds the ids of items that imported into Vault Storage; [failed]
 * holds per-file failures. The two sets are disjoint and together account for every
 * requested source (Requirements 4.1, 4.3).
 */
data class ImportReport(
    val succeeded: List<String>,
    val failed: List<FailedImport>
)

/**
 * A single import that failed, identified by its source name with a human-readable reason.
 */
data class FailedImport(
    val sourceName: String,
    val reason: String
)
