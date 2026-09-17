// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Spec §7.2 and ARCHITECTURE.md §9: prompt once, respect refusal. */
class BatteryPromptTest {
    @Test
    fun promptsWhenASurvivalSettingIsTurnedOn() {
        shouldPromptForBattery(
            alreadyShown = false,
            isIgnoringOptimisations = false,
            survivalSettingJustEnabled = true,
        ) shouldBe true
    }

    /** "Respect refusal": once shown, never again, whatever they chose. */
    @Test
    fun neverPromptsTwice() {
        shouldPromptForBattery(
            alreadyShown = true,
            isIgnoringOptimisations = false,
            survivalSettingJustEnabled = true,
        ) shouldBe false
    }

    @Test
    fun doesNotPromptWhenAlreadyExempt() {
        shouldPromptForBattery(
            alreadyShown = false,
            isIgnoringOptimisations = true,
            survivalSettingJustEnabled = true,
        ) shouldBe false
    }

    /**
     * Not at first launch, and not on an unrelated settings change. Doze is
     * worth raising at the moment the user says they want the tunnel to
     * survive; anywhere else it is noise, and noise is how a prompt gets
     * dismissed unread.
     */
    @Test
    fun doesNotPromptOutOfNowhere() {
        shouldPromptForBattery(
            alreadyShown = false,
            isIgnoringOptimisations = false,
            survivalSettingJustEnabled = false,
        ) shouldBe false
    }
}
