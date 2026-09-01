// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.Parcel
import android.os.Parcelable
import space.getsub.core.model.LatencyOptions
import space.getsub.core.model.PingMode

/**
 * One measurement run's options, across the AIDL boundary.
 *
 * [mode] is an explicit int discriminant rather than an enum name, in the same
 * style as [ConnectionStateParcel]'s `kind`: adding a mode is then a deliberate
 * change on both sides instead of a string that silently fails to match.
 *
 * Options are resolved in `:main` before the call — from settings, and from the
 * owning subscription's directives — so `:bg` never has to reach into either to
 * interpret a run.
 */
public data class LatencyOptionsParcel(
    val mode: Int,
    val timeoutSeconds: Int,
    val checkUrl: String,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        mode = parcel.readInt(),
        timeoutSeconds = parcel.readInt(),
        checkUrl = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeInt(mode)
        dest.writeInt(timeoutSeconds)
        dest.writeString(checkUrl)
    }

    override fun describeContents(): Int = 0

    /**
     * An unknown [mode] degrades to [PingMode.PROXY_HEAD] rather than throwing,
     * for the reason [ConnectionStateParcel.toState] degrades to `Disconnected`:
     * a value this side cannot name must not take down the process reading it.
     */
    public fun toOptions(): LatencyOptions =
        LatencyOptions(
            mode = if (mode == MODE_TCP) PingMode.TCP else PingMode.PROXY_HEAD,
            timeoutSeconds = timeoutSeconds,
            checkUrl = checkUrl,
        )

    public companion object {
        public const val MODE_TCP: Int = 0
        public const val MODE_PROXY_HEAD: Int = 1

        public fun from(options: LatencyOptions): LatencyOptionsParcel =
            LatencyOptionsParcel(
                mode = if (options.mode == PingMode.TCP) MODE_TCP else MODE_PROXY_HEAD,
                timeoutSeconds = options.timeoutSeconds,
                checkUrl = options.checkUrl,
            )

        @JvmField
        public val CREATOR: Parcelable.Creator<LatencyOptionsParcel> =
            object : Parcelable.Creator<LatencyOptionsParcel> {
                override fun createFromParcel(source: Parcel): LatencyOptionsParcel = LatencyOptionsParcel(source)

                override fun newArray(size: Int): Array<LatencyOptionsParcel?> = arrayOfNulls(size)
            }
    }
}
