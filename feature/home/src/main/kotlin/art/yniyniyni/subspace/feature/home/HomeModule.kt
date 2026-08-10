// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.data.LatencyCache
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.LatencyOptions
import art.yniyniyni.subspace.core.model.LatencyResult
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.service.TunnelClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val cache: LatencyCache,
    private val settings: SettingsRepository,
) : TunnelConnection {
    override val state: StateFlow<ConnectionState> get() = client.state

    override val latencies: StateFlow<Map<Long, LatencyResult>> get() = cache.results

    override val measuring: StateFlow<Set<Long>> get() = cache.testing

    override val pingOnLaunch: Flow<Boolean> get() = settings.pingOnLaunch

    override fun connect(
        profile: Profile,
        rowId: Long,
    ) = client.connect(profile, rowId)

    override fun disconnect() = client.disconnect()

    /**
     * The run id is derived from the profile, not from a shared counter.
     *
     * `:feature:profiles` runs its own counter, and the two screens never
     * measure at once from a user's point of view — but a negative id keeps
     * Home's runs out of that sequence entirely, so neither side can fence away
     * the other's results by coincidence.
     */
    override suspend fun measure(profileId: Long) {
        val options =
            LatencyOptions(
                mode = settings.pingMode.first(),
                timeoutSeconds = settings.pingTimeoutSeconds.first(),
                checkUrl = settings.pingCheckUrl.first(),
            )
        cache.markTesting(listOf(profileId))
        client.startLatencyRun(
            runId = -profileId,
            profileIds = listOf(profileId),
            options = options,
            onResult = { id, result -> cache.put(id, result) },
            onFinished = { cache.finish(listOf(profileId)) },
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object HomeModule {
    @Provides
    @Singleton
    fun tunnelConnection(impl: BoundTunnelConnection): TunnelConnection = impl

    @Provides
    @Singleton
    fun activeProfileSource(impl: BoundActiveProfileSource): ActiveProfileSource = impl
}
