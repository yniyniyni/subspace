// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.service.TunnelClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapts the AIDL-backed [TunnelClient] to the narrow view this screen needs.
 *
 * The indirection exists so [HomeViewModel] can be reasoned about without a live
 * binder — and so the screen never touches the process boundary itself.
 */
@Singleton
internal class BoundTunnelConnection @Inject constructor(
    private val client: TunnelClient,
) : TunnelConnection {
    override val state: StateFlow<ConnectionState> get() = client.state

    override fun connect(profile: Profile) = client.connect(profile)

    override fun disconnect() = client.disconnect()
}

@Module
@InstallIn(SingletonComponent::class)
internal object HomeModule {
    @Provides
    @Singleton
    fun tunnelConnection(impl: BoundTunnelConnection): TunnelConnection = impl
}
