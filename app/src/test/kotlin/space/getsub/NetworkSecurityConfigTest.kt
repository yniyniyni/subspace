// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static verification for F1 (2026-09-22 review): the source
 * `AndroidManifest.xml` wires `android:networkSecurityConfig` to a config
 * that permits cleartext to `127.0.0.1` only.
 *
 * This is a plain-JVM test — no Robolectric, no Android framework — so it
 * cannot exercise AAPT's manifest merge or the platform's own cleartext
 * enforcement; it parses the checked-in source XML directly. That is a
 * narrower claim than "the merged manifest is correct" or "cleartext to
 * loopback actually works on device", and this class does not claim either.
 * See the F1 task report for what remains unverified and why (a device below
 * API 37, which this project's dispatch rules forbid touching here).
 */
class NetworkSecurityConfigTest {
    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance().apply {
            // No DTD/entity resolution needed for these trusted, checked-in
            // files; disabling it is just cheap hygiene for an XML parser.
            isValidating = false
        }.newDocumentBuilder().parse(file).also { it.documentElement.normalize() }

    private val manifestFile = File("src/main/AndroidManifest.xml")
    private val networkSecurityConfigFile = File("src/main/res/xml/network_security_config.xml")

    @Test
    fun `the manifest wires networkSecurityConfig to the loopback-only config`() {
        assertTrue("manifest not found at ${manifestFile.absolutePath}", manifestFile.exists())
        val doc = parse(manifestFile)
        val application = doc.getElementsByTagName("application").item(0)
        assertNotNull("no <application> element in the manifest", application)

        val attrs = application.attributes
        val configAttr = attrs.getNamedItem("android:networkSecurityConfig")
        assertNotNull("android:networkSecurityConfig is not set on <application>", configAttr)
        assertEquals("@xml/network_security_config", configAttr.nodeValue)

        // F1: usesCleartextTraffic="true" is the global relaxation the review
        // explicitly refused — it must never come back as a "simpler" fix.
        assertNull(
            "android:usesCleartextTraffic must not be set — F1 refused the global " +
                "cleartext relaxation in favor of a narrow network security config",
            attrs.getNamedItem("android:usesCleartextTraffic"),
        )
    }

    @Test
    fun `the network security config permits cleartext to 127_0_0_1 and nothing else`() {
        assertTrue(
            "config not found at ${networkSecurityConfigFile.absolutePath}",
            networkSecurityConfigFile.exists(),
        )
        val doc = parse(networkSecurityConfigFile)

        // No blanket relaxation: a <base-config> would apply the platform
        // default everywhere unless it says otherwise, and F1 asked for the
        // narrowest exception the platform allows, not a global one.
        val baseConfigs = doc.getElementsByTagName("base-config")
        assertEquals("a <base-config> would broaden this beyond 127.0.0.1", 0, baseConfigs.length)

        val domainConfigs = doc.getElementsByTagName("domain-config")
        assertEquals("expected exactly one <domain-config>", 1, domainConfigs.length)
        val domainConfig = domainConfigs.item(0)
        assertEquals(
            "true",
            domainConfig.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue,
        )

        val domains = (domainConfig as org.w3c.dom.Element).getElementsByTagName("domain")
        assertEquals("expected exactly one <domain> under the domain-config", 1, domains.length)
        val domain = domains.item(0)
        assertEquals("127.0.0.1", domain.textContent.trim())

        // includeSubdomains has no default that makes "absent" safe to rely
        // on across parsers/readers, so the config states it explicitly —
        // assert that explicit value rather than an XML-spec default.
        val includeSubdomains = domain.attributes.getNamedItem("includeSubdomains")
        assertNotNull("includeSubdomains should be stated explicitly, not relied on as a default", includeSubdomains)
        assertFalse(includeSubdomains.nodeValue.toBoolean())
    }
}
