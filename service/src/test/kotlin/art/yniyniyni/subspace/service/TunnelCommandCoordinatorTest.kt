// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelCommandCoordinatorTest {
    @Test
    fun `commands execute in enqueue order and reapply samples the current session`() =
        runTest {
            val old = profileParcel("old")
            val replacement = profileParcel("replacement")
            var liveSession: ProfileParcel? = old
            val transcript = mutableListOf<String>()
            val coordinator =
                TunnelCommandCoordinator(
                    scope = this,
                    connect = { parcel ->
                        liveSession = parcel
                        transcript += "connect:${parcel.id}"
                    },
                    disconnect = {
                        transcript += "disconnect"
                        liveSession = null
                    },
                    reapplyPerApp = { transcript += "reapply:${liveSession?.id ?: "none"}" },
                )

            coordinator.enqueue(TunnelCommand.ReapplyPerApp)
            coordinator.enqueue(TunnelCommand.Connect(replacement))
            coordinator.enqueue(TunnelCommand.ReapplyPerApp)
            coordinator.enqueue(TunnelCommand.Disconnect)
            coordinator.enqueue(TunnelCommand.ReapplyPerApp)
            advanceUntilIdle()
            coordinator.close()

            transcript shouldBe
                listOf(
                    "reapply:old",
                    "connect:replacement",
                    "reapply:replacement",
                    "disconnect",
                    "reapply:none",
                )
        }

    @Test
    fun `disconnect connect and reapply retain their adversarial enqueue order`() =
        runTest {
            val replacement = profileParcel("replacement")
            var liveSession: ProfileParcel? = profileParcel("old")
            val transcript = mutableListOf<String>()
            val coordinator =
                TunnelCommandCoordinator(
                    scope = this,
                    connect = { parcel ->
                        liveSession = parcel
                        transcript += "connect:${parcel.id}"
                    },
                    disconnect = {
                        liveSession = null
                        transcript += "disconnect"
                    },
                    reapplyPerApp = { transcript += "reapply:${liveSession?.id ?: "none"}" },
                )

            coordinator.enqueue(TunnelCommand.Disconnect)
            coordinator.enqueue(TunnelCommand.Connect(replacement))
            coordinator.enqueue(TunnelCommand.ReapplyPerApp)
            advanceUntilIdle()
            coordinator.close()

            transcript shouldBe listOf("disconnect", "connect:replacement", "reapply:replacement")
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
