// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound

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

    // ── Adopting a framework start into a live session ──────────────────────
    //
    // [TunnelCommandIngress.reconcile] records a framework start's token before the
    // reconcile it enqueues is decided. When that reconcile answers `Nothing` — the
    // session is live and must not be disturbed — the session goes on settling
    // against the older token it captured at `startTunnel`, `stopSelfResult` refuses
    // the superseded one, and the service is left running with nothing that would
    // ever stop it. [adoptedStartId] is the rule that closes that.

    @Test
    fun `a live session adopts a newer framework start token`() {
        adoptedStartId(sessionStartId = 5, frameworkStartId = 6) shouldBe 6
    }

    /** Nothing holds a started-service lifetime, so there is nothing to move onto it. */
    @Test
    fun `a session with no started lifetime adopts nothing`() {
        adoptedStartId(sessionStartId = 0, frameworkStartId = 6) shouldBe 0
    }

    /**
     * Framework start ids ascend, so anything not newer means no start has arrived since
     * this session claimed its own — and moving backwards would hand the settlement a
     * token `stopSelfResult` may already have resolved.
     */
    @Test
    fun `adoption never moves a session onto an older or repeated token`() {
        adoptedStartId(sessionStartId = 6, frameworkStartId = 6) shouldBe 6
        adoptedStartId(sessionStartId = 6, frameworkStartId = 5) shouldBe 6
        adoptedStartId(sessionStartId = 6, frameworkStartId = 0) shouldBe 6
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
