// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.getsub.core.model.TunnelProxyLocator
import space.getsub.tunnel.TunnelProxyBinding

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
    // No @Singleton here: TunnelProxyBinding (the @Binds delegate) is already @Singleton at its
    // own @Inject constructor, which is what actually governs instance lifetime — @Binds has no
    // constructor of its own to scope, so a second @Singleton on the binding was redundant.
    // Review round 2, Minor.
    @Binds
    internal abstract fun tunnelProxyLocator(implementation: TunnelProxyBinding): TunnelProxyLocator
}
