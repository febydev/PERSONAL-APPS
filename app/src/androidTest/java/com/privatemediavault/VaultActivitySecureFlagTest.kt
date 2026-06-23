package com.privatemediavault

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for app-switcher / screenshot protection (Requirement 9.3).
 *
 * `VaultActivity` sets [WindowManager.LayoutParams.FLAG_SECURE] on its window in `onCreate`
 * before any content is drawn, so the system omits the vault's contents from the recents
 * thumbnail and blocks screenshots. This test launches the real activity via
 * [ActivityScenario] and asserts the flag is present on the live window.
 *
 * It requires a device/emulator because it launches a real Android [android.app.Activity]
 * and inspects its window attributes.
 */
@RunWith(AndroidJUnit4::class)
class VaultActivitySecureFlagTest {

    @Test
    fun vaultActivityWindow_hasFlagSecureSet() {
        ActivityScenario.launch(VaultActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertEquals(
                    "VaultActivity must set FLAG_SECURE so the recents snapshot and " +
                        "screenshots are blocked (Req 9.3)",
                    WindowManager.LayoutParams.FLAG_SECURE,
                    flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        }
    }
}
