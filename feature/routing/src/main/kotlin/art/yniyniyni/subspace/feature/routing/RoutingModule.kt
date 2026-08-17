// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [RoutingSource] to [BoundRoutingSource] — the same
 * `@Module`/`@Provides` shape `:feature:settings`' own `SettingsModule` uses
 * for `SettingsSource`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object RoutingModule {
    @Provides
    @Singleton
    fun routingSource(impl: BoundRoutingSource): RoutingSource = impl
}
