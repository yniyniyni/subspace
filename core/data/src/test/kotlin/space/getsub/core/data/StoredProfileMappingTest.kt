// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound

class StoredProfileMappingTest {
    private fun storedProfile(
        id: Long = 1,
        name: String = "Test",
        outbound: Outbound? =
            VlessOutbound(
                address = "198.51.100.1",
                port = 443,
                uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.None),
            ),
    ) = StoredProfile(
        id = id,
        groupId = 1,
        kind = ProfileKind.TYPED,
        name = name,
        protocol = "vless",
        address = "198.51.100.1",
        port = 443,
        transport = "tcp",
        outbound = outbound,
        rawJson = null,
        lastConnectedAt = null,
        lastError = null,
    )

    @Test
    fun aRowWithNoDecodedOutboundIsNotConnectable() {
        storedProfile(outbound = null).toProfile().shouldBeNull()
    }

    @Test
    fun theProfileIdIsTheRowIdAsAString() {
        val mapped = storedProfile(id = 7L, name = "row").toProfile()

        mapped.shouldNotBeNull()
        mapped.id shouldBe "7"
        mapped.name shouldBe "row"
    }
}
