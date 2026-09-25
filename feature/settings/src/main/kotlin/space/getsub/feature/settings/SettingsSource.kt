// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import space.getsub.core.data.RoutingRepository
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.ThemePreference
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.PingMode
import space.getsub.core.model.ProfileDns
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [SettingsRepository] slice this screen needs.
 *
 * [SettingsRepository] has an `internal` constructor scoped to `:core:data`
 * (§3 keeps settings behind a typed repository, not a key/value table any
 * module can poke) — this module cannot build a real instance of it to test
 * against, the same reason
 * [space.getsub.feature.profiles.ProfileSource] and
 * [space.getsub.feature.home.ActiveProfileSource] exist.
 * [BoundSettingsSource] is the one place that touches the real repository;
 * [SettingsViewModel] goes through this interface instead, so a plain JVM
 * test can exercise it against a fake.
 */
// One property/setter pair per persisted preference this screen needs — the same shape
// SettingsViewModel's own TooManyFunctions suppression documents: splitting this interface by
// preference would only move the count into more, smaller interfaces, not reduce it.
@Suppress("TooManyFunctions")
internal interface SettingsSource {
    /** The current theme preference. See [SettingsRepository.theme]. */
    val theme: Flow<ThemePreference>

    /** Whether Device ID headers are globally permitted. See [SettingsRepository.hwidEnabled]. */
    val hwidEnabled: Flow<Boolean>

    /** The hashed Device ID providers see. See [SettingsRepository.hwid]. */
    val hwid: String

    /** Persists the theme preference. See [SettingsRepository.setTheme]. */
    suspend fun setTheme(preference: ThemePreference)

    /** Persists the global Device ID gate. See [SettingsRepository.setHwidEnabled]. */
    suspend fun setHwidEnabled(enabled: Boolean)

    /** How latency is measured. See [SettingsRepository.pingMode]. */
    val pingMode: Flow<PingMode>

    /** The URL a proxy-head measurement fetches. See [SettingsRepository.pingCheckUrl]. */
    val pingCheckUrl: Flow<String>

    /** How long a measurement may take, in seconds. See [SettingsRepository.pingTimeoutSeconds]. */
    val pingTimeoutSeconds: Flow<Int>

    /** Whether every group is measured once when the server list is first shown. */
    val pingOnLaunch: Flow<Boolean>

    /** Whether ping-on-launch may run on a metered network. */
    val pingOnLaunchMetered: Flow<Boolean>

    suspend fun setPingMode(mode: PingMode)

    suspend fun setPingCheckUrl(url: String)

    suspend fun setPingTimeoutSeconds(seconds: Int)

    suspend fun setPingOnLaunch(enabled: Boolean)

    suspend fun setPingOnLaunchMetered(enabled: Boolean)

    /** The resolver used while no active routing profile has an effective DNS block. */
    val dnsResolver: Flow<DnsResolver>

    suspend fun setDnsResolver(resolver: DnsResolver)

    /** Whether the active routing profile currently supplies an effective DNS change. */
    val dnsOverriddenByProfile: Flow<Boolean>

    /**
     * Whether a *scheduled* geo-database refresh may run on a metered network. See
     * [SettingsRepository.geoRefreshOnMetered]. A manual "Update now" is unaffected by this — see
     * [GeoAssetSource.install]'s KDoc.
     */
    val geoRefreshOnMetered: Flow<Boolean>

    suspend fun setGeoRefreshOnMetered(enabled: Boolean)

    /**
     * Which catalogue source id backs each geo install slot. See
     * [SettingsRepository.selectedGeoSourceIds] — persisted, not a ViewModel default, so a
     * deliberate picker switch survives a process restart (branch review, Finding 1).
     */
    val selectedGeoSourceIds: Flow<Set<String>>

    suspend fun setSelectedGeoSourceIds(ids: Set<String>)

    /** Connect on every boot. See [SettingsRepository.bootAutostart]. */
    val bootAutostart: Flow<Boolean>

    suspend fun setBootAutostart(enabled: Boolean)

    /** Whether the TUN is retained while a wanted session is down. See [SettingsRepository.failClosed]. */
    val failClosed: Flow<Boolean>

    suspend fun setFailClosed(enabled: Boolean)

    /** §9's "prompt once, respect refusal" (spec §7.2). See [SettingsRepository.batteryPromptShown]. */
    val batteryPromptShown: Flow<Boolean>

    suspend fun setBatteryPromptShown(shown: Boolean)

    /**
     * Whether xray's `stats`/`policy`/`metrics` blocks are emitted for the per-server traffic
     * breakdown. See [SettingsRepository.perTagBreakdown] — its KDoc is where the security
     * reasoning behind the default lives.
     */
    val perTagBreakdown: Flow<Boolean>

    suspend fun setPerTagBreakdown(enabled: Boolean)

    /**
     * A fresh platform read of whether Android already exempts this app from battery
     * optimization — not a [Flow], since the only caller needs its value at one instant (the
     * moment a survival setting is switched on), the same one-shot shape [hwid] already uses for
     * a value nothing here needs to observe changing.
     *
     * `suspend`, and not a plain property, because the implementation is a binder round trip to
     * `PowerManager` — §5.3 keeps those off the Main dispatcher, and the dispatcher belongs to
     * the implementation that knows it makes the call rather than to every caller. It also keeps
     * a fake deterministic: no real dispatcher hop for a test scheduler to miss.
     */
    suspend fun isIgnoringBatteryOptimizations(): Boolean
}

// Same shape as SettingsSource's own TooManyFunctions suppression above — this class implements
// every one of that interface's members, so the count cannot come down without moving it there.
@Suppress("TooManyFunctions")
@Singleton
internal class BoundSettingsSource
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    @param:ApplicationContext private val context: Context,
) : SettingsSource {
    override val theme: Flow<ThemePreference> = settingsRepository.theme
    override val hwidEnabled: Flow<Boolean> = settingsRepository.hwidEnabled
    override val hwid: String = settingsRepository.hwid()

    override suspend fun setTheme(preference: ThemePreference) = settingsRepository.setTheme(preference)

    override suspend fun setHwidEnabled(enabled: Boolean) = settingsRepository.setHwidEnabled(enabled)

    override val pingMode: Flow<PingMode> = settingsRepository.pingMode
    override val pingCheckUrl: Flow<String> = settingsRepository.pingCheckUrl
    override val pingTimeoutSeconds: Flow<Int> = settingsRepository.pingTimeoutSeconds
    override val pingOnLaunch: Flow<Boolean> = settingsRepository.pingOnLaunch
    override val pingOnLaunchMetered: Flow<Boolean> = settingsRepository.pingOnLaunchMetered

    override suspend fun setPingMode(mode: PingMode) = settingsRepository.setPingMode(mode)

    override suspend fun setPingCheckUrl(url: String) = settingsRepository.setPingCheckUrl(url)

    override suspend fun setPingTimeoutSeconds(seconds: Int) = settingsRepository.setPingTimeoutSeconds(seconds)

    override suspend fun setPingOnLaunch(enabled: Boolean) = settingsRepository.setPingOnLaunch(enabled)

    override suspend fun setPingOnLaunchMetered(enabled: Boolean) =
        settingsRepository.setPingOnLaunchMetered(enabled)

    override val dnsResolver: Flow<DnsResolver> = settingsRepository.dnsResolver

    override suspend fun setDnsResolver(resolver: DnsResolver) = settingsRepository.setDnsResolver(resolver)

    override val dnsOverriddenByProfile: Flow<Boolean> =
        combine(
            settingsRepository.activeRoutingRuleSetId,
            routingRepository.observeAllStored(),
        ) { activeId, ruleSets ->
            dnsOverriddenByProfile(ruleSets.firstOrNull { it.ruleSet.id == activeId }?.dns)
        }

    override val geoRefreshOnMetered: Flow<Boolean> = settingsRepository.geoRefreshOnMetered

    override suspend fun setGeoRefreshOnMetered(enabled: Boolean) =
        settingsRepository.setGeoRefreshOnMetered(enabled)

    override val selectedGeoSourceIds: Flow<Set<String>> = settingsRepository.selectedGeoSourceIds

    override suspend fun setSelectedGeoSourceIds(ids: Set<String>) =
        settingsRepository.setSelectedGeoSourceIds(ids)

    override val bootAutostart: Flow<Boolean> = settingsRepository.bootAutostart

    override suspend fun setBootAutostart(enabled: Boolean) = settingsRepository.setBootAutostart(enabled)

    override val failClosed: Flow<Boolean> = settingsRepository.failClosed

    override suspend fun setFailClosed(enabled: Boolean) = settingsRepository.setFailClosed(enabled)

    override val batteryPromptShown: Flow<Boolean> = settingsRepository.batteryPromptShown

    override suspend fun setBatteryPromptShown(shown: Boolean) = settingsRepository.setBatteryPromptShown(shown)

    override val perTagBreakdown: Flow<Boolean> = settingsRepository.perTagBreakdown

    override suspend fun setPerTagBreakdown(enabled: Boolean) = settingsRepository.setPerTagBreakdown(enabled)

    override suspend fun isIgnoringBatteryOptimizations(): Boolean =
        withContext(Dispatchers.IO) {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
}

/** True only when a valid active profile materially changes DNS behavior (R21). */
internal fun dnsOverriddenByProfile(dns: ProfileDns?): Boolean =
    dns != null &&
        !dns.isInvalid &&
        (dns.hasResolver || dns.hosts.isNotEmpty() || dns.fakeDns == true)
