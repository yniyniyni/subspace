// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network.di

import android.content.Context
import art.yniyniyni.subspace.core.network.AndroidDeviceInfo
import art.yniyniyni.subspace.core.network.AndroidHwidProvider
import art.yniyniyni.subspace.core.network.DeviceInfo
import art.yniyniyni.subspace.core.network.HwidProvider
import art.yniyniyni.subspace.core.network.SubscriptionFetcher
import art.yniyniyni.subspace.core.network.SubscriptionSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies this app's own `versionName`, sent as `x-app-version` and folded
 * into the default User-Agent (§A.4.1/§A.4.2). Provided below via
 * [android.content.pm.PackageManager] rather than `BuildConfig.VERSION_NAME`:
 * `:core:network` cannot see `:app`'s `BuildConfig` (only `:core:data` may
 * depend on `:core:network`, §4 — the reverse edge does not exist either), so
 * a runtime lookup of the app's own installed package is the only value this
 * module can reach on its own. Same pattern as
 * `:feature:settings`'s `AppVersionSource`, which hits the identical
 * `BuildConfig`-is-unreachable problem for the same architectural reason.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class AppVersion

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkModule {
    @Binds
    @Singleton
    abstract fun bindHwidProvider(impl: AndroidHwidProvider): HwidProvider

    @Binds
    @Singleton
    abstract fun bindDeviceInfo(impl: AndroidDeviceInfo): DeviceInfo

    /** `:core:data`'s sync pipeline (Task 11) fetches through this seam; [SubscriptionFetcher] is the only impl. */
    @Binds
    @Singleton
    abstract fun bindSubscriptionSource(impl: SubscriptionFetcher): SubscriptionSource

    companion object {
        @Provides
        @AppVersion
        @Singleton
        fun provideAppVersion(
            @ApplicationContext context: Context,
        ): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
    }
}
