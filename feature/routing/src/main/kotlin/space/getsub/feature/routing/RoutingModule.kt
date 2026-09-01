// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds this feature's source interfaces to their Hilt-provided
 * implementations — the same `@Module`/`@Provides` shape
 * `:feature:settings`' own `SettingsModule` uses for `SettingsSource`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object RoutingModule {
    @Provides
    @Singleton
    fun routingSource(impl: BoundRoutingSource): RoutingSource = impl

    @Provides
    @Singleton
    fun perAppSource(impl: BoundPerAppSource): PerAppSource = impl

    @Provides
    @Singleton
    fun importReviewSource(impl: BoundImportReviewSource): ImportReviewSource = impl
}
