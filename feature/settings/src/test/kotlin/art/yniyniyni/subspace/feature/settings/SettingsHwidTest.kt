// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsHwidTest {
    @Test
    fun hwidDefaultsToEnabled() {
        SettingsState().hwidEnabled shouldBe true
    }

    // Two tests used to sit here — `displayedHwidIsTheValueThatWouldBeSent` and
    // `hwidIsNeverTruncatedForDisplay` — each of which constructed SettingsState(hwid = x) and
    // read `.hwid` straight back. `hwid` is a plain constructor val, so both were tautologies on
    // Kotlin's data-class generation: they invoked no production code and would have stayed green
    // with the entire HWID feature deleted. Their names claimed coverage of what is *sent* and of
    // *display*, neither of which they touched. The real coverage lives where the behaviour does:
    // SettingsHwidLayoutTest asserts the value renders untruncated on one line, and
    // SubscriptionFetcherTest asserts the same value reaches the x-hwid header.

    @Test
    fun hwidIsRedactedFromDiagnosticStringification() {
        SettingsState(hwid = "abc123").toString() shouldNotContain "abc123"
    }

    @Test
    fun dnsEndpointsAreRedactedFromDiagnosticStringification() {
        val state =
            SettingsState(
                dnsAddress = "https://resolver.example/dns-query",
                dnsBootstrapIp = "192.0.2.12",
            )
        val diagnostic = state.toString()

        assertFalse("DNS address leaked into diagnostic text", diagnostic.contains("resolver.example"))
        assertFalse("DNS bootstrap IP leaked into diagnostic text", diagnostic.contains("192.0.2.12"))
    }
}
