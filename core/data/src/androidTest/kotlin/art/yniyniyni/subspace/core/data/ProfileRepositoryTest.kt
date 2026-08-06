// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val REEMIT_TIMEOUT_MS = 5_000L

// Backtick names with spaces are avoided here for the same reason
// SubspaceDatabaseTest avoids them: runTest {}'s lambda inherits the
// enclosing test method's JVM name, and this module's minSdk 26 makes D8
// reject spaces in the resulting synthetic class name below DEX 040.
class ProfileRepositoryTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        repository = ProfileRepository(db.profileDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun importingTwoProfilesIntoTheDefaultGroupYieldsTwoRows() =
        runTest {
            val groupId = repository.defaultGroupId()

            repository.import(
                listOf(sampleProfile(address = "198.51.100.1"), sampleProfile(address = "198.51.100.2")),
                groupId,
            )

            repository.observeGroups().first().single().profiles.size shouldBe 2
        }

    @Test
    fun importingTheSameProfileTwiceYieldsOneRow() =
        runTest {
            val groupId = repository.defaultGroupId()

            repository.import(listOf(sampleProfile()), groupId)
            repository.import(listOf(sampleProfile()), groupId)

            repository.observeGroups().first().single().profiles.size shouldBe 1
        }

    // The real bug this pins (element-provenance report): a real subscription document was
    // a top-level array of 8 elements — element [0] an "auto/best server" config with 8
    // `vless` outbounds, elements [1]..[7] one single-server config each. 15 profiles from
    // one document, all reported "Imported 15 of 15", but every one of them hashed identity
    // from the *whole document's* bytes and collapsed into a single upserted row: 1 row, not
    // 15. This constructs that shape directly against Profile (not through
    // SubscriptionParser — XrayJsonTest owns the parser's half of the fix: that array
    // elements each get their own Profile.rawJson, and that several outbounds out of one
    // element still share it) to pin ProfileRepository.import's own half: 15 profiles in,
    // 15 distinct rows out — the 7 that each carry a unique element's bytes via
    // identityHashOfRaw, and the 8 that share element [0]'s bytes via the
    // identityHashOf(outbound) fallback described in import's own KDoc.
    @Test
    fun importingARawDocumentThatYieldsFifteenProfilesStoresFifteenDistinctRows() =
        runTest {
            val groupId = repository.defaultGroupId()
            val autoBestServerElement =
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"198.51.100.0"}]}}]}"""
            val eightSharingOneElement =
                (1..8).map { i ->
                    sampleProfile(address = "198.51.100.$i").copy(rawJson = autoBestServerElement)
                }
            val sevenWithTheirOwnElement =
                (1..7).map { i ->
                    sampleProfile(address = "203.0.113.$i").copy(
                        rawJson =
                        """{"outbounds":[{"protocol":"vless","settings":""" +
                            """{"vnext":[{"address":"203.0.113.$i"}]}}]}""",
                    )
                }

            val storedCount = repository.import(eightSharingOneElement + sevenWithTheirOwnElement, groupId)

            storedCount shouldBe 15
            val stored = repository.observeGroups().first().single().profiles
            stored.size shouldBe 15
            stored.map { db.profileDao().profile(it.id)!!.identityHash }.distinct().size shouldBe 15
        }

    @Test
    fun deletingAGroupRemovesItsProfiles() =
        runTest {
            val groupId = repository.defaultGroupId()
            repository.import(listOf(sampleProfile()), groupId)

            repository.deleteGroup(groupId)

            repository.observeGroups().first() shouldBe emptyList()
        }

    // Deliberately not runTest + advanceUntilIdle() here, despite that being
    // the brief's literal example. Room's Flow re-queries by hopping onto its
    // own real query executor (a genuine withContext to a background thread,
    // not a virtual-time suspension), so a StandardTestDispatcher launch{}
    // collecting it is never woken by advanceUntilIdle() — confirmed by
    // running it: the collector saw zero emissions.
    //
    // Fix round 1 (code review of 11d5435): the first replacement used
    // Flow.first { predicate } from a freshly-subscribed collector racing
    // import() on real executors. first{} is satisfied by *whichever*
    // emission matches the predicate — nothing required a second, later
    // emission after an empty first one, so that version could pass even if
    // invalidation never fired at all, as long as the collector's own
    // initial query happened to run after the write had already committed.
    // withTimeout bounded how long the test waited; it did not bound what
    // the test proved.
    //
    // Fixed with two explicit checkpoints instead of one order-agnostic
    // predicate, using a Channel so each checkpoint is a real
    // synchronization point (send-then-receive), not a hope about ordering:
    //   1. Receive and assert the pre-import emission is empty. Room's Flow
    //      registers its invalidation observer before running its initial
    //      query (it has to, to avoid missing a write that lands mid-query),
    //      so by the time this receive() returns, the collector is
    //      guaranteed to be watching for writes to the profiles table.
    //   2. Only then call import().
    //   3. Receive again and require a *distinct* later emission whose
    //      profiles are non-empty. That emission can only exist if the
    //      write actually invalidated the collector's query — this is the
    //      one property Flow.first{} above did not require.
    // A broken invalidation path makes step 3 hang until the timeout fires
    // instead of silently passing, because no second emission ever arrives.
    // Block body, not `= runBlocking { ... }`: runBlocking<T> returns the
    // block's actual result type, and JUnit rejects a non-void test method —
    // unlike runTest, whose TestResult is a JVM-only typealias for Unit.
    @Test
    fun observeGroupsReEmitsAfterAnImport() {
        runBlocking {
            val groupId = repository.defaultGroupId()

            withTimeout(REEMIT_TIMEOUT_MS) {
                val emissions = Channel<List<ProfileGroup>>(Channel.UNLIMITED)
                val collector = launch { repository.observeGroups().collect { emissions.send(it) } }

                // Checkpoint 1: the collector's own initial query, observed
                // before import() is called at all.
                emissions.receive().single().profiles shouldBe emptyList()

                repository.import(listOf(sampleProfile()), groupId)

                // Checkpoint 2: skip past any emission that doesn't yet carry
                // the imported row (Room may re-signal more than once), but
                // require at least one further, distinct emission to arrive.
                var afterImport = emissions.receive()
                while (afterImport.single().profiles.isEmpty()) {
                    afterImport = emissions.receive()
                }

                afterImport.single().profiles.size shouldBe 1
                collector.cancel()
            }
        }
    }

    // Task 21: EditorViewModel.save() calls ProfileRepository.update() for a
    // TYPED profile. StoredProfile (the type feature/profiles sees) exposes
    // no identityHash field on purpose — it is a :core:data storage concern,
    // not something a caller should read back and compare — so this is the
    // one place §6's "a changed address is a changed identity" claim can
    // actually be checked, against the real ProfileEntity column, not a
    // fake's stand-in.
    @Test
    fun updatingATypedProfileRewritesItsIdentityHash() =
        runTest {
            val groupId = repository.defaultGroupId()
            repository.import(listOf(sampleProfile(address = "198.51.100.1")), groupId)
            val stored = repository.observeGroups().first().single().profiles.single()
            val before = db.profileDao().profile(stored.id)!!.identityHash

            repository.update(
                stored.id,
                stored.name,
                VlessOutbound(
                    address = "198.51.100.99",
                    port = 443,
                    uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                    flow = null,
                    stream = StreamSettings(network = "tcp", security = Security.None),
                ),
            )

            val after = db.profileDao().profile(stored.id)!!
            after.identityHash shouldNotBe before
            after.address shouldBe "198.51.100.99"
        }

    // A RAW_JSON profile's rename() must never touch its stored bytes —
    // rename() only ever copies the `name` column, but this pins that
    // guarantee against a regression that widens it, the same "leaves its
    // bytes untouched" claim EditorViewModelTest makes against a fake.
    @Test
    fun renamingAProfileLeavesEveryOtherColumnUntouched() =
        runTest {
            val groupId = repository.defaultGroupId()
            val rawJson = """{"outbounds":[{"protocol":"vless"}]}"""
            repository.import(listOf(sampleProfile().copy(rawJson = rawJson)), groupId)
            val stored = repository.observeGroups().first().single().profiles.single()
            val before = db.profileDao().profile(stored.id)!!

            repository.rename(stored.id, "Renamed")

            val after = db.profileDao().profile(stored.id)!!
            after.name shouldBe "Renamed"
            after.rawJson shouldBe rawJson
            after.outbound shouldBe before.outbound
            after.identityHash shouldBe before.identityHash
        }

    // Task 21 fix round 1: ProfileEntity's unique (groupId, identityHash) index (§4.2) makes
    // ProfileDao.updateProfile's default ABORT conflict strategy throw
    // SQLiteConstraintException when the recomputed identity collides with a sibling
    // profile already in the group. Before this fix, ProfileRepository.update() let that
    // exception propagate straight out of a suspend function called from
    // EditorViewModel.save()'s viewModelScope.launch with no try/catch — an uncaught
    // exception on that scope crashes the app (ARCHITECTURE.md §10.4). This test exercises
    // the real Room constraint, not a fake standing in for it: the surest evidence the fix
    // actually reaches the exception this class throws.
    @Test
    fun updatingATypedProfileIntoACollidingIdentityReturnsFalseInsteadOfThrowing() =
        runTest {
            val groupId = repository.defaultGroupId()
            repository.import(
                listOf(sampleProfile(address = "198.51.100.1"), sampleProfile(address = "198.51.100.2")),
                groupId,
            )
            val profiles = repository.observeGroups().first().single().profiles
            val first = profiles.first { it.address == "198.51.100.1" }
            val second = profiles.first { it.address == "198.51.100.2" }

            val succeeded =
                repository.update(
                    first.id,
                    first.name,
                    VlessOutbound(
                        address = second.address,
                        port = second.port,
                        uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                        flow = null,
                        stream = StreamSettings(network = "tcp", security = Security.None),
                    ),
                )

            succeeded shouldBe false
            db.profileDao().profile(first.id)!!.address shouldBe "198.51.100.1"
        }

    // Same index, the other write path: move() carries a profile's existing (unedited)
    // identity into a new group that already holds an identical outbound.
    @Test
    fun movingATypedProfileIntoAGroupThatAlreadyHoldsItReturnsFalseInsteadOfThrowing() =
        runTest {
            val groupA = repository.defaultGroupId()
            val groupB = repository.createGroup("Other")
            repository.import(listOf(sampleProfile()), groupA)
            repository.import(listOf(sampleProfile()), groupB)
            val inGroupA = repository.observeGroups().first().first { it.id == groupA }.profiles.single()

            val succeeded = repository.move(inGroupA.id, groupB)

            succeeded shouldBe false
            db.profileDao().profile(inGroupA.id)!!.groupId shouldBe groupA
        }

    // Fix round 2, Important finding 1: ProfileDao.upsertProfile used to overwrite the
    // whole row on a matching-identity re-import, including lastConnectedAt/lastError/
    // createdAt — columns the source never described in the first place. A user who
    // imports a subscription, connects (earning a lastConnectedAt), then re-imports the
    // same subscription a week later to pick up a changed server saw every *other* row's
    // connection history silently reset to "never used". This pins that the device-learned
    // columns survive a re-import while the source-described ones (here: name) still
    // update — the same "leaves untouched" claim renamingAProfileLeavesEveryOtherColumnUntouched
    // makes for rename(), now for import()'s own upsert path.
    @Test
    fun reimportingAProfileWithTheSameIdentityPreservesItsConnectionHistory() =
        runTest {
            val groupId = repository.defaultGroupId()
            repository.import(listOf(sampleProfile(address = "198.51.100.1").copy(name = "Original")), groupId)
            val stored = repository.observeGroups().first().single().profiles.single()
            val createdAt = db.profileDao().profile(stored.id)!!.createdAt

            repository.recordConnected(stored.id, 1_700_000_000_000L)
            repository.recordError(stored.id, "handshakeFailed")
            val afterConnect = db.profileDao().profile(stored.id)!!
            afterConnect.lastConnectedAt shouldBe 1_700_000_000_000L
            afterConnect.lastError shouldBe "handshakeFailed"

            // A week later: same identity, source now describes a new display name.
            val renamedBySource = sampleProfile(address = "198.51.100.1").copy(name = "Renamed by provider")
            repository.import(listOf(renamedBySource), groupId)

            val afterReimport = db.profileDao().profile(stored.id)!!
            afterReimport.id shouldBe stored.id
            afterReimport.name shouldBe "Renamed by provider"
            afterReimport.lastConnectedAt shouldBe 1_700_000_000_000L
            afterReimport.lastError shouldBe "handshakeFailed"
            afterReimport.createdAt shouldBe createdAt
        }

    // Fix round 2, Important finding 2: the outbound-identity fallback import() uses for a
    // fanned-out raw element (several outbounds sharing one element's bytes — see import's
    // own KDoc) used to hash with no kind discriminator, so a RAW_JSON profile produced that
    // way could collide with a TYPED profile of the same server (e.g. the equivalent
    // vless:// link, pasted into the same group later). The upsert then silently overwrote
    // the RAW_JSON row: kind flipped to TYPED and rawJson went to null, discarding the exact
    // bytes §6 exists to preserve. This pins that a TYPED import never touches a RAW_JSON
    // sibling with the same outbound.
    @Test
    fun importingATypedProfileNeverOverwritesARawJsonSiblingWithTheSameOutbound() =
        runTest {
            val groupId = repository.defaultGroupId()
            // Two outbounds in one element forces both resulting RAW_JSON profiles onto the
            // outbound-identity fallback (fanout count 2, not 1) rather than identityHashOfRaw.
            val fanoutElement =
                """{"outbounds":[
                    {"protocol":"vless","settings":{"vnext":[{"address":"198.51.100.1"}]}},
                    {"protocol":"vless","settings":{"vnext":[{"address":"198.51.100.2"}]}}
                ]}"""
            val rawProfiles =
                listOf(
                    sampleProfile(address = "198.51.100.1").copy(rawJson = fanoutElement),
                    sampleProfile(address = "198.51.100.2").copy(rawJson = fanoutElement),
                )
            repository.import(rawProfiles, groupId)
            val rawStored = repository.observeGroups().first().single().profiles.single { it.address == "198.51.100.1" }
            val rawRowBefore = db.profileDao().profile(rawStored.id)!!
            rawRowBefore.kind shouldBe ProfileKind.RAW_JSON.name
            rawRowBefore.rawJson shouldNotBe null

            // A TYPED profile (no rawJson) describing the exact same outbound as the first
            // raw element's first entry — the collision case.
            repository.import(listOf(sampleProfile(address = "198.51.100.1")), groupId)

            val afterProfiles = repository.observeGroups().first().single().profiles
            afterProfiles.size shouldBe 3
            val rawRowAfter = db.profileDao().profile(rawStored.id)!!
            rawRowAfter.kind shouldBe ProfileKind.RAW_JSON.name
            rawRowAfter.rawJson shouldBe rawRowBefore.rawJson
        }

    private fun sampleProfile(address: String = "198.51.100.1") =
        Profile(
            id = "unused",
            name = "Test",
            outbound =
            VlessOutbound(
                address = address,
                port = 443,
                uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.None),
            ),
        )
}
