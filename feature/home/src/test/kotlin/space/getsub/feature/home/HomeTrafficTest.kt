// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.core.model.TrafficSample

class HomeTrafficTest {
    @Test
    fun `a disconnected session renders no traffic`() =
        runTest {
            val state = HomeState(traffic = null)
            assertEquals(null, state.traffic)
        }

    @Test
    fun `a connected session carries its totals`() =
        runTest {
            val sample =
                TrafficSample(uplinkBytes = 1_024, downlinkBytes = 2_048, uplinkPackets = 4, downlinkPackets = 8)
            val state = HomeState(traffic = sample)
            assertEquals(1_024L, state.traffic?.uplinkBytes)
            assertEquals(2_048L, state.traffic?.downlinkBytes)
        }
}
