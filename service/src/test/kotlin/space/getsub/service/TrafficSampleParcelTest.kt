// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.core.model.TagTraffic
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

    @Test
    fun `carries per-tag rows`() {
        // Task 14b: the parcel marshals TagTraffic as three parallel arrays
        // rather than a Parcelable-wrapped list (TagTraffic itself cannot
        // implement Parcelable — it lives in :core:model, which ARCHITECTURE.md
        // §4 keeps free of Android imports). from()/toSample() is what this
        // test can reach on the JVM; writeToParcel/the Parcel constructor need
        // a real android.os.Parcel and are exercised on device instead.
        val sample =
            TrafficSample(
                uplinkBytes = 1,
                downlinkBytes = 2,
                uplinkPackets = 3,
                downlinkPackets = 4,
                perTag =
                listOf(
                    TagTraffic(tag = "proxy", uplinkBytes = 100, downlinkBytes = 200),
                    TagTraffic(tag = "direct", uplinkBytes = 5, downlinkBytes = 7),
                ),
            )
        assertEquals(sample, TrafficSampleParcel.from(sample).toSample())
    }

    @Test
    fun `an empty breakdown round-trips as an empty list, not null`() {
        val sample = TrafficSample(uplinkBytes = 1, downlinkBytes = 2, uplinkPackets = 3, downlinkPackets = 4)
        assertEquals(emptyList<TagTraffic>(), TrafficSampleParcel.from(sample).toSample().perTag)
    }
}
