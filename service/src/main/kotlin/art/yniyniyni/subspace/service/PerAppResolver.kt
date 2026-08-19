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
 * [RoutingResolver] does: `:service` cannot build a `PerAppRepository` by hand.
 * Its own constructor is public, but it takes a `SettingsRepository`, whose
 * constructor **is** `internal` to `:core:data` (§3) — so the barrier is
 * transitive rather than direct, and no less real for it. This project carries no
 * mocking library either (§10.7 does not justify adding one for two reads). That
 * indirection is what makes [PerAppResolverTest] a plain JVM test rather than an
 * instrumented one.
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

/**
 * What to call on `VpnService.Builder`, decided without touching one.
 *
 * The variants are exhaustive and non-overlapping **by construction**, which is
 * the point: a builder holds allowed applications or disallowed ones, never both
 * (spec §2.2). Making that a type rather than a discipline means the mistake
 * cannot be made in `establishTun()`, where it would surface only as an
 * `UnsupportedOperationException` on a device.
 */
internal sealed interface BuilderPlan {
    /** Pre-M5.5 behaviour: exclude ourselves and nothing else. */
    data object DisallowOwnOnly : BuilderPlan

    /** Exclude ourselves **and** these. Deny-list mode. */
    data class Disallow(val packages: Set<String>) : BuilderPlan

    /**
     * Include only these. Allow-list mode.
     *
     * Our own package is absent and must stay absent — in this mode exclusion is
     * structural, because `addDisallowedApplication` cannot be called at all.
     */
    data class Allow(val packages: Set<String>) : BuilderPlan
}

/** @return null for [PerAppResolution.EmptyAllowList] — that start is refused, not planned. */
internal fun builderPlan(resolution: PerAppResolution): BuilderPlan? =
    when (resolution) {
        PerAppResolution.Off -> BuilderPlan.DisallowOwnOnly
        PerAppResolution.EmptyAllowList -> null
        is PerAppResolution.Selected ->
            when (resolution.mode) {
                PerAppMode.AllowList -> BuilderPlan.Allow(resolution.packages)
                else -> BuilderPlan.Disallow(resolution.packages)
            }
    }

/**
 * The outcome of offering a [BuilderPlan]'s packages to a `VpnService.Builder`.
 *
 * @property requested how many packages the plan carried.
 * @property skipped how many the builder refused because they are no longer
 *   installed. §8 says skip and continue: one package uninstalled since it was
 *   selected must not abort tunnel setup.
 */
internal data class PackageApplication(
    val requested: Int,
    val skipped: Int,
) {
    /**
     * Nothing at all reached the builder.
     *
     * Cosmetic for a deny list — excluding nothing extra degrades towards
     * [BuilderPlan.DisallowOwnOnly], which is the safe direction. **Fatal for an
     * allow list.** `VpnService.Builder.addAllowedApplication` verifies the
     * package *before* it lazily creates the allowed-applications list
     * (`android/net/VpnService.java`, ~806-817), so a run in which every add
     * threw leaves that list null — and a null list means no per-app filtering
     * at all, i.e. every installed app tunnelled. The allow-list arm
     * deliberately calls no `addDisallowedApplication` (spec §2.2), so nothing
     * else would exclude us either: the user's "only these apps" would silently
     * invert into "everything, including ourselves" — §8's rule broken and
     * §5.1's loop built, reported as one `Log.w` with a count.
     */
    val nothingApplied: Boolean get() = skipped >= requested
}

/**
 * Offers each package to [add] in turn, counting instead of aborting.
 *
 * Split out of `establishTun` so the all-skipped case is decided somewhere a JVM
 * test can reach: a `VpnService.Builder` cannot be constructed off-device, but
 * this can be handed a lambda that refuses everything.
 *
 * @param add false when the package is no longer installed. Never throws — the
 *   caller swallows `NameNotFoundException`, because the only thing it carries
 *   is the package name and §5.6 forbids logging that.
 */
internal fun applyEach(
    packages: Set<String>,
    add: (String) -> Boolean,
): PackageApplication =
    PackageApplication(
        requested = packages.size,
        skipped = packages.count { !add(it) },
    )
