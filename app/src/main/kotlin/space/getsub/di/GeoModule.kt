// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import space.getsub.core.data.GeoAssetRoot
import space.getsub.core.model.GeoDataValidator
import space.getsub.core.xray.LibXrayGeoDataValidator
import space.getsub.geoAssetDirectory
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
 * The [space.getsub.core.data.GeoDownloader] binding those seams also
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
