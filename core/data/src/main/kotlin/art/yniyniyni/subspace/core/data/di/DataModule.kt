// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.di

import android.content.Context
import androidx.room.Room
import art.yniyniyni.subspace.core.data.db.ProfileDao
import art.yniyniyni.subspace.core.data.db.SettingDao
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val DATABASE_NAME = "subspace.db"

/**
 * Opens [SubspaceDatabase] against the on-disk file called [name].
 *
 * The one and only place the builder is configured. ARCHITECTURE.md §3: this app runs `:main` and
 * `:bg` as separate processes, each with its own `SubspaceDatabase` instance opening the same
 * on-disk file — Hilt singletons do not bridge that boundary, Room does.
 * `enableMultiInstanceInvalidation()` is what wires the two instances'
 * query-invalidation together; without it a write from one process's instance never invalidates
 * the other's cached query results, so the UI can serve stale rows forever with no exception and
 * no log anywhere.
 *
 * `MultiProcessInvalidationTest` builds *its* reader and its `:writer` process's writer through
 * this same function rather than through a builder of its own, so deleting the
 * `enableMultiInstanceInvalidation()` line below fails that test. A test that configured its own
 * builder would keep passing while production lost the flag — which is the §3 silent failure
 * reproduced in the test suite instead of guarded against. Do not inline this back into
 * [DataModule.database], and do not "simplify" the flag away.
 *
 * [name] is a parameter for that test's sake only; production has exactly one database.
 */
internal fun subspaceDatabase(
    context: Context,
    name: String = DATABASE_NAME,
): SubspaceDatabase =
    Room
        .databaseBuilder(context.applicationContext, SubspaceDatabase::class.java, name)
        .enableMultiInstanceInvalidation()
        .build()

/**
 * Provides the Room database and its DAOs to both processes.
 *
 * A `@Singleton` binding here is safe for the same reason `TunnelClient`'s is (`:service`): it
 * exists once per process, and the two processes each get their own — which is exactly why
 * [subspaceDatabase]'s invalidation flag is load-bearing rather than decorative.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): SubspaceDatabase = subspaceDatabase(context)

    @Provides
    fun profileDao(database: SubspaceDatabase): ProfileDao = database.profileDao()

    @Provides
    fun settingDao(database: SubspaceDatabase): SettingDao = database.settingDao()
}
