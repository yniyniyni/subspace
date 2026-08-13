// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.di

import android.content.Context
import androidx.room.Room
import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.data.GeoAssetRoot
import art.yniyniyni.subspace.core.data.GeoDownloader
import art.yniyniyni.subspace.core.data.db.GeoAssetDao
import art.yniyniyni.subspace.core.data.db.MIGRATION_1_2
import art.yniyniyni.subspace.core.data.db.MIGRATION_2_3
import art.yniyniyni.subspace.core.data.db.ProfileDao
import art.yniyniyni.subspace.core.data.db.RoutingRuleSetDao
import art.yniyniyni.subspace.core.data.db.SettingDao
import art.yniyniyni.subspace.core.data.db.SubscriptionDao
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.GeoDataValidator
import art.yniyniyni.subspace.core.network.GeoDownloadOutcome
import art.yniyniyni.subspace.core.network.GeoFileFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Singleton

private const val DATABASE_NAME = "subspace.db"

/**
 * Ceiling on any single geo database, in bytes.
 *
 * The largest measured source is runetfreedom's `geosite.dat` at 73.7 MB
 * (research §7). 128 MiB leaves real headroom for upstream growth while still
 * bounding a source that lies about its size or a hijacked URL serving an
 * endless stream — a geo source URL is user-supplied and untrusted (§A.1).
 */
private const val MAX_GEO_FILE_BYTES = 128L * 1024 * 1024

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
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

    @Provides
    fun subscriptionDao(database: SubspaceDatabase): SubscriptionDao = database.subscriptionDao()

    @Provides
    fun routingRuleSetDao(database: SubspaceDatabase): RoutingRuleSetDao = database.routingRuleSetDao()

    @Provides
    fun geoAssetDao(database: SubspaceDatabase): GeoAssetDao = database.geoAssetDao()

    /**
     * Assembles [GeoAssetRepository] from its Room DAO plus the two seams this
     * module cannot supply itself: [GeoDataValidator] is a `:core:xray` call and
     * [GeoAssetRoot] is a path `:app` computes (§4 forbids `:core:data` from
     * depending on either), so both come from `:app`'s `GeoModule` via Hilt's
     * shared app component. [GeoAssetRepository]'s constructor is `internal` to
     * this module on purpose (§11) — production reaches it only through this
     * provider, never by hand.
     */
    @Provides
    @Singleton
    fun geoAssetRepository(
        dao: GeoAssetDao,
        validator: GeoDataValidator,
        @GeoAssetRoot root: File,
        downloader: GeoDownloader,
    ): GeoAssetRepository =
        GeoAssetRepository(
            dao = dao,
            validator = validator,
            root = root,
            download = downloader::download,
            clock = System::currentTimeMillis,
        )

    /**
     * The production [GeoDownloader]: streams through [GeoFileFetcher] and
     * translates its outcome into [GeoAssetRepository]'s narrower contract.
     *
     * This lives here rather than in `:app`'s `GeoModule` because `:core:network`
     * is `:core:data`'s own I/O boundary (§4) — `GeoFileFetcher` is not visible
     * from `:app` at all, `checkModuleBoundaries` enforces exactly that, and the
     * same seam already exists for the subscription pipeline
     * (`SubscriptionSyncFailure`, in this module, for the identical reason).
     * `proxyPort` is left at its default; Task 13 wires it once a tunnel is
     * live to route the request through.
     */
    @Provides
    @Singleton
    fun geoDownloader(fetcher: GeoFileFetcher): GeoDownloader =
        GeoDownloader { url, target ->
            when (val outcome = fetcher.download(url, target, MAX_GEO_FILE_BYTES) {}) {
                is GeoDownloadOutcome.Success -> outcome.bytes to outcome.sha256
                // GeoAssetRepository.install's download step catches this and
                // records DownloadFailed; a failure discovered after a
                // successful download (staging, validation, publish, or the
                // Room record) is recorded as InstallFailed instead, so a local
                // disk problem is never reported to the user as "check your
                // connection" (§A.4.1).
                is GeoDownloadOutcome.Failed -> throw IOException(outcome.reason.name)
            }
        }
}
