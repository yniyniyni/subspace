// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.di

import art.yniyniyni.subspace.core.model.TunnelProxyLocator
import art.yniyniyni.subspace.tunnel.TunnelProxyBinding
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Supplies the one [TunnelProxyLocator] implementation.
 *
 * Separate from [GeoModule] because the consumers are unrelated: the subscription
 * pipeline in `:core:data` needs this too, and geo assets are not a reason for it
 * to exist. Same module for the same reason [GeoModule] is here — `:app` is the
 * only place `:service` and `:core:model` are both in scope (§4).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TunnelProxyModule {
    @Binds
    @Singleton
    internal abstract fun tunnelProxyLocator(implementation: TunnelProxyBinding): TunnelProxyLocator
}
