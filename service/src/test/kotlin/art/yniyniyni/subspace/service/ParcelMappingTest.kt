// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import art.yniyniyni.subspace.core.model.failure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * Round-trips the IPC mappings without touching a `Parcel`.
 *
 * `Parcel` is an Android type stubbed to throw in JVM unit tests, so these
 * exercise the `from`/`to` conversions only. That is where the interesting bugs
 * live anyway: a mis-mapped discriminant silently turns one state into another,
 * and the write/read pair is mechanical.
 */
class ParcelMappingTest {
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

    private val profile = Profile(id = "p1", name = "Test", outbound = outbound)

    @Test
    fun `profile survives a round trip`() {
        ProfileParcel.from(profile).toProfile() shouldBe profile
    }

    @Test
    fun `profile round trip preserves a null flow`() {
        // flow is nullable and writeString/readString round-trips null, unlike
        // most other fields which default to empty. Worth pinning.
        val noFlow = profile.copy(outbound = outbound.copy(flow = null))
        ProfileParcel.from(noFlow).toProfile() shouldBe noFlow
    }

    @Test
    fun `profile round trip preserves tls security`() {
        val tlsSecurity =
            Security.Tls(
                serverName = "example.com",
                fingerprint = "firefox",
                allowInsecure = true,
            )
        val tlsStream = StreamSettings(network = "tcp", security = tlsSecurity)
        val tls = profile.copy(outbound = outbound.copy(stream = tlsStream))

        ProfileParcel.from(tls).toProfile() shouldBe tls
    }

    @Test
    fun `profile round trip preserves a non-zero vmess alterId`() {
        // The field's own KDoc: "non-zero means VMessAEAD is off". A legacy
        // server that needs this is exactly the case a silent 0-rewrite would
        // break with no exception and no log — wrong config, silent auth
        // failure. Pin it explicitly rather than relying on the vless-shaped
        // fixture above to catch a regression here.
        val vmess =
            VmessOutbound(
                address = "example.com",
                port = 8443,
                uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
                alterId = 1,
                security = "auto",
                stream = StreamSettings(network = "tcp", security = Security.None),
            )
        val vmessProfile = profile.copy(outbound = vmess)

        val roundTripped = ProfileParcel.from(vmessProfile).toProfile()

        roundTripped shouldBe vmessProfile
        (roundTripped.outbound as VmessOutbound).alterId shouldBe 1
    }

    @Test
    fun `an unknown protocol degrades to vless instead of throwing`() {
        // Mirrors ConnectionStateParcel's unknown-kind handling: a discriminant
        // the receiving process cannot name must not take it down.
        val parcel =
            ProfileParcel(
                protocol = 99,
                id = "p1",
                name = "Test",
                address = "example.com",
                port = 443,
                uuid = "some-uuid",
                credential2 = "",
                alterId = 0,
                flow = null,
                network = "tcp",
                securityKind = ProfileParcel.SECURITY_NONE,
                serverName = "",
                publicKey = "",
                shortId = "",
                fingerprint = "",
                spiderX = "",
                allowInsecure = false,
            )

        val out = parcel.toProfile().outbound
        out.shouldBeInstanceOf<VlessOutbound>()
        out.address shouldBe "example.com"
        out.port shouldBe 443
    }

    @Test
    fun `profile toString does not leak secrets`() {
        // §5.6. A data class would have printed the uuid and REALITY key, and
        // toString lands in crash output without anyone choosing to log it.
        val rendered = ProfileParcel.from(profile).toString()
        rendered shouldNotContain "70cc48c5"
        rendered shouldNotContain "AAECAwQ"
        rendered shouldNotContain "example.com"
    }

    @Test
    fun `every connection state survives a round trip`() {
        val states =
            listOf(
                ConnectionState.Disconnected,
                ConnectionState.Disconnecting,
                ConnectionState.Connected(sinceEpochMillis = 1_700_000_000_000, socksPort = 10808),
            ) + StartupStage.entries.map { ConnectionState.Connecting(it) }

        states.forEach { state ->
            ConnectionStateParcel.from(state).toState() shouldBe state
        }
    }

    @Test
    fun `every failure reason survives a round trip`() {
        FailureReason.entries.forEach { reason ->
            val state = failure(reason, "something went wrong")
            ConnectionStateParcel.from(state).toState() shouldBe state
        }
    }

    @Test
    fun `failure detail is redacted crossing the boundary`() {
        // Redaction happens on both sides. It is idempotent, so the second pass
        // is free — and it means a receiver cannot be handed a raw address even
        // if the sender is ever changed (§5.6).
        val state = failure(FailureReason.CoreStartFailed, "dial tcp 203.0.113.44:443 refused")
        ConnectionStateParcel.from(state).toState().let { it as ConnectionState.Failed }
            .detail shouldNotContain "203.0.113.44"
    }

    @Test
    fun `an unknown kind degrades to disconnected instead of throwing`() {
        // A state the UI cannot name must not take down the process trying to
        // display it. This is the forward-compatibility path if :bg is ever
        // newer than :main.
        ConnectionStateParcel(
            kind = 99,
            stage = 0,
            sinceEpochMillis = 0,
            socksPort = 0,
            reason = 0,
            detail = "",
        ).toState() shouldBe ConnectionState.Disconnected
    }
}
