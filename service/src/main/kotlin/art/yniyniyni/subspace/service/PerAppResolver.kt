// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PerAppSelection

/** What the start sequence should do about per-app routing (§8). */
internal sealed interface PerAppResolution {
    /** Every app uses the tunnel. M1's proven shape, and the only mode that predates M5.5. */
    data object Off : PerAppResolution

    /** @property packages non-empty, and guaranteed not to contain our own package. */
    data class Selected(
        val mode: PerAppMode,
        val packages: Set<String>,
    ) : PerAppResolution

    /**
     * Allow-list mode with nothing left to allow.
     *
     * Its own resolution rather than [Off] because the two are opposites: `Off`
     * tunnels everything, this would tunnel nothing. Starting it produces
     * "connected, no traffic, no error" — §10.1's signature — so it is refused
     * with a named failure instead (Task 6).
     */
    data object EmptyAllowList : PerAppResolution
}

/**
 * Decides the per-app half of a start attempt.
 *
 * Takes suspend lambdas rather than the repository itself, for the reason
 * [RoutingResolver] does: `PerAppRepository` and `SettingsRepository` have
 * `internal` constructors scoped to `:core:data`, so `:service` cannot build one,
 * and this project carries no mocking library (§10.7 does not justify adding one
 * for two reads). That indirection is what makes [PerAppResolverTest] a plain JVM
 * test rather than an instrumented one.
 *
 * @param ownPackage a lambda rather than a `String` so the test need not stand up
 *   a `Context`, and so the value is read at resolve time rather than captured at
 *   service construction.
 */
internal class PerAppResolver(
    private val selection: suspend () -> PerAppSelection,
    private val ownPackage: () -> String,
) {
    /**
     * §8's own-package rule is enforced here as well as in `InstalledAppsSource`,
     * deliberately. The picker filter stops a user selecting us; this stops a
     * stored row, a downgrade, or Part 2's directive channel from doing it
     * anyway. In [PerAppMode.AllowList] nothing else can: calling
     * `addDisallowedApplication` alongside `addAllowedApplication` throws, so
     * there is no explicit exclusion to fall back on.
     */
    @Suppress("ReturnCount") // One early return per outcome; see the comments on each.
    suspend fun resolve(): PerAppResolution {
        val current = selection()
        if (current.mode == PerAppMode.Off) return PerAppResolution.Off

        val packages = current.packages - ownPackage()
        if (packages.isNotEmpty()) return PerAppResolution.Selected(current.mode, packages)

        // Nothing left after stripping. Which empty means what depends on the mode,
        // and getting this backwards is the difference between "everything is
        // tunnelled" and "nothing is".
        return when (current.mode) {
            PerAppMode.AllowList -> PerAppResolution.EmptyAllowList
            else -> PerAppResolution.Off
        }
    }
}
