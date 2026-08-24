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
                    connect = { parcel, startId ->
                        liveSession = parcel
                        transcript += "connect:${parcel.id}:$startId"
                    },
                    rejectConnect = { startId, rowId -> transcript += "reject:$rowId:$startId" },
                    disconnect = { startId ->
                        transcript += "disconnect:$startId"
                        liveSession = null
                    },
                    reapplyPerApp = { transcript += "reapply:${liveSession?.id ?: "none"}" },
                )

            coordinator.enqueue(TunnelCommand.ReapplyPerApp)
            coordinator.enqueue(TunnelCommand.Connect(replacement, startId = 2))
            coordinator.enqueue(TunnelCommand.ReapplyPerApp)
            coordinator.enqueue(TunnelCommand.Disconnect(startId = 2))
            coordinator.enqueue(TunnelCommand.ReapplyPerApp)
            advanceUntilIdle()
            coordinator.close()

            transcript shouldBe
                listOf(
                    "reapply:old",
                    "connect:replacement:2",
                    "reapply:replacement",
                    "disconnect:2",
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
                    connect = { parcel, startId ->
                        liveSession = parcel
                        transcript += "connect:${parcel.id}:$startId"
                    },
                    rejectConnect = { startId, rowId -> transcript += "reject:$rowId:$startId" },
                    disconnect = { startId ->
                        liveSession = null
                        transcript += "disconnect:$startId"
                    },
                    reapplyPerApp = { transcript += "reapply:${liveSession?.id ?: "none"}" },
                )

            coordinator.enqueue(TunnelCommand.Disconnect(startId = 1))
            coordinator.enqueue(TunnelCommand.Connect(replacement, startId = 2))
            coordinator.enqueue(TunnelCommand.ReapplyPerApp)
            advanceUntilIdle()
            coordinator.close()

            transcript shouldBe listOf("disconnect:1", "connect:replacement:2", "reapply:replacement")
        }

    @Test
    fun `an older disconnect cannot clear a later connect start lifetime`() =
        runTest {
            var latestFrameworkStartId = 1
            val transcript = mutableListOf<String>()
            val coordinator =
                TunnelCommandCoordinator(
                    scope = this,
                    connect = { parcel, startId -> transcript += "connect:${parcel.id}:$startId" },
                    rejectConnect = { startId, rowId -> transcript += "reject:$rowId:$startId" },
                    disconnect = { startId ->
                        transcript += "disconnect:$startId"
                        transcript += "stop:$startId:${startId == latestFrameworkStartId}"
                    },
                    reapplyPerApp = {},
                )
            val ingress = TunnelCommandIngress(coordinator::enqueue)

            ingress.started(profileParcel("old"), startId = 1)
            ingress.disconnect()
            latestFrameworkStartId = 2
            ingress.started(profileParcel("later"), startId = 2)
            advanceUntilIdle()
            coordinator.close()

            transcript shouldBe
                listOf(
                    "connect:old:1",
                    "disconnect:1",
                    "stop:1:false",
                    "connect:later:2",
                )
        }

    @Test
    fun `malformed then valid connect has one FIFO transcript and preserves later lifetime`() =
        runTest {
            var latestFrameworkStartId = 1
            var state = "active"
            val transcript = mutableListOf<String>()
            val coordinator =
                TunnelCommandCoordinator(
                    scope = this,
                    connect = { parcel, startId ->
                        state = parcel.id
                        transcript += "connect:${parcel.id}:$startId"
                    },
                    rejectConnect = { startId, rowId ->
                        state = "failed"
                        transcript += "reject:$rowId:$startId"
                        transcript += "stop:$startId:${startId == latestFrameworkStartId}"
                    },
                    disconnect = { startId -> transcript += "disconnect:$startId" },
                    reapplyPerApp = {},
                )
            val ingress = TunnelCommandIngress(coordinator::enqueue)

            ingress.started(profile = null, startId = 1)
            latestFrameworkStartId = 2
            ingress.started(profileParcel("valid"), startId = 2)
            advanceUntilIdle()
            coordinator.close()

            transcript shouldBe listOf("reject:0:1", "stop:1:false", "connect:valid:2")
            state shouldBe "valid"
        }

    @Test
    fun `valid then malformed connect has one FIFO transcript and stops latest lifetime`() =
        runTest {
            var latestFrameworkStartId = 1
            var state = "idle"
            val transcript = mutableListOf<String>()
            val coordinator =
                TunnelCommandCoordinator(
                    scope = this,
                    connect = { parcel, startId ->
                        state = parcel.id
                        transcript += "connect:${parcel.id}:$startId"
                    },
                    rejectConnect = { startId, rowId ->
                        state = "failed"
                        transcript += "reject:$rowId:$startId"
                        transcript += "stop:$startId:${startId == latestFrameworkStartId}"
                    },
                    disconnect = { startId -> transcript += "disconnect:$startId" },
                    reapplyPerApp = {},
                )
            val ingress = TunnelCommandIngress(coordinator::enqueue)

            ingress.started(profileParcel("valid"), startId = 1)
            latestFrameworkStartId = 2
            ingress.started(profile = null, startId = 2)
            advanceUntilIdle()
            coordinator.close()

            transcript shouldBe listOf("connect:valid:1", "reject:0:2", "stop:2:true")
            state shouldBe "failed"
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
