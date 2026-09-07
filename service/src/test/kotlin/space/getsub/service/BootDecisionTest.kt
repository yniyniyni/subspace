// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Spec §4.2. Boot autostart connects on **every** boot when on, regardless of
 * whether the session was wanted when the device went down — a user who asks the
 * app to connect at boot means at every boot. So this path writes intent rather
 * than reading it.
 */
class BootDecisionTest {
    @Test
    fun offMeansNothingHappens() {
        bootAction(autostartEnabled = false, vpnConsentGranted = true, activeProfileRowId = 1L) shouldBe
            BootAction.DoNothing
    }

    @Test
    fun onWithConsentAndAProfileConnects() {
        bootAction(autostartEnabled = true, vpnConsentGranted = true, activeProfileRowId = 1L) shouldBe
            BootAction.Connect
    }

    /**
     * `VpnService.prepare()` returns null only when consent already exists, and
     * there is no Activity at boot to show the dialog. Give up quietly — do not
     * crash, and do not nag.
     */
    @Test
    fun consentGoneMeansGiveUpQuietly() {
        bootAction(autostartEnabled = true, vpnConsentGranted = false, activeProfileRowId = 1L) shouldBe
            BootAction.DoNothing
    }

    @Test
    fun noActiveProfileMeansNothingToConnectTo() {
        bootAction(autostartEnabled = true, vpnConsentGranted = true, activeProfileRowId = null) shouldBe
            BootAction.DoNothing
    }

    /**
     * The difference from every other entry point: prior session state is not an
     * input. If it were, a user who disconnected before rebooting would never get
     * the boot start they asked for.
     */
    @Test
    fun priorSessionStateIsNotAnInput() {
        bootAction(autostartEnabled = true, vpnConsentGranted = true, activeProfileRowId = 3L) shouldBe
            BootAction.Connect
    }
}
