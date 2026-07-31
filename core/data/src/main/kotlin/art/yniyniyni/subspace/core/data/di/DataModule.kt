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
 * Provides the Room database and its DAOs to both processes.
 *
 * ARCHITECTURE.md §3: this app runs `:main` and `:bg` as separate processes, each with its own
 * `SubspaceDatabase` instance opening the same on-disk file — Hilt singletons do not bridge
 * that boundary, Room does. [enableMultiInstanceInvalidation] is what wires the two instances'
 * query-invalidation together; without it a write from one process's instance never invalidates
 * the other's cached query results, so the UI can serve stale rows forever with no exception and
 * no log anywhere. Task 10 is the instrumented test that fails the moment this call is removed —
 * do not "simplify" it away.
 *
 * A `@Singleton` binding here is safe for the same reason `TunnelClient`'s is (`:service`): it
 * exists once per process, and the two processes each get their own — which is exactly why the
 * invalidation flag above is load-bearing rather than decorative.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): SubspaceDatabase =
        Room
            .databaseBuilder(context, SubspaceDatabase::class.java, DATABASE_NAME)
            .enableMultiInstanceInvalidation()
            .build()

    @Provides
    fun profileDao(database: SubspaceDatabase): ProfileDao = database.profileDao()

    @Provides
    fun settingDao(database: SubspaceDatabase): SettingDao = database.settingDao()
}
