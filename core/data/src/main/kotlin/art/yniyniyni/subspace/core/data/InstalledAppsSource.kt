// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One installed application, as much of it as the data layer has any business knowing. */
public data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * Enumerates installed applications for the per-app proxy (§8).
 *
 * The `QUERY_ALL_PACKAGES` declaration this needs lives in this module's manifest,
 * with the reasoning for it. Two callers share this one path: the picker in
 * `:feature:routing`, and the directive channel's `-invert` materialisation.
 *
 * Labels only, never icons: a `Drawable` is a UI object and would drag the
 * platform's resource lifecycle into a data type. `:feature:routing` resolves
 * icons per row itself.
 */
@Singleton
public class InstalledAppsSource
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Every installed application except this one, sorted by label.
     *
     * Sorted here so the picker does not sort again on every recomposition, and
     * because a stable order is what makes two consecutive reads comparable.
     *
     * Our own package is filtered out at the source rather than in the UI. §8
     * forbids routing the app through itself, and in [PerAppMode.AllowList] that
     * exclusion is purely structural — nothing calls `addDisallowedApplication`
     * to enforce it — so an own-package row the user could tick would be a real
     * defect, not a cosmetic one.
     *
     * On [Dispatchers.IO]: this walks every installed package and resolves a
     * label for each, which is far too slow for the main thread (§5.3).
     */
    public suspend fun installed(): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .map { info -> InstalledApp(info.packageName, pm.getApplicationLabel(info).toString()) }
                .sortedBy { it.label.lowercase() }
                .toList()
        }

    /** Just the package names, for callers that never render a row. */
    public suspend fun packageNames(): Set<String> = installed().mapTo(mutableSetOf()) { it.packageName }
}
