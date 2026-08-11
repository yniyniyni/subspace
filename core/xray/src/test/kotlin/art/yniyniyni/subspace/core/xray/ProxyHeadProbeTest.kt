// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.LatencyOptions
import art.yniyniyni.subspace.core.model.LatencyOutcome
import art.yniyniyni.subspace.core.model.PingMode
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val OPTIONS =
    LatencyOptions(
        mode = PingMode.PROXY_HEAD,
        timeoutSeconds = 5,
        checkUrl = "https://example.invalid/204",
    )

/**
 * Stands in for libXray, which is native and cannot run in a JVM test. What is
 * under test here is the orchestration [ProxyHeadProbe] owns — port allocation
 * and its single retry, the unsupported-protocol short circuit, the proxy
 * address, and above all that the temp config never survives the call.
 */
private class FakePingApi(
    private val delay: Int = 42,
    private val failedAllocations: Int = 0,
    private val pingFails: Boolean = false,
) : XrayPingApi {
    var seenConfigPath: String? = null
    var seenProxy: String? = null
    var seenTimeoutSeconds: Int? = null
    var allocateCalls = 0

    override suspend fun allocatePort(): Int {
        allocateCalls++
        if (allocateCalls <= failedAllocations) throw XrayException("no free port")
        return 10800
    }

    override suspend fun ping(
        configPath: String,
        timeoutSeconds: Int,
        url: String,
        proxy: String,
    ): Int {
        seenConfigPath = configPath
        seenProxy = proxy
        seenTimeoutSeconds = timeoutSeconds
        if (pingFails) throw XrayException("ping failed")
        return delay
    }
}

class ProxyHeadProbeTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val stream = StreamSettings(network = "tcp", security = Security.None)

    private fun vlessProfile(): Profile {
        val outbound =
            VlessOutbound(
                address = "example.com",
                port = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                flow = null,
                stream = stream,
            )
        return Profile(id = "p1", name = "test", outbound = outbound)
    }

    /** Trojan is a protocol `:core:xray` deliberately refuses to emit a config for. */
    private fun trojanProfile(): Profile {
        val outbound = TrojanOutbound(address = "example.com", port = 443, password = "pw", stream = stream)
        return Profile(id = "p2", name = "trojan", outbound = outbound)
    }

    @Test
    fun `a successful ping returns OK with the reported delay`() =
        runTest {
            val result = ProxyHeadProbe(FakePingApi(delay = 42), temp.root).measure(vlessProfile(), OPTIONS)
            result.outcome shouldBe LatencyOutcome.OK
            result.delayMillis shouldBe 42
        }

    @Test
    fun `the proxy address names the allocated port on loopback`() =
        runTest {
            val api = FakePingApi()
            ProxyHeadProbe(api, temp.root).measure(vlessProfile(), OPTIONS)
            api.seenProxy shouldBe "socks5://127.0.0.1:10800"
        }

    @Test
    fun `the timeout is passed through in seconds, the unit libXray expects`() =
        runTest {
            val api = FakePingApi()
            ProxyHeadProbe(api, temp.root).measure(vlessProfile(), OPTIONS)
            api.seenTimeoutSeconds shouldBe 5
        }

    @Test
    fun `a protocol the generator cannot emit is UNSUPPORTED with no ping attempted`() =
        runTest {
            val api = FakePingApi()
            val result = ProxyHeadProbe(api, temp.root).measure(trojanProfile(), OPTIONS)
            result.outcome shouldBe LatencyOutcome.UNSUPPORTED
            api.seenConfigPath.shouldBeNull()
        }

    @Test
    fun `the temp config is deleted after a successful measurement`() =
        runTest {
            ProxyHeadProbe(FakePingApi(), temp.root).measure(vlessProfile(), OPTIONS)
            temp.root.listFiles().orEmpty().toList().shouldBeEmpty()
        }

    @Test
    fun `the temp config is deleted after a failed measurement`() =
        runTest {
            val result = ProxyHeadProbe(FakePingApi(pingFails = true), temp.root).measure(vlessProfile(), OPTIONS)
            result.outcome shouldBe LatencyOutcome.UNREACHABLE
            temp.root.listFiles().orEmpty().toList().shouldBeEmpty()
        }

    @Test
    fun `port allocation is retried exactly once, then reported as a failure`() =
        runTest {
            val api = FakePingApi(failedAllocations = 99)
            val result = ProxyHeadProbe(api, temp.root).measure(vlessProfile(), OPTIONS)
            result.outcome shouldBe LatencyOutcome.UNREACHABLE
            api.allocateCalls shouldBe 2
        }

    @Test
    fun `a retried allocation that succeeds still measures`() =
        runTest {
            val api = FakePingApi(failedAllocations = 1)
            val result = ProxyHeadProbe(api, temp.root).measure(vlessProfile(), OPTIONS)
            result.outcome shouldBe LatencyOutcome.OK
            api.allocateCalls shouldBe 2
        }
}
