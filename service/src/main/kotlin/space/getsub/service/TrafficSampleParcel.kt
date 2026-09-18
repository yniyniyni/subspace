// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.Parcel
import android.os.Parcelable
import space.getsub.core.model.TagTraffic
import space.getsub.core.model.TrafficSample

/**
 * Flattens [TrafficSample] for the AIDL boundary, following
 * [ConnectionStateParcel]'s shape.
 *
 * Unlike [ConnectionStateParcel] — which carries a diagnostic [String] and
 * therefore redacts it on the far side (§5.6) — the four traffic totals here
 * are not config content, so there is nothing for a `redact()` step to do on
 * them. [TagTraffic.tag] is different: it is an outbound tag from the user's
 * own config (§5.6). It is not redacted either, but for the opposite reason —
 * it goes straight to the UI that config's own author is looking at, and is
 * never logged (see [TagTraffic]'s own KDoc). `:core:model` cannot implement
 * [Parcelable] itself (ARCHITECTURE.md §4: zero Android imports), so [perTag]
 * is marshalled here as three parallel arrays rather than a list of a second,
 * Parcelable-wrapped tag type.
 */
public data class TrafficSampleParcel(
    val uplinkBytes: Long,
    val downlinkBytes: Long,
    val uplinkPackets: Long,
    val downlinkPackets: Long,
    val perTag: List<TagTraffic> = emptyList(),
) : Parcelable {
    constructor(parcel: Parcel) : this(
        uplinkBytes = parcel.readLong(),
        downlinkBytes = parcel.readLong(),
        uplinkPackets = parcel.readLong(),
        downlinkPackets = parcel.readLong(),
        perTag = parcel.readTagTraffic(),
    )

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeLong(uplinkBytes)
        dest.writeLong(downlinkBytes)
        dest.writeLong(uplinkPackets)
        dest.writeLong(downlinkPackets)
        dest.writeTagTraffic(perTag)
    }

    override fun describeContents(): Int = 0

    fun toSample(): TrafficSample =
        TrafficSample(
            uplinkBytes = uplinkBytes,
            downlinkBytes = downlinkBytes,
            uplinkPackets = uplinkPackets,
            downlinkPackets = downlinkPackets,
            perTag = perTag,
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
                perTag = sample.perTag,
            )
    }
}

/** [TrafficSampleParcel]'s wire encoding for [TrafficSampleParcel.perTag] — three parallel arrays. */
private fun Parcel.writeTagTraffic(rows: List<TagTraffic>) {
    writeStringList(rows.map { it.tag })
    writeLongArray(rows.map { it.uplinkBytes }.toLongArray())
    writeLongArray(rows.map { it.downlinkBytes }.toLongArray())
}

/** The inverse of [writeTagTraffic]. Any array Android hands back null is treated as empty. */
private fun Parcel.readTagTraffic(): List<TagTraffic> {
    val tags = createStringArrayList().orEmpty()
    val uplinks = createLongArray() ?: LongArray(0)
    val downlinks = createLongArray() ?: LongArray(0)
    return tags.indices.map { i ->
        TagTraffic(
            tag = tags[i],
            uplinkBytes = uplinks.getOrElse(i) { 0L },
            downlinkBytes = downlinks.getOrElse(i) { 0L },
        )
    }
}
