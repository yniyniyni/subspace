// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import org.junit.Test

class TunnelCommandIngressTest {
    @Test
    fun `disconnect snapshots the latest start id atomically with enqueue order`() {
        val commands = mutableListOf<TunnelCommand>()
        val ingress = TunnelCommandIngress(commands::add)
        val profile = profileParcel("profile")

        ingress.started(profile, startId = 7)
        ingress.disconnect()
        ingress.started(profile, startId = 8)

        commands shouldBe
            listOf(
                TunnelCommand.Connect(profile, startId = 7),
                TunnelCommand.Disconnect(startId = 7),
                TunnelCommand.Connect(profile, startId = 8),
            )
    }

    @Test
    fun `unknown and malformed starts become closed rejection commands`() {
        val commands = mutableListOf<TunnelCommand>()
        val ingress = TunnelCommandIngress(commands::add)
        val source = profileParcel("malformed")
        val malformed =
            ProfileParcel(
                protocol = Int.MAX_VALUE,
                rowId = source.rowId,
                id = source.id,
                name = source.name,
                address = source.address,
                port = source.port,
                uuid = source.uuid,
                credential2 = source.credential2,
                alterId = source.alterId,
                flow = source.flow,
                network = source.network,
                securityKind = source.securityKind,
                serverName = source.serverName,
                publicKey = source.publicKey,
                shortId = source.shortId,
                fingerprint = source.fingerprint,
                spiderX = source.spiderX,
                allowInsecure = source.allowInsecure,
            )

        ingress.started(profile = null, startId = 3)
        ingress.started(profile = malformed, startId = 4)

        commands shouldBe
            listOf(
                TunnelCommand.RejectConnect(
                    startId = 3,
                    rowId = ProfileParcel.UNASSIGNED_ROW_ID,
                ),
                TunnelCommand.RejectConnect(startId = 4, rowId = malformed.rowId),
            )
    }

    private fun profileParcel(id: String): ProfileParcel =
        ProfileParcel.from(
            Profile(
                id = id,
                name = "profile-$id",
                outbound = VlessOutbound(
                    address = "203.0.113.1",
                    port = 443,
                    uuid = "00000000-0000-0000-0000-000000000001",
                    flow = null,
                    stream = StreamSettings("tcp", Security.None),
                ),
            ),
            rowId = 7L,
        )
}
