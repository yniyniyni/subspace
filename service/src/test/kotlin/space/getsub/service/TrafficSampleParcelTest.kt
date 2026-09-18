// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.core.model.TrafficSample

class TrafficSampleParcelTest {
    @Test
    fun `carries every field`() {
        val sample = TrafficSample(uplinkBytes = 1, downlinkBytes = 2, uplinkPackets = 3, downlinkPackets = 4)
        assertEquals(sample, TrafficSampleParcel.from(sample).toSample())
    }

    @Test
    fun `carries values past the 32-bit boundary intact`() {
        // The whole reason TrafficSampler accumulates into a Long — a parcel
        // that narrowed this would undo spec §1.3 silently.
        val sample =
            TrafficSample(
                uplinkBytes = 8_000_000_000L,
                downlinkBytes = 9_000_000_000L,
                uplinkPackets = 0,
                downlinkPackets = 0,
            )
        assertEquals(sample, TrafficSampleParcel.from(sample).toSample())
    }
}
