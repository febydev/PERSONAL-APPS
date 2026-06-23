package com.privatemediavault.viewmodel

/**
 * Runtime, in-memory render state for a single media item: whether it is currently shown
 * in Clear State (un-blurred) or in Blurred State.
 *
 * This state is intentionally **never persisted** (it lives only in the view-model layer):
 *  - every fresh load defaults to blurred (`isClear = false`, Req 6.1), and
 *  - every session end resets it back to blurred (Req 6.3, 9.1, 9.2).
 *
 * Mirrors the `MediaRenderState` described in the design's Data Models section.
 */
data class MediaRenderState(
    val itemId: String,
    val isClear: Boolean = false,
)
