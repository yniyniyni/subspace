// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network.di

import art.yniyniyni.subspace.core.network.AndroidHwidProvider
import art.yniyniyni.subspace.core.network.HwidProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkModule {
    @Binds
    @Singleton
    abstract fun bindHwidProvider(impl: AndroidHwidProvider): HwidProvider
}
