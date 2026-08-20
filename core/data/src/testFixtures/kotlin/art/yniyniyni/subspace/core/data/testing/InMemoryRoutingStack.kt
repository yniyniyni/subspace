// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.testing

import android.content.Context
import androidx.room.Room
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.RoutingProfileDeletion
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.data.RuleSetAssets
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.network.HwidProvider
import java.io.File

/**
 * A real [RoutingRepository] over an in-memory database.
 *
 * Exists for the same reason [InMemorySubscriptionStack] does (§11): the
 * repository's constructor is `internal` to `:core:data`, so a test in another
 * module cannot build one, and `testFixtures` is the sanctioned way to expose it
 * without widening the production surface.
 */
public class InMemoryRoutingStack(context: Context) {
    private val database =
        Room
            .inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val profiles: ProfileRepository = ProfileRepository(database.profileDao())
    private val root = File(context.cacheDir, "routing-stack-${System.nanoTime()}").apply(File::mkdirs)

    /** The real routing repository under test. */
    public val repository: RoutingRepository = RoutingRepository(database.routingRuleSetDao())
    private val settings = SettingsRepository(database.settingDao(), HwidProvider { "test-hwid" })
    private val deletion =
        RoutingProfileDeletion(database, repository, RuleSetAssets(root), settings, profiles)

    /** A real subscription repository sharing this database, for foreign-key cascade tests. */
    public val subscriptionRepository: SubscriptionRepository =
        SubscriptionRepository(database.subscriptionDao(), profiles, database, deletion)

    public fun close() {
        database.close()
        root.deleteRecursively()
    }
}
