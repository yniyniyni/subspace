// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser.directive

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class DirectiveRegistryTest {
    @Test
    fun consumerVocabularyIsClosed() {
        Consumer.entries.map { it.name }.toSet() shouldBe
            setOf(
                "Subscriptions",
                "LatencySorting",
                "Routing",
                "RoutingProfiles",
                "ProfileDns",
                "Passthrough",
                "PlatformHardening",
                "CensorshipResistance",
                "Release",
                "None",
            )
    }

    @Test
    fun sniffingIsConsumedByPlatformHardening() {
        DirectiveRegistry.spec("sniffing-enable")?.consumer shouldBe Consumer.PlatformHardening
    }

    @Test
    fun announceIsConsumedByRelease() {
        DirectiveRegistry.spec("announce")?.consumer shouldBe Consumer.Release
    }

    @Test
    fun `profile-update-interval is whole hours, minimum one`() {
        // Research file: "must be a multiple of one hour". A web search for this
        // key returns profile-title's description attached to it; anyone taking
        // that at face value builds an interval field holding a display string.
        val spec = DirectiveRegistry.spec("profile-update-interval").shouldNotBeNull()
        spec.kind shouldBe DirectiveKind.Integer(min = 1, max = 8760)
        spec.disposition shouldBe Disposition.Accept
        spec.consumer shouldBe Consumer.Subscriptions
    }

    @Test
    fun `profile-title is text capped at twenty-five characters, base64 allowed`() {
        val spec = DirectiveRegistry.spec("profile-title").shouldNotBeNull()
        spec.kind shouldBe DirectiveKind.Text(maxLength = 25, base64Allowed = true)
        spec.danger shouldBe Danger.Benign
    }

    @Test
    fun `announce is capped at two hundred characters`() {
        DirectiveRegistry.spec("announce")?.kind shouldBe
            DirectiveKind.Text(maxLength = 200, base64Allowed = true)
    }

    @Test
    fun `subscription-request-timeout is five to fifteen seconds`() {
        DirectiveRegistry.spec("subscription-request-timeout")?.kind shouldBe
            DirectiveKind.Integer(min = 5, max = 15)
    }

    @Test
    fun `xray-tun-mtu carries its documented range`() {
        DirectiveRegistry.spec("xray-tun-mtu")?.kind shouldBe
            DirectiveKind.Integer(min = 68, max = 65535)
    }

    @Test
    fun `ping-type keeps three modes and drops icmp`() {
        // Appendix D cuts the icmp *value*, not the key: raw sockets need root.
        // This is a key whose schema loses one enum member, not a rejected key.
        val spec = DirectiveRegistry.spec("ping-type").shouldNotBeNull()
        spec.disposition shouldBe Disposition.Accept
        spec.kind shouldBe DirectiveKind.Enumerated(setOf("proxy", "proxy-head", "tcp"))
    }

    @Test
    fun `every Appendix D cut key is rejected with a recorded reason`() {
        val cut =
            listOf(
                "hide-vpn-icon",
                "hide-settings",
                "manual-block-user-agent",
                "subscription-always-hwid-enable",
                "block-bind-to-tunnel-enable",
                "no-limit-enabled",
                "no-limit-xhttp-enabled",
                "proxy-enable",
                "tun-mode",
                "tun-type",
                "custom-tunnel-config",
                "color-profile",
                "include-all-networks-enable",
                "exclude-apns-enable",
                "proxy-ping-timeout",
            )
        cut.forEach { key ->
            val spec = DirectiveRegistry.spec(key).shouldNotBeNull()
            (spec.disposition is Disposition.Reject) shouldBe true
            (spec.disposition as Disposition.Reject).note.isNotBlank() shouldBe true
        }
    }

    @Test
    fun `traffic-redirecting keys are classified Dangerous`() {
        // §A.1: these get explicit user confirmation in this project. The
        // classification rides on the key so M5 and M8 inherit it rather than
        // re-deriving it.
        listOf(
            "new-url",
            "new-domain",
            "fallback-url",
            "server-address-resolve-enable",
            "server-address-resolve-dns-domain",
            "server-address-resolve-dns-ip",
            "per-app-proxy-mode",
            "per-app-proxy-list",
            "per-app-proxy-list-invert",
            "per-app-proxy-list-set",
            "check-url-via-proxy",
        ).forEach { key ->
            DirectiveRegistry.spec(key)?.danger shouldBe Danger.Dangerous
        }
    }

    /**
     * Keys that are `Danger.Dangerous` and nonetheless have a consumer, because
     * §A.1's required explicit confirmation actually exists for them.
     *
     * This set is the whole reason the blanket assertion below was narrowed rather
     * than deleted. Adding a member here is a claim that the key cannot take effect
     * without the user approving it, in a UI that shows them what they are
     * approving. `routing` earns it through M6's import review sheet (spec §6).
     */
    @Suppress("VariableNaming", "ktlint:standard:property-naming")
    private val CONFIRMED_DANGEROUS_KEYS = setOf("routing")

    @Test
    fun dangerousKeysAreUnconsumedUnlessTheyPassThroughConfirmation() {
        DirectiveRegistry.specs.values
            .filter { it.danger == Danger.Dangerous }
            .forEach { spec ->
                if (spec.key in CONFIRMED_DANGEROUS_KEYS) {
                    spec.consumer shouldNotBe Consumer.None
                } else {
                    spec.consumer shouldBe Consumer.None
                }
            }
    }

    @Test
    fun theConfirmedDangerousSetIsExactlyWhatWeThinkItIs() {
        // Pinned so that a future milestone wiring up a second Dangerous key has to
        // change this line, and explain itself in the diff.
        CONFIRMED_DANGEROUS_KEYS shouldBe setOf("routing")
        DirectiveRegistry.specs.values
            .filter { it.danger == Danger.Dangerous && it.consumer != Consumer.None }
            .map { it.key }
            .toSet() shouldBe CONFIRMED_DANGEROUS_KEYS
    }

    @Test
    fun routingIsStoredAsDangerousAndConsumedByRoutingProfiles() {
        val spec = DirectiveRegistry.spec("routing").shouldNotBeNull()
        spec.disposition shouldBe Disposition.Accept
        spec.danger shouldBe Danger.Dangerous
        spec.consumer shouldBe Consumer.RoutingProfiles
        spec.kind shouldBe DirectiveKind.Text(maxLength = 524_288, base64Allowed = false)
    }

    @Test
    fun routingEnableIsSensitiveNotDangerous() {
        val spec = DirectiveRegistry.spec("routing-enable").shouldNotBeNull()
        spec.danger shouldBe Danger.Sensitive
        spec.consumer shouldBe Consumer.RoutingProfiles
    }

    @Test
    fun userAgentGeoFilesBelongsToCensorshipResistance() {
        DirectiveRegistry.spec("user-agent-geo-files")?.consumer shouldBe Consumer.CensorshipResistance
    }

    @Test
    fun perAppProxyKeysStayUnconsumedForever() {
        // M5.5 Part 2 was abandoned on 2026-08-20: no panel in the target set emits
        // these. This is the intended end state, not a deferral. See the roadmap.
        listOf(
            "per-app-proxy-mode",
            "per-app-proxy-list",
            "per-app-proxy-list-invert",
            "per-app-proxy-list-set",
        ).forEach { key ->
            DirectiveRegistry.spec(key)?.consumer shouldBe Consumer.None
        }
    }

    @Test
    fun `every key is lower case and unique`() {
        DirectiveRegistry.specs.forEach { (key, spec) ->
            key shouldBe key.lowercase()
            spec.key shouldBe key
        }
    }

    @Test
    fun `the M4-consumed set is exactly the keys this milestone acts on`() {
        // Pins spec §1.1's scope. Adding a consumer without amending the spec
        // fails here, deliberately.
        DirectiveRegistry.specs.values
            .filter { it.consumer == Consumer.Subscriptions }
            .map { it.key }
            .toSet() shouldBe
            setOf(
                "profile-title",
                "profile-update-interval",
                "subscription-userinfo",
                "subscription-request-timeout",
                "change-user-agent",
                "subscription-auto-update-enable",
                "subscription-auto-update-open-enable",
            )
    }

    @Test
    fun `the registry covers the documented surface`() {
        // A floor, not an exact count: the research file lists ~90 keys across
        // three groups. Far fewer means rows were dropped silently.
        (DirectiveRegistry.specs.size >= 80) shouldBe true
        DirectiveRegistry.specs.keys shouldContain "subscription-userinfo"
    }
}
