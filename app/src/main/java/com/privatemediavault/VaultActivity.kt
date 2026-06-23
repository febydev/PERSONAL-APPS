package com.privatemediavault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.privatemediavault.ui.VaultNavHost
import com.privatemediavault.ui.theme.AppBackground
import com.privatemediavault.ui.theme.VaultTheme

/**
 * Single Activity that hosts the entire Compose UI and enforces the session-protection
 * requirements that can only be wired at the activity/lifecycle level.
 *
 * - **App-switcher and screenshot protection (Req 9.3).** [WindowManager.LayoutParams.FLAG_SECURE]
 *   is set on the window before any content is shown, so the system omits the vault's
 *   contents from the recents/app-switcher thumbnail and blocks screenshots.
 * - **Auto-lock on background (Req 9.1, 9.2).** A process-lifecycle observer ends the
 *   session the moment the app moves to the background (`ON_STOP`). Ending the session
 *   zeroes the in-memory DEK and flips [com.privatemediavault.domain.SessionManager.sessionState]
 *   to locked, which the navigation host observes to route back to PIN entry and which the
 *   view models observe to return every item to blurred.
 * - **Single-Activity navigation (Req 5.4, 9.4).** All screens live in one Compose
 *   navigation graph hosted here; locked state always routes to the PIN screen.
 */
class VaultActivity : ComponentActivity() {

    private val container: VaultContainer
        get() = (application as VaultApplication).container

    /**
     * Ends the session whenever the whole app goes to the background. Registered against
     * the *process* lifecycle (not this activity's) so a configuration change does not look
     * like a background event, while a genuine move to background reliably locks the vault.
     */
    private val autoLockObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            container.sessionManager.endSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Req 9.3: suppress the recents thumbnail and screenshots before content is drawn.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        // Req 9.1/9.2: lock the vault when the app is backgrounded.
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockObserver)

        setContent {
            VaultTheme {
                AppBackground {
                    VaultNavHost(container = container)
                }
            }
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(autoLockObserver)
        super.onDestroy()
    }
}
