// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import androidx.room.withTransaction
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Coordinates database, setting, and generation-tree removal for imported routing profiles. */
@Singleton
internal class RoutingProfileDeletion
@Inject
constructor(
    private val database: SubspaceDatabase,
    private val repository: RoutingRepository,
    private val assets: RuleSetAssets,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
) {
    /** Deletes one rule set without racing an import or a newer active selection. */
    suspend fun deleteRuleSet(id: Long) {
        val initial = repository.stored(id)
        if (initial == null) {
            RoutingProfileProcessCoordinator.withSettings {
                settings.clearActiveRoutingRuleSetIf(id)
            }
            removeTrees(listOf(id))
            return
        }
        RoutingProfileProcessCoordinator.withProfiles(listOf(initial.ruleSet.name)) {
            val current = repository.stored(id)
            if (current != null) {
                RoutingProfileProcessCoordinator.withSettings {
                    database.withTransaction {
                        repository.delete(id)
                        settings.clearActiveRoutingRuleSetIf(id)
                    }
                }
            }
            removeTrees(listOf(id))
        }
    }

    /**
     * Deletes a subscription and every routing profile it owns.
     *
     * The subscription lock prevents a concurrent import carrying [subscriptionId]
     * from appearing after the ownership snapshot. Profile locks are then acquired
     * in sorted order before the Room cascade and setting compare-and-clear.
     */
    suspend fun deleteSubscription(
        subscriptionId: Long,
        groupId: Long,
    ) {
        RoutingProfileProcessCoordinator.withSubscription(subscriptionId) {
            val initial = repository.storedForSubscription(subscriptionId)
            RoutingProfileProcessCoordinator.withProfiles(initial.map { it.ruleSet.name }) {
                val owned = repository.storedForSubscription(subscriptionId)
                val ids = owned.map { it.ruleSet.id }
                RoutingProfileProcessCoordinator.withSettings {
                    database.withTransaction {
                        profiles.deleteGroup(groupId)
                        ids.forEach { settings.clearActiveRoutingRuleSetIf(it) }
                    }
                }
                removeTrees(ids)
            }
        }
    }

    private suspend fun removeTrees(ids: List<Long>) {
        withContext(NonCancellable) {
            ids.forEach { assets.removeSet(it) }
        }
    }
}
