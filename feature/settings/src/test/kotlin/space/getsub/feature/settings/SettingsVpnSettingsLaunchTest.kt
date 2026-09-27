// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.lifecycle.Lifecycle
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Pins controller ruling R14 (M8.5 spec §6 #10, final fix wave finding #1): the deferred
 * `ACTION_VPN_SETTINGS` deep link may fire only while the Settings screen is RESUMED, so it
 * never lands behind a system settings screen the confirm path just launched on top of it.
 */
class SettingsVpnSettingsLaunchTest {
    @Test
    fun doesNotLaunchWhenNotRequested() {
        shouldLaunchVpnSettings(requested = false, lifecycleState = Lifecycle.State.RESUMED) shouldBe false
    }

    @Test
    fun doesNotLaunchWhenRequestedButNotResumed() {
        shouldLaunchVpnSettings(requested = true, lifecycleState = Lifecycle.State.STARTED) shouldBe false
    }

    @Test
    fun doesNotLaunchWhenRequestedButCreated() {
        shouldLaunchVpnSettings(requested = true, lifecycleState = Lifecycle.State.CREATED) shouldBe false
    }

    @Test
    fun launchesWhenRequestedAndResumed() {
        shouldLaunchVpnSettings(requested = true, lifecycleState = Lifecycle.State.RESUMED) shouldBe true
    }
}
