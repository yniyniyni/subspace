// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.os.Parcel
import android.os.Parcelable
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.failure

/**
 * Flattens the sealed [ConnectionState] for the AIDL boundary.
 *
 * The discriminant is an explicit int rather than a class name so that adding a
 * state is a deliberate change on both sides of the boundary. Enum ordinals are
 * used for [stage] and [reason] for the same reason they are cheap here and
 * dangerous in storage: this parcel never outlives a single bind, so reordering
 * an enum cannot corrupt anything persisted.
 */
internal data class ConnectionStateParcel(
    val kind: Int,
    val stage: Int,
    val sinceEpochMillis: Long,
    val socksPort: Int,
    val reason: Int,
    val detail: String,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        kind = parcel.readInt(),
        stage = parcel.readInt(),
        sinceEpochMillis = parcel.readLong(),
        socksPort = parcel.readInt(),
        reason = parcel.readInt(),
        detail = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeInt(kind)
        dest.writeInt(stage)
        dest.writeLong(sinceEpochMillis)
        dest.writeInt(socksPort)
        dest.writeInt(reason)
        dest.writeString(detail)
    }

    override fun describeContents(): Int = 0

    /**
     * Rebuilds the sealed state.
     *
     * Failures route through [failure], so [detail] is redacted on this side of
     * the boundary too. Redaction is idempotent, so the second pass costs
     * nothing — and it means a caller cannot receive an unredacted string even
     * if the sending process is ever compromised or changed (§5.6).
     *
     * An unknown [kind] degrades to [ConnectionState.Disconnected] rather than
     * throwing: a state the UI cannot name must not take down the process that
     * is trying to display it.
     */
    fun toState(): ConnectionState =
        when (kind) {
            KIND_DISCONNECTED -> ConnectionState.Disconnected
            KIND_CONNECTING -> ConnectionState.Connecting(StartupStage.entries[stage])
            KIND_CONNECTED -> ConnectionState.Connected(sinceEpochMillis, socksPort)
            KIND_DISCONNECTING -> ConnectionState.Disconnecting
            KIND_FAILED -> failure(FailureReason.entries[reason], detail)
            else -> ConnectionState.Disconnected
        }

    companion object {
        const val KIND_DISCONNECTED = 0
        const val KIND_CONNECTING = 1
        const val KIND_CONNECTED = 2
        const val KIND_DISCONNECTING = 3
        const val KIND_FAILED = 4

        @JvmField
        val CREATOR =
            object : Parcelable.Creator<ConnectionStateParcel> {
                override fun createFromParcel(parcel: Parcel) = ConnectionStateParcel(parcel)

                override fun newArray(size: Int) = arrayOfNulls<ConnectionStateParcel>(size)
            }

        fun from(state: ConnectionState): ConnectionStateParcel =
            when (state) {
                is ConnectionState.Disconnected ->
                    ConnectionStateParcel(KIND_DISCONNECTED, 0, 0L, 0, 0, "")

                is ConnectionState.Connecting ->
                    ConnectionStateParcel(KIND_CONNECTING, state.stage.ordinal, 0L, 0, 0, "")

                is ConnectionState.Connected ->
                    ConnectionStateParcel(
                        KIND_CONNECTED,
                        0,
                        state.sinceEpochMillis,
                        state.socksPort,
                        0,
                        "",
                    )

                is ConnectionState.Disconnecting ->
                    ConnectionStateParcel(KIND_DISCONNECTING, 0, 0L, 0, 0, "")

                is ConnectionState.Failed ->
                    ConnectionStateParcel(KIND_FAILED, 0, 0L, 0, state.reason.ordinal, state.detail)
            }
    }
}
