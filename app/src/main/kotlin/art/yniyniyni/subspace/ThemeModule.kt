// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object ThemeModule {
    @Provides
    @Singleton
    fun themeSource(impl: BoundThemeSource): ThemeSource = impl
}
