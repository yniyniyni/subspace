// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * [changedProfileIds] is the fix for review round 2's Important 6: [BoundProfileSource.syncSubscription]
 * used to scope core validation with a plain `Set<String>` difference over `rawJson` text, which
 * is blind to row identity. These tests pin the id-keyed replacement directly, since that is
 * where the bug actually lived — a full [BoundProfileSource] wiring test would need a real
 * [space.getsub.core.data.ProfileRepository]/[space.getsub.core.data.SubscriptionRepository]/
 * [space.getsub.core.data.sync.SubscriptionSyncer] (all `internal`-constructor classes
 * in `:core:data`, reachable only through Room-backed instrumented fixtures, not a plain JVM
 * fake), which is disproportionate to what this bug actually was: a two-line diff algorithm.
 */
class ProfileSourceSyncScopingTest {
    // The exact scenario review round 2 described: a sync adds a new row (id 2) whose rawJson is
    // byte-identical to an already-eligible row (id 1) already present in the group. A content-set
    // diff (`after.keys.map{after[it]}.toSet() - before...toSet()`) would compute an empty
    // difference here, since both sets contain only "SHARED" — silently dropping the new row.
    @Test
    fun `a newly added row is flagged even when its rawJson matches an already-eligible row elsewhere`() {
        val before = mapOf(1L to "SHARED")
        val after = mapOf(1L to "SHARED", 2L to "SHARED")

        changedProfileIds(before, after) shouldBe setOf(2L)
    }

    @Test
    fun `an existing row whose rawJson changed is flagged`() {
        val before = mapOf(1L to "OLD")
        val after = mapOf(1L to "NEW")

        changedProfileIds(before, after) shouldBe setOf(1L)
    }

    @Test
    fun `an unchanged row is not re-flagged`() {
        val before = mapOf(1L to "SAME", 2L to "ALSO-SAME")
        val after = mapOf(1L to "SAME", 2L to "ALSO-SAME")

        changedProfileIds(before, after).shouldBeEmpty()
    }

    @Test
    fun `a row present only before (dropped from the group) is not flagged — nothing to validate`() {
        val before = mapOf(1L to "GONE")
        val after = emptyMap<Long, String>()

        changedProfileIds(before, after).shouldBeEmpty()
    }
}
