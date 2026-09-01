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
import space.getsub.core.network.HwidProvider
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

    /**
     * Overwrites every stored routing fingerprint, standing in for a row written
     * by a build whose fingerprint algorithm differed from the current one.
     *
     * `decideFor` must still recognise identical content as unchanged: the stored
     * column records what an *older* algorithm computed, and treating it as
     * authoritative makes every profile re-prompt on the first sync after an
     * upgrade — spec §4.3's "one unexplained review sheet per user".
     */
    public fun forgeStoredFingerprints(value: String) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE routing_rule_sets SET fingerprint = ?",
            arrayOf<Any>(value),
        )
    }

    public fun close() {
        database.close()
        root.deleteRecursively()
    }
}
