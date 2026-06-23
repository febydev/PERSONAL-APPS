package com.privatemediavault.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for User-adjustable vault settings that more than one screen
 * needs to read or write.
 *
 * Right now the only such setting is the remove-originals preference (Req 4.4): the
 * `SettingsScreen` writes it and the import flow on the vault grid reads it. Keeping it
 * here — rather than privately inside one view model — means both screens observe the same
 * value, so a toggle made in Settings is honoured by the very next import without any
 * cross-view-model plumbing.
 *
 * The default [InMemorySettingsStore] holds the value in memory for the process lifetime.
 * A persistent implementation (e.g. backed by `SecurePrefs`) can be swapped in later
 * without touching the view models, since they depend only on this interface.
 */
interface SettingsStore {

    /**
     * Whether a successful import should delete the source original from its device
     * location (Req 4.4). Observable so the vault grid stays in sync with changes made on
     * the settings screen.
     */
    val removeOriginals: StateFlow<Boolean>

    /** Updates the remove-originals preference (Req 4.4). */
    fun setRemoveOriginals(enabled: Boolean)

    /**
     * A user-controlled global override of the blurred-by-default rule: when `true`, every
     * item in the grid is shown unblurred at once; when `false` (the default), the normal
     * blurred-by-default behaviour holds. This only takes effect while the vault is
     * unlocked — a session lock always returns everything to blurred regardless of this
     * value. Default is `false` so a fresh vault still loads fully blurred.
     */
    val revealAll: StateFlow<Boolean>

    /** Updates the reveal-all override. */
    fun setRevealAll(enabled: Boolean)
}

/**
 * In-memory [SettingsStore]. Holds the preference for the lifetime of the process; this is
 * sufficient because the values are convenience toggles rather than security-critical
 * state. A single instance is shared between the settings and vault view models by the
 * activity-level wiring (task 10.1).
 */
class InMemorySettingsStore(
    initialRemoveOriginals: Boolean = false,
    initialRevealAll: Boolean = false,
) : SettingsStore {

    private val _removeOriginals = MutableStateFlow(initialRemoveOriginals)
    override val removeOriginals: StateFlow<Boolean> = _removeOriginals.asStateFlow()

    override fun setRemoveOriginals(enabled: Boolean) {
        _removeOriginals.value = enabled
    }

    private val _revealAll = MutableStateFlow(initialRevealAll)
    override val revealAll: StateFlow<Boolean> = _revealAll.asStateFlow()

    override fun setRevealAll(enabled: Boolean) {
        _revealAll.value = enabled
    }
}
