// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.testing

import android.content.Context
import androidx.room.Room
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase

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

    public val repository: RoutingRepository = RoutingRepository(database.routingRuleSetDao())

    public fun close() {
        database.close()
    }
}
