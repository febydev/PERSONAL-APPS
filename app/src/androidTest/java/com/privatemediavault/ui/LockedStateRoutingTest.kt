package com.privatemediavault.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privatemediavault.domain.model.SessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for locked-state routing to PIN entry (Requirements 9.1, 9.2).
 *
 * `VaultNavHost` observes the session state and, on any transition to
 * [SessionState.Locked], routes back to the PIN screen and clears the authenticated back
 * stack. That decision is implemented by the production [shouldRouteToPin] predicate, which
 * this test drives directly across every relevant state/destination combination — verifying
 * the *actual* routing logic without standing up the full Compose navigation graph.
 *
 * Runs as an instrumentation test for parity with the other lifecycle/window integration
 * tests in this suite; it requires a device/emulator to execute.
 */
@RunWith(AndroidJUnit4::class)
class LockedStateRoutingTest {

    private val unlocked: SessionState = SessionState.Unlocked(startedAt = 0L)
    private val locked: SessionState = SessionState.Locked

    @Test
    fun locked_onGrid_routesToPin() {
        // Req 9.1/9.2: a locked session viewing the grid must be sent to PIN entry.
        assertTrue(shouldRouteToPin(locked, VaultRoute.GRID))
    }

    @Test
    fun locked_onViewer_routesToPin() {
        assertTrue(shouldRouteToPin(locked, VaultRoute.VIEWER))
    }

    @Test
    fun locked_onSettings_routesToPin() {
        assertTrue(shouldRouteToPin(locked, VaultRoute.SETTINGS))
    }

    @Test
    fun locked_alreadyOnPin_doesNotReRoute() {
        // Already on PIN: no redundant navigation.
        assertFalse(shouldRouteToPin(locked, VaultRoute.PIN))
    }

    @Test
    fun locked_withNoCurrentDestination_doesNotRoute() {
        // Graph not laid out yet (null route): nothing to route away from.
        assertFalse(shouldRouteToPin(locked, null))
    }

    @Test
    fun unlocked_staysOnAuthenticatedScreens() {
        // An active session must never be bounced to PIN.
        assertFalse(shouldRouteToPin(unlocked, VaultRoute.GRID))
        assertFalse(shouldRouteToPin(unlocked, VaultRoute.VIEWER))
        assertFalse(shouldRouteToPin(unlocked, VaultRoute.SETTINGS))
        assertFalse(shouldRouteToPin(unlocked, VaultRoute.PIN))
        assertFalse(shouldRouteToPin(unlocked, null))
    }
}
