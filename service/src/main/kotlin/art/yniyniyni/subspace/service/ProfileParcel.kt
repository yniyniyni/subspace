// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.os.Parcel
import android.os.Parcelable
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound

/**
 * Carries a [Profile] across the AIDL boundary.
 *
 * Fields are written flat rather than through a general encoding. **This class
 * is deleted in M3**, when profiles come from Room and the service is handed an
 * id instead of a whole profile — so do not invest in generality here.
 *
 * [protocol] is an explicit int discriminant, in the same style as
 * [ConnectionStateParcel]'s `kind` — an int, not a class name, so adding a
 * protocol is a deliberate change on both sides. [uuid] is the generic
 * credential slot: VMess keeps a UUID there, Trojan and Shadowsocks put their
 * password there, and SOCKS puts its username. [credential2] is occupied by
 * **three** different protocols, not two — VMess's `security` cipher string
 * (`auto`, `aes-128-gcm`, …), Shadowsocks' method, and SOCKS' password. Do not
 * assume it is idle for VMess. [alterId] carries only VMess's legacy AlterID
 * and is `0` — meaningless, not "unset" — for every other protocol.
 *
 * §5.6: this parcel carries the UUID, password, and REALITY key in the clear.
 * That is acceptable — it travels only over a same-UID binder to our own `:bg`
 * process, which needs the values to build the config. It must never be
 * logged, which is why this is a plain class with a hand-written [toString]: a
 * `data class` would generate one that prints every secret, and `toString`
 * reaches crash output without anyone choosing to log it.
 */
@Suppress("LongParameterList")
// A parcel is a flat wire format; the parameter count is the field count, and
// grouping them into sub-objects would mean more Parcelable plumbing for a class
// that M3 deletes. The constructor is never called by hand — use [from].
public class ProfileParcel(
    val protocol: Int,
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val credential2: String,
    val alterId: Int,
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
        protocol = parcel.readInt(),
        id = parcel.readString().orEmpty(),
        name = parcel.readString().orEmpty(),
        address = parcel.readString().orEmpty(),
        port = parcel.readInt(),
        uuid = parcel.readString().orEmpty(),
        credential2 = parcel.readString().orEmpty(),
        alterId = parcel.readInt(),
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
        dest.writeInt(protocol)
        dest.writeString(id)
        dest.writeString(name)
        dest.writeString(address)
        dest.writeInt(port)
        dest.writeString(uuid)
        dest.writeString(credential2)
        dest.writeInt(alterId)
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

    /**
     * Rebuilds the [Profile], or `null` if this parcel carries a discriminant
     * this process cannot name.
     *
     * Nullable rather than degrading to a default. [ConnectionStateParcel]'s
     * unknown `kind` degrades to `Disconnected` because the worst case there is
     * a UI showing a state that is merely wrong. Here the worst case is a
     * *tunnel*: an unknown security kind read as [Security.None] silently
     * strips REALITY and produces a connection in the clear that looks to the
     * user exactly like a dead server, and an unknown protocol read as VLESS
     * dials the wrong protocol at a real address. §10.4's rule applies —
     * fail loudly and specifically rather than connect to something the user
     * did not choose.
     *
     * Both branches are currently unreachable: the same APK writes and reads
     * this parcel and it is never persisted. This is defensive, and cheap
     * because of it.
     */
    fun toProfile(): Profile? {
        val security = security() ?: return null
        val stream = StreamSettings(network = network, security = security)
        val out = outbound(stream) ?: return null
        return Profile(id = id, name = name, outbound = out)
    }

    /** Rebuilds the right [Outbound] from [protocol], or null if it names none. */
    private fun outbound(stream: StreamSettings): Outbound? =
        when (protocol) {
            PROTOCOL_VLESS ->
                VlessOutbound(
                    address = address,
                    port = port,
                    uuid = uuid,
                    flow = flow,
                    stream = stream,
                )

            PROTOCOL_VMESS ->
                VmessOutbound(
                    address = address,
                    port = port,
                    uuid = uuid,
                    alterId = alterId,
                    security = credential2,
                    stream = stream,
                )

            PROTOCOL_TROJAN ->
                TrojanOutbound(
                    address = address,
                    port = port,
                    password = uuid,
                    stream = stream,
                )

            PROTOCOL_SHADOWSOCKS ->
                ShadowsocksOutbound(
                    address = address,
                    port = port,
                    method = credential2,
                    password = uuid,
                )

            PROTOCOL_SOCKS ->
                SocksOutbound(
                    address = address,
                    port = port,
                    username = uuid.ifEmpty { null },
                    password = credential2.ifEmpty { null },
                )

            else -> null
        }

    /** Rebuilds the [Security], or null if [securityKind] names none. */
    private fun security(): Security? =
        when (securityKind) {
            SECURITY_NONE -> Security.None

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

            else -> null
        }

    companion object {
        const val PROTOCOL_VLESS = 0
        const val PROTOCOL_VMESS = 1
        const val PROTOCOL_TROJAN = 2
        const val PROTOCOL_SHADOWSOCKS = 3
        const val PROTOCOL_SOCKS = 4

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
            val fields = fieldsFor(out)
            val security = fields.stream?.security ?: Security.None
            val reality = security as? Security.Reality
            val tls = security as? Security.Tls
            val kind =
                when (security) {
                    is Security.Reality -> SECURITY_REALITY
                    is Security.Tls -> SECURITY_TLS
                    Security.None -> SECURITY_NONE
                }
            return ProfileParcel(
                protocol = fields.protocol,
                id = profile.id,
                name = profile.name,
                address = out.address,
                port = out.port,
                uuid = fields.uuid,
                credential2 = fields.credential2,
                alterId = fields.alterId,
                flow = fields.flow,
                network = fields.stream?.network.orEmpty(),
                securityKind = kind,
                serverName = reality?.serverName ?: tls?.serverName.orEmpty(),
                publicKey = reality?.publicKey.orEmpty(),
                shortId = reality?.shortId.orEmpty(),
                fingerprint = reality?.fingerprint ?: tls?.fingerprint.orEmpty(),
                spiderX = reality?.spiderX.orEmpty(),
                allowInsecure = tls?.allowInsecure ?: false,
            )
        }

        /**
         * The per-protocol slice of [from]'s work, split out so `from` itself
         * stays a flat field assembly rather than one large `when`-per-field
         * function.
         */
        private fun fieldsFor(out: Outbound): OutboundFields =
            when (out) {
                is VlessOutbound ->
                    OutboundFields(PROTOCOL_VLESS, out.uuid, "", 0, out.flow, out.stream)

                is VmessOutbound ->
                    OutboundFields(PROTOCOL_VMESS, out.uuid, out.security, out.alterId, null, out.stream)

                is TrojanOutbound ->
                    OutboundFields(PROTOCOL_TROJAN, out.password, "", 0, null, out.stream)

                is ShadowsocksOutbound ->
                    OutboundFields(PROTOCOL_SHADOWSOCKS, out.password, out.method, 0, null, null)

                is SocksOutbound ->
                    OutboundFields(PROTOCOL_SOCKS, out.username.orEmpty(), out.password.orEmpty(), 0, null, null)
            }
    }

    /**
     * The flat fields [fieldsFor] extracts from one [Outbound], before security
     * is folded in. [alterId] is meaningful only for [VmessOutbound] — every
     * other protocol passes `0`, which [outbound] never reads back for them.
     */
    private data class OutboundFields(
        val protocol: Int,
        val uuid: String,
        val credential2: String,
        val alterId: Int,
        val flow: String?,
        val stream: StreamSettings?,
    )
}
