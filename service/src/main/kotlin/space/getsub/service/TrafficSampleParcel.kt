// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.Parcel
import android.os.Parcelable
import space.getsub.core.model.TrafficSample

/**
 * Flattens [TrafficSample] for the AIDL boundary, following
 * [ConnectionStateParcel]'s shape.
 *
 * Unlike [ConnectionStateParcel] — which carries a diagnostic [String] and
 * therefore redacts it on the far side (§5.6) — this parcel carries four
 * `Long`s and nothing else. There is no free text here, so there is nothing
 * for a `redact()` step to do; that absence is deliberate, not an omission.
 */
public data class TrafficSampleParcel(
    val uplinkBytes: Long,
    val downlinkBytes: Long,
    val uplinkPackets: Long,
    val downlinkPackets: Long,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        uplinkBytes = parcel.readLong(),
        downlinkBytes = parcel.readLong(),
        uplinkPackets = parcel.readLong(),
        downlinkPackets = parcel.readLong(),
    )

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeLong(uplinkBytes)
        dest.writeLong(downlinkBytes)
        dest.writeLong(uplinkPackets)
        dest.writeLong(downlinkPackets)
    }

    override fun describeContents(): Int = 0

    fun toSample(): TrafficSample =
        TrafficSample(
            uplinkBytes = uplinkBytes,
            downlinkBytes = downlinkBytes,
            uplinkPackets = uplinkPackets,
            downlinkPackets = downlinkPackets,
        )

    companion object {
        @JvmField
        val CREATOR =
            object : Parcelable.Creator<TrafficSampleParcel> {
                override fun createFromParcel(parcel: Parcel) = TrafficSampleParcel(parcel)

                override fun newArray(size: Int) = arrayOfNulls<TrafficSampleParcel>(size)
            }

        fun from(sample: TrafficSample): TrafficSampleParcel =
            TrafficSampleParcel(
                uplinkBytes = sample.uplinkBytes,
                downlinkBytes = sample.downlinkBytes,
                uplinkPackets = sample.uplinkPackets,
                downlinkPackets = sample.downlinkPackets,
            )
    }
}
