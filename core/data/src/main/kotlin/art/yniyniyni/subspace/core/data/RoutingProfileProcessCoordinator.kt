// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide ordering for routing-profile mutations.
 *
 * This is deliberately an object rather than an injected instance. Hilt normally
 * creates one importer, but tests and other in-process callers may construct more;
 * every instance must still participate in the same lifecycle lock.
 *
 * Lock order is fixed and must never be inverted: subscription id, sorted profile
 * names, then routing-selection settings. Key reservations are reference-counted
 * before waiting and released in [NonCancellable], so cancellation cannot leak a
 * key or replace a mutex while another waiter still references it.
 */
internal object RoutingProfileProcessCoordinator {
    private val profileLockAuthority = Any()
    private val subscriptions = ReferenceCountedMutexes<Long>()
    private val profiles = ReferenceCountedMutexes<String>()
    private val settings = Mutex()

    suspend fun <T> withImport(
        profileName: String,
        subscriptionId: Long?,
        block: suspend (ProfileLocks) -> T,
    ): T =
        if (subscriptionId == null) {
            withProfiles(listOf(profileName), block)
        } else {
            subscriptions.withLock(subscriptionId) {
                withProfiles(listOf(profileName), block)
            }
        }

    suspend fun <T> withSubscription(
        subscriptionId: Long,
        block: suspend () -> T,
    ): T = subscriptions.withLock(subscriptionId, block)

    suspend fun <T> withProfiles(
        profileNames: Collection<String>,
        block: suspend (ProfileLocks) -> T,
    ): T {
        val names = profileNames.distinct().sorted()
        val locks = ProfileLocks(names.toSet(), profileLockAuthority)
        return withProfileLocks(names, index = 0) {
            try {
                block(locks)
            } finally {
                locks.revoke(profileLockAuthority)
            }
        }
    }

    suspend fun <T> withSettings(block: suspend () -> T): T = settings.withLock { block() }

    private suspend fun <T> withProfileLocks(
        names: List<String>,
        index: Int,
        block: suspend () -> T,
    ): T =
        if (index == names.size) {
            block()
        } else {
            profiles.withLock(names[index]) {
                withProfileLocks(names, index + 1, block)
            }
        }

    /** Unforgeable, revocable proof that the enclosing block owns these profile-name locks. */
    internal class ProfileLocks internal constructor(
        private val names: Set<String>,
        authority: Any,
    ) {
        private var active = true

        init {
            check(authority === profileLockAuthority) { "Routing profile lock capability is not forgeable" }
        }

        internal fun holds(name: String): Boolean = active && name in names

        internal fun requireHeld(name: String) {
            check(holds(name)) { "Routing profile name lock is not held" }
        }

        internal fun revoke(authority: Any) {
            check(authority === profileLockAuthority) { "Only the lock coordinator can revoke this capability" }
            active = false
        }
    }
}

/** A keyed mutex map whose entries exist only while an owner or waiter references them. */
private class ReferenceCountedMutexes<K> {
    private val registry = Mutex()
    private val entries = mutableMapOf<K, Entry>()

    suspend fun <T> withLock(
        key: K,
        block: suspend () -> T,
    ): T {
        val entry =
            registry.withLock {
                entries.getOrPut(key, ::Entry).also { it.references += 1 }
            }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                registry.withLock {
                    entry.references -= 1
                    if (entry.references == 0 && entries[key] === entry) entries.remove(key)
                }
            }
        }
    }

    private class Entry {
        val mutex = Mutex()
        var references: Int = 0
    }
}
