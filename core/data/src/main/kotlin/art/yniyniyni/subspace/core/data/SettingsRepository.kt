// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.SettingDao
import art.yniyniyni.subspace.core.data.db.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Private to this file: ARCHITECTURE.md §3 puts settings in Room specifically
// so callers go through a typed repository instead of poking the key/value
// table directly. No other module is meant to know these strings.
private const val KEY_THEME = "theme"
private const val KEY_ACTIVE_PROFILE = "active_profile_id"

/** The app's display theme. */
public enum class ThemePreference { System, Light, Dark }

/**
 * The typed accessors over [SettingEntity]'s key/value table.
 *
 * ARCHITECTURE.md §3: settings live in Room, not `androidx.datastore` — Preferences DataStore
 * is not multi-process safe, and this app runs `:main` and `:bg` as separate processes. One
 * storage engine means one invalidation mechanism and one place to reason about concurrency.
 */
@Singleton
public class SettingsRepository
@Inject
internal constructor(
    private val dao: SettingDao,
) {
    /** The current theme preference, defaulting to [ThemePreference.System] until set. */
    public val theme: Flow<ThemePreference> =
        dao.observe(KEY_THEME).map { stored ->
            // A defensive fallback, not an expected path: a value written by this
            // repository is always a valid enum name. Guards against a hand-edited
            // or future-version row rather than crashing the settings screen on it.
            stored?.let { name -> runCatching { ThemePreference.valueOf(name) }.getOrNull() }
                ?: ThemePreference.System
        }

    /** Persists the theme preference. */
    public suspend fun setTheme(preference: ThemePreference) {
        dao.put(SettingEntity(key = KEY_THEME, value = preference.name))
    }

    /** The currently active profile id, or null if none is set. */
    public val activeProfileId: Flow<Long?> =
        dao.observe(KEY_ACTIVE_PROFILE).map { stored -> stored?.toLongOrNull() }

    /**
     * Sets the active profile id, or clears it when [id] is null.
     *
     * [SettingDao] exposes no delete — clearing writes an empty string, which
     * `toLongOrNull()` reads back as null in [activeProfileId], the same as a key that was
     * never set.
     */
    public suspend fun setActiveProfile(id: Long?) {
        dao.put(SettingEntity(key = KEY_ACTIVE_PROFILE, value = id?.toString().orEmpty()))
    }
}
