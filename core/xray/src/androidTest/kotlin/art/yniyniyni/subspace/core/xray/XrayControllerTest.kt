// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Runs `XrayController` against the real libXray on a real device.
 *
 * The important test here is [generatedConfigIsAcceptedByTheRealCore].
 * ARCHITECTURE.md §10.5 warns that agents confidently invent plausible Xray keys,
 * and that an unknown key can be silently ignored *or* reject the whole config.
 * Reviewing the generated JSON by eye cannot catch that. Feeding it to the core's
 * own validator can.
 */
class XrayControllerTest {
    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private val reality =
        Security.Reality(
            serverName = "www.microsoft.com",
            publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            shortId = "0123abcd",
            fingerprint = "chrome",
            spiderX = "/",
        )

    private val outbound =
        VlessOutbound(
            address = "example.com",
            port = 443,
            uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
            flow = "xtls-rprx-vision",
            stream = StreamSettings(network = "tcp", security = reality),
        )

    private fun realityProfile() = Profile(id = "instr-1", name = "Instrumented", outbound = outbound)

    @Test
    fun allocatesARealFreePort() =
        runTest {
            val port = XrayController().allocatePort()
            // §10.6: a dynamically allocated port, not a literal. Anything in the
            // ephemeral range is fine; what matters is that it is not fixed.
            assertTrue("expected a usable port, got $port", port in 1024..65535)
        }

    @Test
    fun allocatesADifferentPortEachTime() =
        runTest {
            val controller = XrayController()
            val first = controller.allocatePort()
            val second = controller.allocatePort()
            // Guards against a stubbed or constant implementation sneaking in.
            assertNotEquals(first, second)
        }

    @Test
    fun generatedConfigIsAcceptedByTheRealCore() =
        runTest {
            // The whole point: no hallucinated keys, proven by the core itself
            // rather than by reading the JSON (§10.5).
            val controller = XrayController()
            val settings =
                TunnelSettings(
                    socksPort = controller.allocatePort(),
                    dnsServer = "1.1.1.1",
                    enableSniffing = true,
                )
            val json = XrayConfigGenerator.generate(realityProfile(), settings)
            val configFile = File(cacheDir, "instr-valid.json").apply { writeText(json) }

            controller.validate(configFile)
            configFile.delete()
        }

    @Test
    fun malformedConfigIsRejectedWithARealError() =
        runTest {
            // §6: catch a bad config here rather than letting runXray fail in a way
            // that is hard to attribute (§10.4). Proves validate() can actually fail
            // — a validator that always passes is worse than none.
            val configFile = File(cacheDir, "instr-broken.json").apply { writeText("{ not json") }

            try {
                XrayController().validate(configFile)
                fail("expected malformed config to be rejected")
            } catch (e: XrayException) {
                assertTrue(
                    "expected a testXray failure, got: ${e.message}",
                    e.message.orEmpty().contains("testXray"),
                )
            } finally {
                configFile.delete()
            }
        }

    @Test
    fun reportsNotRunningBeforeStart() =
        runTest {
            // §5.5: connection state comes from the core, never from a local guess.
            assertTrue(!XrayController().isRunning())
        }

    @Test
    fun stopIsSafeWhenNothingIsRunning() =
        runTest {
            // §5.4: teardown runs from disconnect, onRevoke, and onDestroy, any of
            // which can fire when the core is already down. It must not throw.
            XrayController().stop()
        }
}
