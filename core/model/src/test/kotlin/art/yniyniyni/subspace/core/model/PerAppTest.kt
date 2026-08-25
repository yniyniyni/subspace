// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class PerAppTest {
    @Test
    fun theThreeWireWordsAreHappsNotTheEnumNames() {
        perAppModeFrom("off") shouldBe PerAppMode.Off
        perAppModeFrom("on") shouldBe PerAppMode.AllowList
        perAppModeFrom("bypass") shouldBe PerAppMode.DenyList
    }

    @Test
    fun everyModeRoundTripsThroughItsWireWord() {
        PerAppMode.entries.forEach { mode ->
            perAppModeFrom(perAppModeWire(mode)) shouldBe mode
        }
    }

    // Off is the only safe fallback: it is the one value that cannot change
    // which traffic leaves the device.
    @Test
    fun anythingUnrecognisedIncludingNullFallsBackToOff() {
        perAppModeFrom(null) shouldBe PerAppMode.Off
        perAppModeFrom("") shouldBe PerAppMode.Off
        perAppModeFrom("AllowList") shouldBe PerAppMode.Off
        perAppModeFrom("proxy") shouldBe PerAppMode.Off
    }

    @Test
    fun aSelectionCarriesItsModeAndPackages() {
        val selection = PerAppSelection(PerAppMode.DenyList, setOf("com.example.bank"))

        selection.mode shouldBe PerAppMode.DenyList
        selection.packages shouldBe setOf("com.example.bank")
    }

    @Test
    fun theDefaultSelectionIsOffAndEmpty() {
        PerAppSelection.OFF.mode shouldBe PerAppMode.Off
        PerAppSelection.OFF.packages shouldBe emptySet()
    }
}
