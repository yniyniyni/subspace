// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.sync

import art.yniyniyni.subspace.core.network.FetchFailure

/**
 * Why a subscription sync failed, translated out of [FetchFailure] at the
 * `:core:data` boundary.
 *
 * §4: only `:core:data` may depend on `:core:network`. [SyncResult.Failed]
 * is part of `:core:data`'s own public API — every `:feature:*` module and
 * `:app` consumes it — so if it carried [FetchFailure] directly, every
 * consumer of [SyncResult] would need [FetchFailure] on its own compile
 * classpath too. Gradle's `api`/`implementation` split makes that
 * mechanically possible (an `api` dependency propagates transitively) but
 * `checkModuleBoundaries` only inspects each module's own *declared*
 * `project(...)` dependencies, so it cannot see a type that arrived this
 * way — the rule would still say "only `:core:data` depends on
 * `:core:network`" while every module downstream of `:core:data` actually
 * had the type resolvable and compilable. This type is the fix: a small,
 * closed enum `:core:data` owns outright, translated from [FetchFailure]
 * once, here, where `:core:network` is legitimately visible.
 *
 * Deliberately a 1:1 mirror of [FetchFailure], not a smaller or
 * re-bucketed set — collapsing two [FetchFailure] members into one case
 * here would be indistinguishable from collapsing them in the UI layer,
 * which is exactly what Task 18's exit criterion (a device with HWID
 * enforcement off must read "This subscription requires a device ID,"
 * never a generic failure) forbids. [toSyncFailure] is the total,
 * order-preserving translation; detekt's `NoUnusedImports`/an exhaustive
 * `when` on this file catches drift if [FetchFailure] ever gains a member
 * and this one is not updated to match.
 */
public enum class SubscriptionSyncFailure {
    HwidRequired,
    NotFound,
    DeviceLimitReached,
    Unreachable,
    TimedOut,
    TlsFailure,
    ClientError,
    ServerError,
}

/** Total, 1:1 translation — see [SubscriptionSyncFailure]'s own KDoc for why this exists. */
internal fun FetchFailure.toSyncFailure(): SubscriptionSyncFailure =
    when (this) {
        FetchFailure.HwidRequired -> SubscriptionSyncFailure.HwidRequired
        FetchFailure.NotFound -> SubscriptionSyncFailure.NotFound
        FetchFailure.DeviceLimitReached -> SubscriptionSyncFailure.DeviceLimitReached
        FetchFailure.Unreachable -> SubscriptionSyncFailure.Unreachable
        FetchFailure.TimedOut -> SubscriptionSyncFailure.TimedOut
        FetchFailure.TlsFailure -> SubscriptionSyncFailure.TlsFailure
        FetchFailure.ClientError -> SubscriptionSyncFailure.ClientError
        FetchFailure.ServerError -> SubscriptionSyncFailure.ServerError
    }
