// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object SettingsModule {
    @Provides
    @Singleton
    fun settingsSource(impl: BoundSettingsSource): SettingsSource = impl

    @Provides
    @Singleton
    fun xraySource(impl: BoundXraySource): XraySource = impl

    @Provides
    @Singleton
    fun appVersionSource(impl: BoundAppVersionSource): AppVersionSource = impl
}
