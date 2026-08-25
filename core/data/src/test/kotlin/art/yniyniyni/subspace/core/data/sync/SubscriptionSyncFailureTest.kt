// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data.sync

import art.yniyniyni.subspace.core.network.FetchFailure
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The seam between the two failure vocabularies.
 *
 * This module is the only one that can see both — §4 keeps `:core:network` behind `:core:data` —
 * which makes it the only place the two can be checked against each other.
 */
class SubscriptionSyncFailureTest {
    @Test
    fun theTwoFailureVocabulariesCannotDrift() {
        // The scheduler's retry pacing turns on parsing a PERSISTED string back into a
        // SubscriptionSyncFailure. That string is written as FetchFailure.name
        // (SubscriptionSyncer.recordFailure), and nothing in the type system ties the two enums'
        // *names* together: the bridge is an exhaustive `when`, which keeps compiling happily
        // through a rename because it simply maps the new name onto the unchanged member.
        //
        // What breaks is silent and remote from the rename. RefreshScheduler.isTransientFailure
        // does SubscriptionSyncFailure.valueOf(stored) inside a runCatching; on a mismatch it
        // yields null, every network failure is classified permanent, and every transient failure
        // waits a full provider interval instead of fifteen minutes. No exception, no failing
        // build, no log line — just staleness. This assertion is the tripwire.
        FetchFailure.entries.map { it.name } shouldBe SubscriptionSyncFailure.entries.map { it.name }
    }

    @Test
    fun everyFetchFailureTranslatesToItsOwnSyncFailure() {
        // Total and injective: no member silently collapses onto another's meaning (§7's taxonomy
        // is closed, and a collapse here would show the user the wrong diagnosis).
        val translated = FetchFailure.entries.map { it.toSyncFailure() }

        translated.distinct().size shouldBe FetchFailure.entries.size
        translated.map { it.name } shouldBe FetchFailure.entries.map { it.name }
    }
}
