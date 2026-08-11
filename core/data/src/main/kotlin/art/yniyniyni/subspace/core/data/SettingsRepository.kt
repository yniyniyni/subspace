// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.SettingDao
import art.yniyniyni.subspace.core.data.db.SettingEntity
import art.yniyniyni.subspace.core.model.PingMode
import art.yniyniyni.subspace.core.model.pingModeFrom
import art.yniyniyni.subspace.core.network.HwidProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Private to this file: ARCHITECTURE.md §3 puts settings in Room specifically
// so callers go through a typed repository instead of poking the key/value
// table directly. No other module is meant to know these strings.
private const val KEY_THEME = "theme"
private const val KEY_ACTIVE_PROFILE = "active_profile_id"
private const val KEY_ACTIVE_ROUTING_RULE_SET = "active_routing_rule_set_id"
private const val KEY_HWID_ENABLED = "hwid_enabled"
private const val KEY_PING_MODE = "ping_mode"
private const val KEY_PING_CHECK_URL = "ping_check_url"
private const val KEY_PING_TIMEOUT_SECONDS = "ping_timeout_seconds"
private const val KEY_PING_ON_LAUNCH = "ping_on_launch"
private const val KEY_PING_ON_LAUNCH_METERED = "ping_on_launch_metered"

/**
 * A 204 endpoint on purpose: a `HEAD` against it returns no body, so a latency
 * check never pulls content the user did not ask for.
 */
private const val DEFAULT_CHECK_URL = "https://www.gstatic.com/generate_204"

private const val DEFAULT_TIMEOUT_SECONDS = 5
private const val MIN_TIMEOUT_SECONDS = 1
private const val MAX_TIMEOUT_SECONDS = 15

/** The two wire values [PingMode] round-trips through; `proxy` is accepted on read as an alias. */
private const val MODE_TCP = "tcp"
private const val MODE_PROXY_HEAD = "proxy-head"

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
    private val hwidProvider: HwidProvider,
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

    /**
     * The active routing rule set, or null when routing is off.
     *
     * Null is the ordinary state, not an error: it produces the `"rules": []`
     * block M1's proven tunnel has always carried.
     */
    public val activeRoutingRuleSetId: Flow<Long?> =
        dao.observe(KEY_ACTIVE_ROUTING_RULE_SET).map { stored -> stored?.toLongOrNull() }

    /**
     * Sets the active rule set, or turns routing off when [id] is null.
     *
     * Clearing writes an empty string for the same reason [setActiveProfile]
     * does: [SettingDao] exposes no delete, and `toLongOrNull()` reads `""` back
     * as null — the same result as a key that was never written.
     */
    public suspend fun setActiveRoutingRuleSetId(id: Long?) {
        dao.put(SettingEntity(key = KEY_ACTIVE_ROUTING_RULE_SET, value = id?.toString() ?: ""))
    }

    /** Whether the global Device ID header gate is enabled; defaults to on for provider compatibility. */
    public val hwidEnabled: Flow<Boolean> =
        dao.observe(KEY_HWID_ENABLED).map { stored ->
            // A value this repository writes is always "true" or "false". Preserve the safe,
            // compatible default if a hand-edited/future value cannot be interpreted.
            stored?.toBooleanStrictOrNull() ?: true
        }

    /** The stable, hashed identifier the fetcher sends as `x-hwid`; never the raw Android ID. */
    public fun hwid(): String = hwidProvider.hwid()

    /** Sets the global Device ID gate. Individual subscriptions may still opt out separately. */
    public suspend fun setHwidEnabled(enabled: Boolean) {
        dao.put(SettingEntity(key = KEY_HWID_ENABLED, value = enabled.toString()))
    }

    /**
     * How latency is measured, defaulting to [PingMode.TCP] — see [pingModeFrom]
     * for why that is the default rather than the mode that proves the proxy
     * works.
     *
     * [pingModeFrom] also absorbs a stored `proxy`, which is the documented alias
     * for `proxy-head` — libXray v26.7.11 cannot issue the GET that `proxy`
     * means. Anything else, including a hand-edited `icmp`, falls back rather
     * than reviving a mode Appendix D cut.
     */
    public val pingMode: Flow<PingMode> = dao.observe(KEY_PING_MODE).map { stored -> pingModeFrom(stored) }

    public suspend fun setPingMode(mode: PingMode) {
        val wire = if (mode == PingMode.TCP) MODE_TCP else MODE_PROXY_HEAD
        dao.put(SettingEntity(key = KEY_PING_MODE, value = wire))
    }

    /**
     * The URL a `proxy-head` measurement fetches.
     *
     * User-owned, never provider-set. `check-url-via-proxy` is `Danger.Dangerous`
     * / `Consumer.None` in `DirectiveRegistry`: it would let whoever controls a
     * subscription URL choose what this device fetches through the tunnel, and
     * §A.1 mandates explicit confirmation for that class of directive. This
     * setting is what satisfies the roadmap's "a configurable check URL".
     */
    public val pingCheckUrl: Flow<String> =
        dao.observe(KEY_PING_CHECK_URL).map { stored ->
            stored?.takeIf { it.isNotBlank() } ?: DEFAULT_CHECK_URL
        }

    public suspend fun setPingCheckUrl(url: String) {
        dao.put(SettingEntity(key = KEY_PING_CHECK_URL, value = url))
    }

    /**
     * Clamped on read as well as on write, not only on write: this value reaches
     * libXray as **seconds**, where an out-of-range number is a measurement that
     * effectively never returns. A hand-edited or future-version row must not be
     * able to produce one.
     */
    public val pingTimeoutSeconds: Flow<Int> =
        dao.observe(KEY_PING_TIMEOUT_SECONDS).map { stored ->
            (stored?.toIntOrNull() ?: DEFAULT_TIMEOUT_SECONDS).coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        }

    public suspend fun setPingTimeoutSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        dao.put(SettingEntity(key = KEY_PING_TIMEOUT_SECONDS, value = clamped.toString()))
    }

    /**
     * Whether every group is measured once when the server list is first shown.
     *
     * **Ours, and on by default.** Latency results are session-scoped, so a cold
     * start has none and a "Fastest" sort would be empty until the user tapped
     * something. This is what refills it, for every group including a `MANUAL`
     * one with no provider at all. `subscription-ping-onopen-enabled` can
     * override this for a single subscription's group; it does not own the
     * feature.
     */
    public val pingOnLaunch: Flow<Boolean> =
        dao.observe(KEY_PING_ON_LAUNCH).map { stored -> stored?.toBooleanStrictOrNull() ?: true }

    public suspend fun setPingOnLaunch(enabled: Boolean) {
        dao.put(SettingEntity(key = KEY_PING_ON_LAUNCH, value = enabled.toString()))
    }

    /**
     * Whether ping-on-launch may run on a metered network.
     *
     * Off by default: a large list measured with `proxy-head` starts one Xray
     * instance per server, and doing that unprompted on cellular is real data and
     * real battery. The manual test action is unaffected by this.
     */
    public val pingOnLaunchMetered: Flow<Boolean> =
        dao.observe(KEY_PING_ON_LAUNCH_METERED).map { stored -> stored?.toBooleanStrictOrNull() ?: false }

    public suspend fun setPingOnLaunchMetered(enabled: Boolean) {
        dao.put(SettingEntity(key = KEY_PING_ON_LAUNCH_METERED, value = enabled.toString()))
    }
}
