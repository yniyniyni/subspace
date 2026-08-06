// SPDX-License-Identifier: AGPL-3.0-or-later
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
) {
    /** RAW_JSON runs through the typed projection in M3 (§6). */
    public val compatibilityMode: Boolean get() = kind == ProfileKind.RAW_JSON

    /** `:core:xray` emits VLESS only; every other protocol is refused at generation. */
    public val connectable: Boolean get() = protocol == "vless"
}

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
                    profiles = profilesByGroup[group.id].orEmpty().map { it.toStoredProfile() },
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

    /** Creates a new manual group, appended after every existing group. */
    public suspend fun createGroup(name: String): Long {
        val position = dao.observeGroups().first().size
        return dao.insertGroup(
            ProfileGroupEntity(
                name = name,
                source = GROUP_SOURCE_MANUAL,
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
     * Imports parsed profiles into [groupId], one row per profile.
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
     * One residual gap stays open here: two outbounds within one element that differ *only*
     * in `xhttp` transport settings still collide, because [identityHashOf]'s typed
     * projection has no field for `xhttp` to hash — the pre-existing §6 lossy-projection gap,
     * unrelated to this fix and out of scope for it.
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
        val rawJsonFanoutCounts = profiles.mapNotNull { it.rawJson }.groupingBy { it }.eachCount()
        val identityHashes = mutableSetOf<String>()
        profiles.forEachIndexed { index, profile ->
            val rawJson = profile.rawJson
            val kind = if (rawJson == null) ProfileKind.TYPED else ProfileKind.RAW_JSON
            val identityHash =
                if (rawJson != null && rawJsonFanoutCounts.getValue(rawJson) == 1) {
                    identityHashOfRaw(rawJson)
                } else {
                    identityHashOf(profile.outbound)
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
                ),
            )
        }
        return identityHashes.size
    }

    /**
     * Moves a profile to a different group. A no-op (returns `true`) if the profile no
     * longer exists.
     *
     * @return `false` if [ProfileEntity]'s unique `(groupId, identityHash)` index (§4.2)
     *   already holds an identical outbound in [toGroupId] — [ProfileDao.updateProfile]'s
     *   default `ABORT` conflict strategy throws [SQLiteConstraintException] rather than
     *   silently dropping the write, and that exception carries this table's column values
     *   in its message, so it is caught and turned into a plain `false` here rather than
     *   let propagate: nothing above `:core:data` may see a config value inside a
     *   diagnostic (§5.6). `true` otherwise.
     */
    public suspend fun move(
        profileId: Long,
        toGroupId: Long,
    ): Boolean {
        val existing = dao.profile(profileId) ?: return true
        return try {
            dao.updateProfile(existing.copy(groupId = toGroupId))
            true
        } catch (ignored: SQLiteConstraintException) {
            false
        }
    }

    /** Renames a profile. A no-op if the profile no longer exists. */
    public suspend fun rename(
        profileId: Long,
        name: String,
    ) {
        val existing = dao.profile(profileId) ?: return
        dao.updateProfile(existing.copy(name = name))
    }

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
     *   outbound until it matches a sibling's), and [ProfileDao.updateProfile]'s `ABORT`
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
        val existing = dao.profile(profileId) ?: return true
        return try {
            dao.updateProfile(
                existing.copy(
                    name = name,
                    protocol = outbound.protocolName(),
                    address = outbound.address,
                    port = outbound.port,
                    transport = outbound.transportSummary(),
                    outbound = outbound.toJson(),
                    identityHash = identityHashOf(outbound),
                ),
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

    private fun ProfileEntity.toStoredProfile(): StoredProfile =
        StoredProfile(
            id = id,
            groupId = groupId,
            kind = ProfileKind.valueOf(kind),
            name = name,
            protocol = protocol,
            address = address,
            port = port,
            transport = transport,
            outbound = outbound.toOutbound(),
            rawJson = rawJson,
            lastConnectedAt = lastConnectedAt,
            lastError = lastError,
        )
}

/** The canonical protocol name, matching `OutboundDto`'s `@SerialName`s (`:core:data:serialization`). */
private fun Outbound.protocolName(): String =
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
 */
private fun Outbound.transportSummary(): String {
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
