// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.os.Parcel
import android.os.Parcelable
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound

/**
 * Carries a [Profile] across the AIDL boundary.
 *
 * M1 supports VLESS only, so the fields are written flat rather than through a
 * general encoding. **This class is deleted in M3**, when profiles come from Room
 * and the service is handed an id instead of a whole profile — so do not invest
 * in generality here.
 *
 * §5.6: this parcel carries the UUID and REALITY key in the clear. That is
 * acceptable — it travels only over a same-UID binder to our own `:bg` process,
 * which needs the values to build the config. It must never be logged, which is
 * why this is a plain class with a hand-written [toString]: a `data class` would
 * generate one that prints every secret, and `toString` reaches crash output
 * without anyone choosing to log it.
 */
@Suppress("LongParameterList")
// A parcel is a flat wire format; the parameter count is the field count, and
// grouping them into sub-objects would mean more Parcelable plumbing for a class
// that M3 deletes. The constructor is never called by hand — use [from].
internal class ProfileParcel(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val flow: String?,
    val network: String,
    val securityKind: Int,
    val serverName: String,
    val publicKey: String,
    val shortId: String,
    val fingerprint: String,
    val spiderX: String,
    val allowInsecure: Boolean,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString().orEmpty(),
        name = parcel.readString().orEmpty(),
        address = parcel.readString().orEmpty(),
        port = parcel.readInt(),
        uuid = parcel.readString().orEmpty(),
        flow = parcel.readString(),
        network = parcel.readString().orEmpty(),
        securityKind = parcel.readInt(),
        serverName = parcel.readString().orEmpty(),
        publicKey = parcel.readString().orEmpty(),
        shortId = parcel.readString().orEmpty(),
        fingerprint = parcel.readString().orEmpty(),
        spiderX = parcel.readString().orEmpty(),
        allowInsecure = parcel.readInt() != 0,
    )

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(id)
        dest.writeString(name)
        dest.writeString(address)
        dest.writeInt(port)
        dest.writeString(uuid)
        dest.writeString(flow)
        dest.writeString(network)
        dest.writeInt(securityKind)
        dest.writeString(serverName)
        dest.writeString(publicKey)
        dest.writeString(shortId)
        dest.writeString(fingerprint)
        dest.writeString(spiderX)
        dest.writeInt(if (allowInsecure) 1 else 0)
    }

    override fun describeContents(): Int = 0

    /** Deliberately prints no field values — see the §5.6 note on the class. */
    override fun toString(): String = "ProfileParcel(id=$id)"

    fun toProfile(): Profile {
        val stream = StreamSettings(network = network, security = security())
        val out =
            VlessOutbound(
                address = address,
                port = port,
                uuid = uuid,
                flow = flow,
                stream = stream,
            )
        return Profile(id = id, name = name, outbound = out)
    }

    private fun security(): Security =
        when (securityKind) {
            SECURITY_REALITY ->
                Security.Reality(
                    serverName = serverName,
                    publicKey = publicKey,
                    shortId = shortId,
                    fingerprint = fingerprint,
                    spiderX = spiderX,
                )

            SECURITY_TLS ->
                Security.Tls(
                    serverName = serverName,
                    fingerprint = fingerprint,
                    allowInsecure = allowInsecure,
                )

            else -> Security.None
        }

    companion object {
        const val SECURITY_NONE = 0
        const val SECURITY_REALITY = 1
        const val SECURITY_TLS = 2

        @JvmField
        val CREATOR =
            object : Parcelable.Creator<ProfileParcel> {
                override fun createFromParcel(parcel: Parcel) = ProfileParcel(parcel)

                override fun newArray(size: Int) = arrayOfNulls<ProfileParcel>(size)
            }

        fun from(profile: Profile): ProfileParcel {
            val out = profile.outbound
            val reality = out.stream.security as? Security.Reality
            val tls = out.stream.security as? Security.Tls
            val kind =
                when (out.stream.security) {
                    is Security.Reality -> SECURITY_REALITY
                    is Security.Tls -> SECURITY_TLS
                    Security.None -> SECURITY_NONE
                }
            return ProfileParcel(
                id = profile.id,
                name = profile.name,
                address = out.address,
                port = out.port,
                uuid = out.uuid,
                flow = out.flow,
                network = out.stream.network,
                securityKind = kind,
                serverName = reality?.serverName ?: tls?.serverName.orEmpty(),
                publicKey = reality?.publicKey.orEmpty(),
                shortId = reality?.shortId.orEmpty(),
                fingerprint = reality?.fingerprint ?: tls?.fingerprint.orEmpty(),
                spiderX = reality?.spiderX.orEmpty(),
                allowInsecure = tls?.allowInsecure ?: false,
            )
        }
    }
}
