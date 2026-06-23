package com.privatemediavault.domain.model

/**
 * Outcome of exporting a decrypted copy of a media item out of the Vault.
 *
 * Export is session-gated: it produces a decrypted copy only while an Authenticated
 * Session is active (Req 11.1). When the session is locked the repository reports
 * [SessionLocked] rather than throwing, so the UI can deny the action and route the
 * User to the PIN entry screen (Req 11.2). Any other failure (missing blob, I/O error)
 * is reported as [Failed] with a human-readable reason.
 */
sealed interface ExportResult {
    /** A decrypted copy was written to the User-selected destination. */
    data object Success : ExportResult

    /** The session was locked, so the export was refused (Req 11.2). */
    data object SessionLocked : ExportResult

    /** The export failed for a non-session reason; [reason] explains what went wrong. */
    data class Failed(val reason: String) : ExportResult
}
