// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.testing

import android.content.Context
import androidx.room.Room
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.data.sync.SubscriptionSyncer
import art.yniyniyni.subspace.core.network.FetchOutcome
import art.yniyniyni.subspace.core.network.SubscriptionSource

/**
 * A [SubscriptionRepository] + [SubscriptionSyncer] pair backed by an in-memory Room database, for
 * instrumented tests in modules other than `:core:data`.
 *
 * [SubscriptionRepository] and [SubscriptionSyncer]'s constructors are `internal` to `:core:data` on
 * purpose — production code reaches them only through Hilt. A test in another module (Task 12's
 * `SubscriptionRefreshWorkerTest` in `:app`) legitimately needs real instances, not fakes, the same
 * way `:core:data`'s own `SubscriptionSyncerTest` does — but it cannot call those constructors
 * itself (different Kotlin compilation module), and it must not depend on `:core:network` directly
 * to supply a [SubscriptionSource] (ARCHITECTURE.md §4: only `:core:data` may depend on
 * `:core:network`). This `testFixtures` source set is compiled as part of `:core:data`, so it has
 * the same access `SubscriptionSyncerTest` does, and exposes only the public
 * [SubscriptionRepository]/[SubscriptionSyncer] types outward.
 *
 * The stubbed [SubscriptionSource] always returns an empty, header-less success — this fixture is
 * for tests that never call [SubscriptionSyncer.sync] (or exercise no-subscription paths where it is
 * never reached), not for exercising fetch behaviour itself.
 */
public class InMemorySubscriptionStack(context: Context) : AutoCloseable {
    private val db: SubspaceDatabase = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
    private val profiles = ProfileRepository(db.profileDao())

    public val repository: SubscriptionRepository = SubscriptionRepository(db.subscriptionDao(), profiles)
    public val syncer: SubscriptionSyncer =
        SubscriptionSyncer(
            db.subscriptionDao(),
            repository,
            SubscriptionSource { FetchOutcome.Success("", emptyMap()) },
        )

    override fun close() {
        db.close()
    }
}
