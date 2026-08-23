// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.SettingDao
import art.yniyniyni.subspace.core.data.db.SettingEntity
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PingMode
import art.yniyniyni.subspace.core.model.perAppModeFrom
import art.yniyniyni.subspace.core.model.perAppModeWire
import art.yniyniyni.subspace.core.model.pingModeFrom
import art.yniyniyni.subspace.core.network.HwidProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
private const val KEY_GEO_REFRESH_ON_METERED = "geo_refresh_on_metered"
private const val KEY_SELECTED_GEO_SOURCE_IDS = "selected_geo_source_ids"
private const val SELECTED_GEO_SOURCE_ID_DELIMITER = ","
private const val KEY_PER_APP_MODE = "per_app_mode"
private const val KEY_PER_APP_USER_PACKAGES = "per_app_user_packages"
private const val PER_APP_PACKAGE_DELIMITER = ","
private const val KEY_DNS_TRANSPORT = "dns_transport"
private const val KEY_DNS_DOMAIN = "dns_domain"
private const val KEY_DNS_IP = "dns_ip"

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
@Suppress("TooManyFunctions") // One typed getter/setter pair per setting; splitting the class would not shrink this.
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

    /** Atomically activates [id] only when routing currently reads as off. */
    public suspend fun activateRoutingRuleSetIfNone(id: Long): Boolean =
        dao.putIfNoNumericValue(KEY_ACTIVE_ROUTING_RULE_SET, id)

    /** Clears routing only if [id] is still active; a newer selection is untouched. */
    public suspend fun clearActiveRoutingRuleSetIf(id: Long): Boolean =
        dao.clearIfValue(KEY_ACTIVE_ROUTING_RULE_SET, id.toString()) == 1

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

    /**
     * Whether a *scheduled* geo-database refresh may run on a metered network.
     *
     * Off by default: the largest measured source is 73.7 MB (`GeoSourceCatalogue`), and pulling
     * that unannounced over cellular is hostile. A manual "Update now" is unaffected by this
     * setting — it always runs, ignoring both the metered constraint and the freshness cap
     * (§A.5). This module does not know about the scheduler that reads this flag; `:app`'s
     * `GeoRefreshScheduler` is the caller (§4 forbids the reverse dependency).
     */
    public val geoRefreshOnMetered: Flow<Boolean> =
        dao.observe(KEY_GEO_REFRESH_ON_METERED).map { stored -> stored?.toBooleanStrictOrNull() ?: false }

    public suspend fun setGeoRefreshOnMetered(enabled: Boolean) {
        dao.put(SettingEntity(key = KEY_GEO_REFRESH_ON_METERED, value = enabled.toString()))
    }

    /**
     * Which catalogue source id backs each geo install slot, empty until the user opens the
     * picker and explicitly changes one.
     *
     * A branch review found the previous, unpersisted version of this setting: it lived only in
     * `SettingsState`'s default, reset to `GeoSourceCatalogue.defaults()` on every ViewModel
     * construction, so a deliberate switch away from v2fly was forgotten the moment Settings was
     * left and reopened. Empty (not the defaults pair) is the correct persisted default — the
     * fallback to `GeoSourceCatalogue.defaults()` when nothing is selected *and* nothing is
     * installed belongs to `:feature:settings`' own row-assembly logic, not to this flag's stored
     * value, so that logic stays free to distinguish "the user has never chosen" from "the user
     * chose the v2fly pair on purpose".
     *
     * Stored as a comma-joined id list: [art.yniyniyni.subspace.core.model.GeoSource] ids are
     * plain hyphenated identifiers the catalogue defines (never user-supplied text or a URL), so a
     * plain split is safe and carries nothing §5.6 would otherwise redact.
     */
    public val selectedGeoSourceIds: Flow<Set<String>> =
        dao.observe(KEY_SELECTED_GEO_SOURCE_IDS).map { stored ->
            stored
                ?.split(SELECTED_GEO_SOURCE_ID_DELIMITER)
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        }

    public suspend fun setSelectedGeoSourceIds(ids: Set<String>) {
        val value = ids.joinToString(SELECTED_GEO_SOURCE_ID_DELIMITER)
        dao.put(SettingEntity(key = KEY_SELECTED_GEO_SOURCE_IDS, value = value))
    }

    /**
     * Which apps use the tunnel (§8), defaulting to [PerAppMode.Off].
     *
     * Stored as Happ's wire word rather than the enum name — see [perAppModeFrom]
     * for why, and for why an unreadable value falls back to `Off` rather than to
     * whatever was most recently set.
     */
    public val perAppMode: Flow<PerAppMode> =
        dao.observe(KEY_PER_APP_MODE).map { stored -> perAppModeFrom(stored) }

    public suspend fun setPerAppMode(mode: PerAppMode) {
        dao.put(SettingEntity(key = KEY_PER_APP_MODE, value = perAppModeWire(mode)))
    }

    /**
     * The packages the **user** selected, which is not the same as the packages in
     * force: `PerAppRepository` unions this with any approved provider layer, and
     * that union is what reaches the tunnel.
     *
     * Comma-joined for the reason [selectedGeoSourceIds] is: Android package names
     * match `[A-Za-z0-9_.]+`, so they cannot contain the delimiter. The blank
     * filter is load-bearing rather than defensive — [SettingDao] exposes no
     * delete, so clearing the selection writes `""`, and a naive split would read
     * that back as a set containing one empty package name.
     */
    public val perAppUserPackages: Flow<Set<String>> =
        dao.observe(KEY_PER_APP_USER_PACKAGES).map { stored ->
            stored
                ?.split(PER_APP_PACKAGE_DELIMITER)
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        }

    public suspend fun setPerAppUserPackages(packages: Set<String>) {
        val value = packages.sorted().joinToString(PER_APP_PACKAGE_DELIMITER)
        dao.put(SettingEntity(key = KEY_PER_APP_USER_PACKAGES, value = value))
    }

    /**
     * The resolver used when no routing profile supplies one.
     *
     * Precedence is fixed (spec §6): an active profile's valid DNS block wins
     * wholesale. This applies when there is no active profile, the active profile
     * carries no DNS block, or its block was rejected at import.
     *
     * The default is today's hardcoded literal, deliberately: making the default
     * identical to current behaviour is what lets the generator keep emitting the
     * hardware-proven M1 config byte-for-byte when nothing asks for DNS (§7.4).
     */
    public val dnsResolver: Flow<DnsResolver> =
        combine(
            dao.observe(KEY_DNS_TRANSPORT),
            dao.observe(KEY_DNS_DOMAIN),
            dao.observe(KEY_DNS_IP),
        ) { transport, domain, ip ->
            val parsed = DnsTransport.fromWire(transport)
            val resolver =
                parsed?.let {
                    DnsResolver(
                        transport = it,
                        domain = domain?.takeIf(String::isNotBlank),
                        ip = ip?.takeIf(String::isNotBlank),
                    )
                }
            if (resolver != null && resolver.xrayAddress() != null) resolver else DnsResolver.DEFAULT
        }

    public suspend fun setDnsResolver(resolver: DnsResolver) {
        dao.put(SettingEntity(key = KEY_DNS_TRANSPORT, value = resolver.transport.wireValue))
        dao.put(SettingEntity(key = KEY_DNS_DOMAIN, value = resolver.domain.orEmpty()))
        dao.put(SettingEntity(key = KEY_DNS_IP, value = resolver.ip.orEmpty()))
    }
}
