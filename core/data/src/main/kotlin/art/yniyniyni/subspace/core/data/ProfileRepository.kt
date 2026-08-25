// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import android.database.sqlite.SQLiteConstraintException
import art.yniyniyni.subspace.core.data.db.ProfileDao
import art.yniyniyni.subspace.core.data.db.ProfileEntity
import art.yniyniyni.subspace.core.data.db.ProfileGroupEntity
import art.yniyniyni.subspace.core.data.serialization.identityHashOf
import art.yniyniyni.subspace.core.data.serialization.identityHashOfRaw
import art.yniyniyni.subspace.core.data.serialization.toJson
import art.yniyniyni.subspace.core.data.serialization.toOutbound
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import art.yniyniyni.subspace.core.parser.PassthroughAnalysis
import art.yniyniyni.subspace.core.parser.PassthroughRejection
import art.yniyniyni.subspace.core.parser.analysePassthrough
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_GROUP_NAME = "Local configs"
private const val GROUP_SOURCE_MANUAL = "MANUAL"

/** `TYPED` decodes from a serialized [Outbound]; `RAW_JSON` additionally keeps the pasted bytes (§6). */
public enum class ProfileKind { TYPED, RAW_JSON }

/**
 * One stored server, mapped out of [ProfileEntity] for consumers outside `:core:data`.
 *
 * @property outbound The decoded config, or `null` if the persisted JSON failed to parse.
 *   `String.toOutbound()` never throws (§7), so a corrupt row surfaces here as a null field
 *   rather than taking the rest of the list down with it — callers render that as a broken
 *   row, never as a silently vanished one.
 */
public data class StoredProfile(
    val id: Long,
    val groupId: Long,
    val kind: ProfileKind,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val transport: String,
    val outbound: Outbound?,
    val rawJson: String?,
    val lastConnectedAt: Long?,
    val lastError: String?,
    /**
     * Spec D4's "kept and flagged" marker (Task 11 review fix, Important 4): non-null when this
     * row's provider stopped offering it while it was the active profile, so it was kept rather
     * than deleted. Part 2's UI is the intended reader — "no longer offered by this provider,
     * since <this timestamp>" — nothing in `:core:data` reads it back.
     */
    val droppedFromSubscriptionAt: Long? = null,
    /**
     * Why this row cannot run as written, or null when it can.
     *
     * Structural only — decided by `analysePassthrough` at import. Whether the
     * core accepts the config is a separate gate applied by `:service`.
     */
    val passthroughRejection: PassthroughRejection? = null,
) {
    // §5.6: this is the type that crosses out of :core:data carrying a server address, an
    // Outbound (UUID and REALITY key material) and the raw config, so the redaction has to hold
    // here as well as on the entity it was mapped from — exactly the reasoning StoredSubscription
    // already applies to its URL.
    override fun toString(): String =
        "StoredProfile(id=$id, groupId=$groupId, kind=$kind, name=$name, protocol=$protocol, " +
            "address=<redacted>, port=$port, transport=$transport, outbound=<redacted>, " +
            "rawJson=<redacted>, lastConnectedAt=$lastConnectedAt, lastError=$lastError, " +
            "droppedFromSubscriptionAt=$droppedFromSubscriptionAt, " +
            "passthroughRejection=$passthroughRejection)"

    /** RAW_JSON runs through the typed projection in M3 (§6). */
    public val compatibilityMode: Boolean get() = kind == ProfileKind.RAW_JSON

    /**
     * Whether `:core:xray` can generate a working config for this row.
     *
     * VLESS only — [art.yniyniyni.subspace.core.xray.XrayConfigGenerator] refuses the other
     * four protocols outright — and, within VLESS, only a transport that generator emits
     * ([CONNECTABLE_NETWORKS]). A row whose [outbound] failed to decode is not connectable
     * either: there is no config to generate from `null`.
     *
     * This predicate has been wrong in both directions, so the reasoning is worth keeping.
     * It first checked only `protocol == "vless"`, which promised a working config for a
     * transport the generator wrote no settings for. It was then narrowed to
     * `network == "tcp"`, which over-corrected: omitting a transport's settings object does
     * not make Xray fail, it makes Xray use that transport's registered defaults (verified
     * in v26.7.11 `StreamConfig.Build` — a nil settings pointer skips the block), so an
     * xhttp server on a default path connected fine and was nonetheless labelled "not
     * supported by this build yet". The generator now emits `wsSettings`, `grpcSettings` and
     * `xhttpSettings` from [art.yniyniyni.subspace.core.model.StreamSettings.transport], and
     * this is the set of networks it handles.
     *
     * An allow-list, deliberately, not a deny-list: a transport added to the model without a
     * matching branch in the generator must default to *not* connectable. The inverse would
     * promise a config this build cannot write, which is the first version of this bug.
     */
    public val connectable: Boolean
        get() = (outbound as? VlessOutbound)?.stream?.network?.lowercase() in CONNECTABLE_NETWORKS

    /**
     * Whether this row's own bytes reach the core, rather than a typed projection.
     *
     * `TYPED` rows generate from the typed form permanently (§6), so this is
     * false for them however well-formed they are.
     */
    public val runsAsWritten: Boolean
        get() = kind == ProfileKind.RAW_JSON && passthroughRejection == null
}

/**
 * The `streamSettings.network` values [art.yniyniyni.subspace.core.xray.XrayConfigGenerator]
 * can emit a transport block for, as Xray-core v26.7.11 spells them
 * (`infra/conf/transport_method.go`, `TransportProtocol.Build`) — including its aliases,
 * since it lowercases and accepts either name of a pair, and a stored profile keeps whatever
 * its source wrote.
 *
 * `kcp`/`mkcp`, `httpupgrade` and `hysteria` are real transports Xray supports and this
 * generator does not, so they are absent on purpose. Hysteria2 generation is M8's, and §6
 * warns its config shape is unlike every other protocol's.
 *
 * Lives in `:core:data` rather than `:core:xray` because `:core:data` cannot depend on
 * `:core:xray` (§4 module rules) — [StoredProfile] is the type the UI reads. It is therefore
 * a copy of a fact owned elsewhere, and `XrayConfigGeneratorTest` pins the emitting side of
 * it; keep the two in step.
 */
private val CONNECTABLE_NETWORKS =
    setOf("tcp", "raw", "ws", "websocket", "grpc", "xhttp", "splithttp")

/** A folder of profiles, in display order. */
public data class ProfileGroup(val id: Long, val name: String, val profiles: List<StoredProfile>)

/**
 * The repository API `:feature:*` modules and `:bg` use to reach the profile tables.
 *
 * Owns the *Local configs* default group (spec D3): nothing creates it up front, it is
 * created the first time [defaultGroupId] is called and reused after that.
 *
 * `:bg` only ever calls [recordConnected] and [recordError] (spec D4) — every other member
 * here is a `:main`-only write path (import, group and profile management).
 */
@Suppress("TooManyFunctions") // The public contract Part 2 depends on — see ProfileDao's KDoc for the same call.
@Singleton
public class ProfileRepository
@Inject
internal constructor(
    private val dao: ProfileDao,
) {
    // Guards create-or-find of the default group. defaultGroupId() is a
    // read (does "Local configs" exist?) followed by a conditional write
    // (insert it if not), and ProfileDao exposes no atomic find-or-create
    // for groups the way upsertProfile does for profiles — see its KDoc
    // for why a naive read-then-write here would be exactly the race that
    // class was written to avoid. This mutex serializes callers within
    // this process instead of re-deriving that fix. It does not need to
    // cover :bg, which per spec D4 never calls this method.
    private val defaultGroupMutex = Mutex()

    /**
     * Every group, in display order, each carrying its own profiles in display order.
     *
     * [query] and [protocol] filter the profiles inside each group — matched in SQL over
     * [ProfileEntity]'s shadow columns via [ProfileDao.observeProfiles], not by deserializing
     * every row in memory, per that entity's own KDoc. Groups themselves are never filtered
     * out by this: a group with zero matches still appears, with an empty profile list — the
     * Servers screen (Task 18) decides how to render that, not this layer. Defaults keep every
     * existing caller (e.g. `:feature:home`'s `ActiveProfileSource`) unfiltered and unchanged.
     */
    public fun observeGroups(
        query: String = "",
        protocol: String? = null,
    ): Flow<List<ProfileGroup>> =
        combine(dao.observeGroups(), dao.observeProfiles(query, protocol)) { groups, profiles ->
            val profilesByGroup = profiles.groupBy { it.groupId }
            groups.map { group ->
                ProfileGroup(
                    id = group.id,
                    name = group.name,
                    profiles = profilesByGroup[group.id].orEmpty().mapNotNull { it.toStoredProfile() },
                )
            }
        }

    /** A single profile by its primary key, or null if it no longer exists. */
    public suspend fun profile(id: Long): StoredProfile? = dao.profile(id)?.toStoredProfile()

    /** The id of the *Local configs* group, creating it on first call (spec D3). */
    public suspend fun defaultGroupId(): Long =
        defaultGroupMutex.withLock {
            dao.observeGroups().first().firstOrNull { it.name == DEFAULT_GROUP_NAME }?.id
                ?: dao.insertGroup(
                    ProfileGroupEntity(
                        name = DEFAULT_GROUP_NAME,
                        source = GROUP_SOURCE_MANUAL,
                        position = 0,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
        }

    /**
     * Creates a new group, appended after every existing group.
     *
     * [source] defaults to `"MANUAL"` for every pre-existing caller.
     * [SubscriptionRepository.add] (Task 10) passes `"SUBSCRIPTION"` instead,
     * reusing this same insert rather than duplicating it — the M4 seam
     * [ProfileGroupEntity]'s KDoc describes.
     */
    public suspend fun createGroup(
        name: String,
        source: String = GROUP_SOURCE_MANUAL,
    ): Long {
        val position = dao.observeGroups().first().size
        return dao.insertGroup(
            ProfileGroupEntity(
                name = name,
                source = source,
                position = position,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Renames a group in place. A no-op if the group no longer exists. */
    public suspend fun renameGroup(
        id: Long,
        name: String,
    ) {
        val existing = dao.observeGroups().first().firstOrNull { it.id == id } ?: return
        dao.updateGroup(existing.copy(name = name))
    }

    /** Deletes a group. `ON DELETE CASCADE` removes every profile in it with it. */
    public suspend fun deleteGroup(id: Long): Unit = dao.deleteGroup(id)

    /**
     * Imports parsed profiles into [groupId], one row per profile — except a balancer element's
     * profiles, which collapse to one (see below).
     *
     * Every `RAW_JSON` profile's element bytes are run through
     * [art.yniyniyni.subspace.core.parser.analysePassthrough] once per distinct
     * [Profile.rawJson] value, and the verdict is stored as
     * [ProfileEntity.passthroughRejection] — `null` means the row is eligible to run
     * as written (design §6). When that analysis reports
     * [art.yniyniyni.subspace.core.parser.PassthroughAnalysis.isBalancer], the element's
     * outbounds are balancer members, not several servers the user picks between
     * (research §5b: the target panel's own "auto | best server" entry is exactly this
     * shape), so only the *first* profile carrying that element's bytes is kept — the
     * rest are dropped before the upsert loop below ever sees them. This is a storage
     * decision, not a parser one: `:core:parser`'s per-destination fan-out is still
     * correct output for a `TYPED` re-import of the same bytes.
     *
     * [Profile.rawJson] carries provenance per-profile now, not per-batch (element-provenance
     * fix, device-fixes finding part 2): a profile parsed out of a hand-written Xray config
     * carries the bytes of the *element* that produced it (`:core:parser`'s `XrayJson.kt` —
     * the whole document for a bare top-level object, one array entry for a top-level array
     * of configs), and is stored as [ProfileKind.RAW_JSON]. Every other source (share links,
     * base64 lists, Clash YAML) leaves [Profile.rawJson] null, and those profiles are
     * [ProfileKind.TYPED].
     *
     * Identity: [identityHashOfRaw] hashes those element bytes — correct exactly when the
     * element produced *one* profile, since then its bytes distinguish it from every other
     * element. An element that fans out into several profiles (one Xray config with several
     * `vless` outbounds — the shape that caused this fix: 8 outbounds in one array element,
     * all upserting the same row because they all hashed the same shared bytes) still shares
     * those bytes across every profile it produced, so those specific profiles fall back to
     * [identityHashOf] the outbound instead — exactly what [ProfileKind.TYPED] already does.
     * [rawJsonFanoutCounts] finds that case by counting, within *this* call's own profiles,
     * how many share each non-null [Profile.rawJson] value; a count of one keeps the raw hash,
     * anything higher falls back. (Two elements that happen to be byte-identical would also
     * fall into the "higher" branch — harmless: their raw hashes would already have collided,
     * so falling back to outbound identity can only ever separate profiles the raw hash
     * would have wrongly merged, never merge ones it would have kept apart.)
     *
     * The `xhttp` case this KDoc used to list as a residual gap — two outbounds in one
     * element differing only in their `xhttp` settings, colliding because the typed
     * projection had no field for them — is closed:
     * [art.yniyniyni.subspace.core.model.TransportOptions.Xhttp] now carries path, host and
     * mode, and [identityHashOf] hashes the whole outbound. §6's lossy-projection gap is
     * narrower than it was, not gone: an outbound differing only in a field still outside
     * the model (`XHTTPObject`'s padding and session-id options, `tcpSettings` header
     * obfuscation) collides the same way.
     *
     * Writes go through [ProfileDao.upsertProfile]: a profile whose identity already exists
     * in [groupId] overwrites that row instead of duplicating it. A provider changing a
     * server's SNI is a different identity and therefore a new row, not an update — see
     * [identityHashOf]'s KDoc; that is intended for M3, subscription sync at M4 keys on
     * something else.
     *
     * @return how many distinct rows this call actually wrote — the number of distinct
     *   identities among [profiles], **not** `profiles.size`. `import`'s caller
     *   ([art.yniyniyni.subspace.feature.profiles.add.ImportViewModel]) reports this to the
     *   user rather than the parsed count (§10.1: "15 of 15" must mean 15 rows landed, not
     *   15 profiles parsed and 1 actually stored).
     */
    public suspend fun import(
        profiles: List<Profile>,
        groupId: Long,
    ): Int {
        val now = System.currentTimeMillis()
        val analysisCache = mutableMapOf<String, PassthroughAnalysis>()
        fun analysisFor(rawJson: String) = analysisCache.getOrPut(rawJson) { analysePassthrough(rawJson) }

        // A balancer element (research §5b, the target panel's auto entry) mints one profile per
        // `vless` destination even though those destinations are members of one logical server,
        // not several a user picks between. Keep only the first profile carrying that element's
        // bytes; the rest would otherwise land as extra rows all pointing at the same document.
        val seenBalancerRawJson = mutableSetOf<String>()
        val eligibleProfiles =
            profiles.filter { profile ->
                val rawJson = profile.rawJson
                rawJson == null || !analysisFor(rawJson).isBalancer || seenBalancerRawJson.add(rawJson)
            }

        val rawJsonFanoutCounts = eligibleProfiles.mapNotNull { it.rawJson }.groupingBy { it }.eachCount()
        val identityHashes = mutableSetOf<String>()
        eligibleProfiles.forEachIndexed { index, profile ->
            val rawJson = profile.rawJson
            val kind = if (rawJson == null) ProfileKind.TYPED else ProfileKind.RAW_JSON
            val identityHash =
                if (rawJson != null && rawJsonFanoutCounts.getValue(rawJson) == 1) {
                    identityHashOfRaw(rawJson)
                } else {
                    identityHashOf(profile.outbound, kind)
                }
            identityHashes += identityHash
            dao.upsertProfile(
                ProfileEntity(
                    groupId = groupId,
                    kind = kind.name,
                    identityHash = identityHash,
                    name = profile.name,
                    protocol = profile.outbound.protocolName(),
                    address = profile.outbound.address,
                    port = profile.outbound.port,
                    transport = profile.outbound.transportSummary(),
                    outbound = profile.outbound.toJson(),
                    rawJson = rawJson,
                    position = index,
                    lastConnectedAt = null,
                    lastError = null,
                    createdAt = now,
                    passthroughRejection = rawJson?.let { analysisFor(it).rejection?.name },
                ),
            )
        }
        return identityHashes.size
    }

    /**
     * Overwrites [profileId]'s row with the core's own verdict — a thin, `id`-keyed wrapper over
     * [ProfileDao.setPassthroughRejection].
     *
     * Review fix (Important 1): an earlier version of this method took `(groupId, rawJson)` and
     * re-derived the row via `identityHashOfRaw(rawJson)`, on the claim that a caller only ever
     * has the config's text in hand, not the id. That claim was false — `:feature:profiles`'
     * `ProfileSource` (Task 8's only caller) already iterates [StoredProfile]s pulled from
     * [observeGroups], which carry [StoredProfile.id] directly — and the re-derivation was also
     * provably wrong on its own terms: [import]'s own KDoc documents two byte-identical raw
     * elements both falling to the outbound-identity fallback while each still analyses to a
     * null structural rejection, a case `identityHashOfRaw` cannot distinguish. Taking the id
     * directly removes both problems: no re-derivation, and no config material in the signature.
     *
     * [reason] is a [art.yniyniyni.subspace.core.parser.PassthroughRejection]'s `name`, not the
     * enum itself, matching [ProfileDao.setPassthroughRejection]'s own contract — see that
     * method's KDoc for why the DAO layer stays off `:core:parser`'s types.
     */
    public suspend fun setPassthroughRejection(
        profileId: Long,
        reason: String?,
    ): Unit = dao.setPassthroughRejection(profileId, reason)

    /**
     * Moves a profile to a different group. A no-op (returns `true`) if the profile no
     * longer exists.
     *
     * @return `false` if [ProfileEntity]'s unique `(groupId, identityHash)` index (§4.2)
     *   already holds an identical outbound in [toGroupId] — [ProfileDao.moveProfile]'s
     *   default `ABORT` conflict strategy throws [SQLiteConstraintException] rather than
     *   silently dropping the write, and that exception carries this table's column values
     *   in its message, so it is caught and turned into a plain `false` here rather than
     *   let propagate: nothing above `:core:data` may see a config value inside a
     *   diagnostic (§5.6). `true` otherwise.
     *
     * **Undocumented until Task 11 review round 2, recorded now:** nothing stops [toGroupId]
     * from being a subscription-sourced group. A hand-imported profile moved there keeps
     * `subscriptionKey = null`, so `SubscriptionDao.subscriptionProfiles` — the query
     * `SubscriptionSyncer.reconcile` diffs a response against — never sees it: it is not
     * matched, not deleted, not protected the way a kept-active row's `identityHash` is. A
     * later sync's insert or update can still collide with its `identityHash`, in which case
     * `SubscriptionSyncer` catches the resulting `SQLiteConstraintException` and returns
     * `SyncResult.ReconciliationConflict` — the sync fails cleanly rather than crashing — but
     * the moved-in row itself is simply outside spec §6.5's reconciliation for as long as it
     * stays in that group.
     */
    public suspend fun move(
        profileId: Long,
        toGroupId: Long,
    ): Boolean {
        return try {
            dao.moveProfile(profileId, toGroupId)
            true
        } catch (ignored: SQLiteConstraintException) {
            false
        }
    }

    /** Renames a profile. A no-op if the profile no longer exists. */
    public suspend fun rename(
        profileId: Long,
        name: String,
    ): Unit = dao.renameProfile(profileId, name)

    /**
     * Rewrites a `TYPED` profile's name and whole [outbound] in place, recomputing every
     * shadow column and [identityHashOf] from the new outbound — §6: identity covers the
     * whole outbound, so a changed address, port, credential, transport or security is a
     * changed identity, not merely a changed display value. A no-op if the profile no
     * longer exists.
     *
     * Never call this for a `RAW_JSON` profile. Re-deriving stored bytes from a decoded
     * [Outbound] is exactly the lossy round trip §6 exists to prevent — that profile's only
     * editable field is its name, via [rename]. The editor (`:feature:profiles`) is
     * responsible for routing to the right one of the two based on [StoredProfile.kind];
     * this repository does not re-check it, the same trust boundary [import]'s [rawJson]
     * parameter already relies on its caller for.
     *
     * @return `false` if the recomputed [identityHash] collides with another profile
     *   already in this profile's group — [ProfileEntity]'s unique `(groupId,
     *   identityHash)` index (§4.2) makes that a real possibility (editing a profile's
     *   outbound until it matches a sibling's), and [ProfileDao.updateTypedProfile]'s `ABORT`
     *   conflict strategy throws [SQLiteConstraintException] rather than silently
     *   dropping the write. That exception can quote this table's column values, so it is
     *   caught here and turned into a plain `false` rather than let propagate past
     *   `:core:data` (§5.6) — see [move] for the same treatment of the same index. `true`
     *   otherwise, including the no-op case where the profile no longer exists.
     */
    public suspend fun update(
        profileId: Long,
        name: String,
        outbound: Outbound,
    ): Boolean {
        return try {
            dao.updateTypedProfile(
                id = profileId,
                name = name,
                protocol = outbound.protocolName(),
                address = outbound.address,
                port = outbound.port,
                transport = outbound.transportSummary(),
                outbound = outbound.toJson(),
                identityHash = identityHashOf(outbound, ProfileKind.TYPED),
            )
            true
        } catch (ignored: SQLiteConstraintException) {
            false
        }
    }

    /** Deletes a single profile. */
    public suspend fun delete(profileId: Long): Unit = dao.deleteProfile(profileId)

    /**
     * Records a successful connection and clears any prior error.
     *
     * One of the two writes `:bg` performs on this table (spec D4). Nothing else in the
     * app calls this.
     */
    public suspend fun recordConnected(
        profileId: Long,
        atEpochMillis: Long,
    ): Unit = dao.recordConnected(profileId, atEpochMillis)

    /**
     * Records a connection failure.
     *
     * [redactedDetail] must never contain config contents (§5.6) — callers pass a category,
     * not the underlying exception message. The other of the two writes `:bg` performs on
     * this table (spec D4). Nothing else in the app calls this.
     */
    public suspend fun recordError(
        profileId: Long,
        redactedDetail: String,
    ): Unit = dao.recordError(profileId, redactedDetail)

    private fun ProfileEntity.toStoredProfile(): StoredProfile? {
        // Decode the discriminator first. A row with an unknown kind is corrupt, and returning
        // here ensures no other persisted column — including outbound JSON — is parsed or guessed.
        val decodedKind = decodeProfileKind(kind) ?: return null
        return StoredProfile(
            id = id,
            groupId = groupId,
            kind = decodedKind,
            name = name,
            protocol = protocol,
            address = address,
            port = port,
            transport = transport,
            outbound = outbound.toOutbound(),
            rawJson = rawJson,
            lastConnectedAt = lastConnectedAt,
            lastError = lastError,
            droppedFromSubscriptionAt = droppedFromSubscriptionAt,
            passthroughRejection = passthroughRejection?.let(::decodePassthroughRejection),
        )
    }
}

private fun decodeProfileKind(value: String): ProfileKind? =
    ProfileKind.entries.firstOrNull { it.name == value }

/**
 * Decodes a stored [PassthroughRejection] name back into the enum, or null for
 * anything that does not match — a null column already means eligible, and an
 * unrecognised name (a future rejection member reintroduced against an older
 * install) degrades the same way rather than crashing the Servers screen.
 */
private fun decodePassthroughRejection(value: String): PassthroughRejection? =
    PassthroughRejection.entries.firstOrNull { it.name == value }

/**
 * The canonical protocol name, matching `OutboundDto`'s `@SerialName`s (`:core:data:serialization`).
 *
 * `internal`, not `private`: `SubscriptionSyncer` (Task 11) builds [ProfileEntity] rows from
 * parsed [Profile]s the same way [import] does and reuses this rather than re-deriving it.
 */
internal fun Outbound.protocolName(): String =
    when (this) {
        is VlessOutbound -> "vless"
        is VmessOutbound -> "vmess"
        is TrojanOutbound -> "trojan"
        is ShadowsocksOutbound -> "shadowsocks"
        is SocksOutbound -> "socks"
    }

/**
 * The dot-separated display string the Servers screen shows, e.g. `ws · tls · 8443` or
 * `tcp · reality · 443`.
 *
 * A **display** value only: it exists so search matches what the user can see, and nothing
 * ever reads it back into a config. [ShadowsocksOutbound] and [SocksOutbound] carry no
 * [art.yniyniyni.subspace.core.model.StreamSettings] at all — they summarise as plain `tcp`.
 *
 * `internal`, not `private`: shared with `SubscriptionSyncer` for the same reason as
 * [protocolName].
 */
internal fun Outbound.transportSummary(): String {
    val stream =
        when (this) {
            is VlessOutbound -> stream
            is VmessOutbound -> stream
            is TrojanOutbound -> stream
            is ShadowsocksOutbound -> null
            is SocksOutbound -> null
        }
    val parts = mutableListOf(stream?.network ?: "tcp")
    when (stream?.security) {
        is Security.Reality -> parts += "reality"
        is Security.Tls -> parts += "tls"
        is Security.None, null -> Unit
    }
    parts += port.toString()
    return parts.joinToString(" · ")
}
