package com.privatemediavault.viewmodel

/**
 * Holds the runtime [MediaRenderState] for the currently loaded media items and provides
 * the "return everything to blurred" operation that runs on session end.
 *
 * This is the minimal, JVM-testable seam behind the blurred-by-default and re-blur-on-lock
 * guarantees. The richer `VaultViewModel` (task 9.2) observes [com.privatemediavault.domain.SessionManager.sessionState]
 * and surfaces this state to Compose; non-observing components can instead wire
 * [resetAllToBlurred] as the `SessionManager` `onSessionEnd` hook.
 *
 * The decisive guarantee (Req 6.3, 9.1, 9.2) is that [resetAllToBlurred] is **total and
 * idempotent**: it drives every tracked item to Blurred State regardless of its prior
 * state, so even when an earlier protective step left some items clear — the partial-failure
 * scenario of Req 9.2 — calling it leaves no item in Clear State.
 *
 * Not thread-safe by itself; the owning view model confines access to its own scope.
 */
class MediaRenderStateHolder {

    private val states = LinkedHashMap<String, MediaRenderState>()

    /**
     * Loads [itemIds] for display, each defaulting to Blurred State (Req 6.1). Replaces any
     * previously tracked state. Load order is preserved.
     */
    fun load(itemIds: List<String>) {
        states.clear()
        itemIds.forEach { id -> states[id] = MediaRenderState(itemId = id, isClear = false) }
    }

    /** The current render states, in load order. */
    fun renderStates(): List<MediaRenderState> = states.values.toList()

    /**
     * Sets the clear/blurred state of a single tracked item; a no-op for unknown ids.
     * Models the user un-blurring (or re-blurring) an arbitrary subset (Req 7.1, 8.1).
     */
    fun setClear(itemId: String, isClear: Boolean) {
        val current = states[itemId] ?: return
        states[itemId] = current.copy(isClear = isClear)
    }

    /**
     * Returns every tracked item to Blurred State. Total and idempotent: after this call no
     * item remains clear, irrespective of prior state (Req 6.3, 9.1, 9.2). Safe — and
     * intended — to invoke as the `SessionManager` `onSessionEnd` hook.
     */
    fun resetAllToBlurred() {
        states.keys.toList().forEach { id ->
            states[id] = states.getValue(id).copy(isClear = false)
        }
    }
}
