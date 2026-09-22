// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.getsub.core.model.ConnectionState
import space.getsub.service.TunnelClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The narrow slice of tunnel state Settings needs: whether a session is currently up (F2 /
 * ruling R39).
 *
 * `TunnelService.startCore` reads a diagnostic setting like per-tag breakdown once, at connect —
 * `ARCHITECTURE.md` §10.4's principle is that a diagnostic must not tear down a working tunnel to
 * apply itself, so [SettingsViewModel] cannot simply push a changed setting into a running
 * session. What it can do is tell the user honestly whether a change just took effect or is
 * waiting for the next reconnect, which needs to know whether a session is up in the first place.
 *
 * Deliberately not [space.getsub.feature.home.TunnelConnection]: `:feature:*` modules never
 * depend on each other (`ARCHITECTURE.md` §4, enforced by `checkModuleBoundaries` — see
 * `feature/settings/build.gradle.kts`'s own comment on the `:core:xray` edge for the same rule
 * from the other side), so Settings gets its own interface over the same underlying
 * [TunnelClient], sized to exactly what it needs — no `connect`/`disconnect`, no latency, no
 * traffic. Settings never drives the tunnel.
 */
internal interface TunnelSessionSource {
    /**
     * ARCHITECTURE.md §5.5: a mirror of what the service reported, never a value this screen
     * infers on its own.
     */
    val state: StateFlow<ConnectionState>
}

/**
 * Adapts the AIDL-backed [TunnelClient] — already a `@Singleton`, so this reuses the same bound
 * instance `:main`'s `MainActivity` binds/unbinds, not a second binder connection.
 */
@Singleton
internal class BoundTunnelSessionSource
@Inject
constructor(
    private val client: TunnelClient,
) : TunnelSessionSource {
    override val state: StateFlow<ConnectionState> get() = client.state
}

/**
 * [SettingsViewModel]'s constructor default — the same "add an optional capability without
 * touching every existing caller" shape [space.getsub.service.TrafficSamplerLoop]'s own
 * `readTags` parameter uses. Every test that does not care about connection state (the large
 * majority of [SettingsViewModelTest]) keeps compiling unchanged; production wiring always goes
 * through [BoundTunnelSessionSource] via [SettingsModule] regardless of this default, since Hilt
 * resolves constructor parameters from bindings and does not consult Kotlin default values.
 *
 * "Disconnected" is the correct universal default, not an arbitrary placeholder: every one of
 * F2's pending-reconnect mechanics is a no-op with nothing connected, so a [SettingsViewModel]
 * built against this never shows a pending notice it would have no session to attach to.
 */
internal object DisconnectedTunnelSessionSource : TunnelSessionSource {
    override val state: StateFlow<ConnectionState> =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected).asStateFlow()
}
