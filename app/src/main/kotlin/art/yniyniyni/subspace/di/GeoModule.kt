// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.di

import android.content.Context
import art.yniyniyni.subspace.core.data.GeoAssetRoot
import art.yniyniyni.subspace.core.model.GeoDataValidator
import art.yniyniyni.subspace.core.xray.LibXrayGeoDataValidator
import art.yniyniyni.subspace.geoAssetDirectory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Supplies the two seams `:core:data`'s `DataModule.geoAssetRepository` needs
 * but cannot construct itself: the validator, and the install root.
 *
 * `:core:data` must not depend on `:core:xray` (§4: [LibXrayGeoDataValidator]
 * is a libXray call) or on `:app` (§4: [geoAssetDirectory] lives here), and
 * `:app` sits downstream of both — so this is the one place with both halves in
 * scope. Hilt aggregates every `@InstallIn(SingletonComponent::class)` module at
 * the app component, so a `:core:data` provider depending on a binding declared
 * here needs no Gradle edge back from `:core:data` to `:app`.
 *
 * The [art.yniyniyni.subspace.core.data.GeoDownloader] binding those seams also
 * feed is provided by `DataModule` itself, not here: `GeoFileFetcher` lives in
 * `:core:network`, which only `:core:data` may depend on (§4,
 * `checkModuleBoundaries`) — `:app` cannot see it at all.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GeoModule {
    @Provides
    @Singleton
    fun geoDataValidator(implementation: LibXrayGeoDataValidator): GeoDataValidator = implementation

    @Provides
    @GeoAssetRoot
    fun geoAssetRoot(
        @ApplicationContext context: Context,
    ): File = geoAssetDirectory(context)
}
