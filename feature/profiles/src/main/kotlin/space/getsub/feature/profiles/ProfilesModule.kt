// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.getsub.feature.profiles.list.BoundLatencyTester
import space.getsub.feature.profiles.list.LatencyTester
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object ProfilesModule {
    @Provides
    @Singleton
    fun profileSource(impl: BoundProfileSource): ProfileSource = impl

    /**
     * `@Singleton` because the results it exposes are session-scoped and shared:
     * a run started from the Servers list must be visible to whatever else reads
     * a latency, and a second instance would hold a second, empty cache.
     */
    @Provides
    @Singleton
    fun latencyTester(impl: BoundLatencyTester): LatencyTester = impl
}
