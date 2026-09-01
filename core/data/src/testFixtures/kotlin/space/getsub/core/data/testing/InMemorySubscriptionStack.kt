// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.testing

import android.content.Context
import androidx.room.Room
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.RoutingProfileDeletion
import space.getsub.core.data.RoutingRepository
import space.getsub.core.data.RuleSetAssets
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.data.db.SubspaceDatabase
import space.getsub.core.data.sync.SubscriptionSyncer
import space.getsub.core.model.TunnelProxyLocator
import space.getsub.core.network.FetchOutcome
import space.getsub.core.network.HwidProvider
import space.getsub.core.network.SubscriptionSource
import java.io.File

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
 *
 * @param onFetch runs before every fetch returns its fixed success — a plain suspend hook (not a
 *   [SubscriptionSource]/`FetchOutcome`) so a caller in another module can control fetch *timing*
 *   (e.g. hang with `delay()` to make a sync cancellable mid-flight) without needing `:core:network`
 *   types on its own classpath, which ARCHITECTURE.md §4 reserves to `:core:data`.
 */
public class InMemorySubscriptionStack(
    context: Context,
    private val onFetch: suspend () -> Unit = {},
) : AutoCloseable {
    private val db: SubspaceDatabase = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
    private val profiles = ProfileRepository(db.profileDao())
    private val settings = SettingsRepository(db.settingDao(), HwidProvider { "test-hwid" })
    private val root = File(context.cacheDir, "subscription-stack-${System.nanoTime()}").apply(File::mkdirs)
    private val routing = RoutingRepository(db.routingRuleSetDao())
    private val deletion = RoutingProfileDeletion(db, routing, RuleSetAssets(root), settings, profiles)

    public val repository: SubscriptionRepository =
        SubscriptionRepository(db.subscriptionDao(), profiles, db, deletion)
    public val syncer: SubscriptionSyncer =
        SubscriptionSyncer(
            db.subscriptionDao(),
            repository,
            settings,
            SubscriptionSource {
                onFetch()
                FetchOutcome.Success("", emptyMap())
            },
            // No tunnel in this fixture; every fetch goes direct, same as
            // production with nothing bound.
            TunnelProxyLocator { null },
        )

    override fun close() {
        db.close()
        root.deleteRecursively()
    }
}
