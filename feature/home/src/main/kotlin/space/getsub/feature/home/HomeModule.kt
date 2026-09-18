// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import space.getsub.core.data.LatencyCache
import space.getsub.core.data.SettingsRepository
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyOptions
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.LatencyTarget
import space.getsub.core.model.Profile
import space.getsub.core.model.TrafficSample
import space.getsub.service.TunnelClient
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

    override val traffic: StateFlow<TrafficSample?> get() = client.traffic

    override val pingOnLaunch: Flow<Boolean> get() = settings.pingOnLaunch

    override val perTagBreakdown: Flow<Boolean> get() = settings.perTagBreakdown

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
        // Both this and BoundLatencyTester drive the same single-run-at-a-time
        // LatencyRunner and write to the same LatencyCache, so starting here
        // supersedes a Servers run — whose onFinished is then fenced away, leaving
        // its rows on "…" for the session. LatencyTester's KDoc states this as a
        // requirement for implementations; the fix had been applied to only one of
        // the two paths that can supersede.
        cache.finish(cache.testing.value)
        cache.markTesting(listOf(profileId))
        @Suppress("IgnoredReturnValue")
        client.startLatencyRun(
            runId = -profileId,
            // Home follows the global setting: it measures the active profile,
            // and has no group context to resolve a provider's ping-type from.
            targets = listOf(LatencyTarget(profileId, options.mode)),
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
