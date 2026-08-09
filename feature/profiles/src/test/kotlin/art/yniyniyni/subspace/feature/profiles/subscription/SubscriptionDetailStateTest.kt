// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.subscription

import art.yniyniyni.subspace.core.data.EffectiveValue
import art.yniyniyni.subspace.core.data.StoredSubscription
import io.kotest.matchers.shouldBe
import org.junit.Test

class SubscriptionDetailStateTest {
    @Test
    fun `an unpinned field shows the provider value alone`() {
        val row = SettingRowState.from(
            EffectiveValue("profile-update-interval", "6", providerValue = "6", isPinned = false),
        )

        row.value shouldBe "6"
        row.showsDeclinedProviderValue shouldBe false
    }

    @Test
    fun `a pinned field disagreeing with the provider shows both`() {
        // Spec D3's UI consequence, and the reason EffectiveValue carries both.
        val row = SettingRowState.from(
            EffectiveValue("profile-update-interval", "24", providerValue = "6", isPinned = true),
        )

        row.value shouldBe "24"
        row.declinedProviderValue shouldBe "6"
        row.showsDeclinedProviderValue shouldBe true
    }

    @Test
    fun `a pinned field agreeing with the provider does not nag`() {
        val row = SettingRowState.from(
            EffectiveValue("profile-update-interval", "6", providerValue = "6", isPinned = true),
        )

        row.showsDeclinedProviderValue shouldBe false
    }

    @Test
    fun `a pinned field the provider never sent shows no declined value`() {
        val row = SettingRowState.from(
            EffectiveValue("profile-update-interval", "24", providerValue = null, isPinned = true),
        )

        row.showsDeclinedProviderValue shouldBe false
    }

    @Test
    fun `a one toggle value is enabled under the shared boolean rule`() {
        SettingRowState("subscription-auto-update-enable", "1", null, isPinned = true).isEnabled shouldBe true
    }

    @Test
    fun `the url is redacted for display`() {
        // §5.6: the URL is a secret. Showing it in full invites a screenshot in
        // a support channel.
        redactUrl("https://panel.example.com/sub/abc123secret") shouldBe
            "https://panel.example.com/…"
    }

    @Test
    fun `redaction survives a url with no path`() {
        redactUrl("https://panel.example.com") shouldBe "https://panel.example.com/…"
    }

    @Test
    fun `an empty response remains a distinct persisted last-fetch outcome`() {
        val stored =
            StoredSubscription(
                id = 1L,
                groupId = 2L,
                url = "https://panel.example.com/sub",
                userAgentOverride = null,
                hwidEnabled = true,
                lastFetchedAt = 100L,
                lastFetchStatus = "NoServers",
                lastFetchDetail = "NoServers",
            )

        stored.toLastFetchState() shouldBe LastFetchState.NoServers(lastSuccessAtEpochMillis = 100L)
    }
}
