// SPDX-License-Identifier: AGPL-3.0-or-later
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
    private val subscriptions = ReferenceCountedMutexes<Long>()
    private val profiles = ReferenceCountedMutexes<String>()
    private val settings = Mutex()

    suspend fun <T> withImport(
        profileName: String,
        subscriptionId: Long?,
        block: suspend () -> T,
    ): T =
        if (subscriptionId == null) {
            profiles.withLock(profileName, block)
        } else {
            subscriptions.withLock(subscriptionId) {
                profiles.withLock(profileName, block)
            }
        }

    suspend fun <T> withSubscription(
        subscriptionId: Long,
        block: suspend () -> T,
    ): T = subscriptions.withLock(subscriptionId, block)

    suspend fun <T> withProfiles(
        profileNames: Collection<String>,
        block: suspend () -> T,
    ): T = withProfiles(profileNames.distinct().sorted(), index = 0, block)

    suspend fun <T> withSettings(block: suspend () -> T): T = settings.withLock { block() }

    private suspend fun <T> withProfiles(
        names: List<String>,
        index: Int,
        block: suspend () -> T,
    ): T =
        if (index == names.size) {
            block()
        } else {
            profiles.withLock(names[index]) {
                withProfiles(names, index + 1, block)
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
