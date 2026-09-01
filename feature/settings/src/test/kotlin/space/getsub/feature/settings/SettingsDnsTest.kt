// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.ProfileDns

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
