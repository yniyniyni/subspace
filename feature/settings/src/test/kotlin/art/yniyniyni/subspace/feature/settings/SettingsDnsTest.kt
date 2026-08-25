// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.ProfileDns
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDnsTest {
    @Test
    fun onlyEffectiveActiveProfileDnsOverridesTheAppSetting() {
        assertFalse(dnsOverriddenByProfile(null))
        assertFalse(dnsOverriddenByProfile(ProfileDns.INVALID))
        assertFalse(dnsOverriddenByProfile(ProfileDns(fakeDns = false)))

        assertTrue(dnsOverriddenByProfile(ProfileDns(remote = DnsResolver.DEFAULT)))
        assertTrue(dnsOverriddenByProfile(ProfileDns(hosts = mapOf("example.test" to "192.0.2.1"))))
        assertTrue(dnsOverriddenByProfile(ProfileDns(fakeDns = true)))
    }
}
