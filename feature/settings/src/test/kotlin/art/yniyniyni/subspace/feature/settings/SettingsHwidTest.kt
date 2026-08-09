// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class SettingsHwidTest {
    @Test
    fun hwidDefaultsToEnabled() {
        SettingsState().hwidEnabled shouldBe true
    }

    @Test
    fun displayedHwidIsTheValueThatWouldBeSent() {
        SettingsState(hwid = "abc123").hwid shouldBe "abc123"
    }

    @Test
    fun hwidIsNeverTruncatedForDisplay() {
        val full = "K7dQ2mX9pL4nR8vT1yU3wA6sD5fG0hJ2kZ9xC4vB7nM"

        SettingsState(hwid = full).hwid shouldBe full
    }

    @Test
    fun hwidIsRedactedFromDiagnosticStringification() {
        SettingsState(hwid = "abc123").toString() shouldNotContain "abc123"
    }
}
