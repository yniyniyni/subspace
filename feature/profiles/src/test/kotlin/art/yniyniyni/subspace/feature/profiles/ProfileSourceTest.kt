// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.profiles

import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Task 10 review, Critical 2: [routingOverridesPassthroughFor] is the rule
 * [ProfileSource.BoundProfileSource]'s own `routingOverridesPassthrough` combines
 * `SettingsRepository.activeRoutingRuleSetId`/`.dnsResolver` through — pulled out to a plain
 * function so this JVM test can pin it without constructing a real (Room-backed, `internal`
 * constructor) `SettingsRepository`, which nothing in this module can do (see [ProfileSource]'s
 * own file-level KDoc).
 */
class ProfileSourceTest {
    @Test
    fun `neither routing nor a custom resolver means no override`() {
        routingOverridesPassthroughFor(activeRuleSetId = null, resolver = DnsResolver.DEFAULT) shouldBe false
    }

    @Test
    fun `an active rule set alone means override, even with the default resolver`() {
        routingOverridesPassthroughFor(activeRuleSetId = 7L, resolver = DnsResolver.DEFAULT) shouldBe true
    }

    @Test
    fun `a custom resolver alone means override, even with no active rule set`() {
        val customResolver = DnsResolver(DnsTransport.DOH, domain = "https://dns.example/dns-query")
        routingOverridesPassthroughFor(activeRuleSetId = null, resolver = customResolver) shouldBe true
    }

    @Test
    fun `both together still means override, not a special third case`() {
        val customResolver = DnsResolver(DnsTransport.DOU, ip = "9.9.9.9")
        routingOverridesPassthroughFor(activeRuleSetId = 7L, resolver = customResolver) shouldBe true
    }
}
