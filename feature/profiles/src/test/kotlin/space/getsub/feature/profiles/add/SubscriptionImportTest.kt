// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.add

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import space.getsub.core.data.sync.SubscriptionSyncFailure
import space.getsub.core.data.sync.SyncResult
import space.getsub.feature.profiles.R

/**
 * `SubscriptionSyncFailure`, not `:core:network`'s `FetchFailure` directly —
 * a branch review found the original `FetchFailure`-typed version of this
 * file forced `:core:data`'s `:core:network` dependency to `api`, which made
 * `checkModuleBoundaries`'s "only `:core:data` may depend on `:core:network`"
 * rule pass on paper while every downstream module actually had the type
 * resolvable. `SubscriptionSyncFailure` is `:core:data`'s own 1:1 translation
 * — see its KDoc — so this file only ever needs `:core:data` on its
 * classpath, which it already has.
 */
class SubscriptionImportTest {
    @Test
    fun aSuccessfulSyncReportsRowsWrittenNotServersParsed() {
        // M3's device run established the failure mode: "Imported 0 of 1" with
        // no reason. The count must be rows that landed, and ProfileRepository
        // .import already returns distinct rows written for exactly this.
        val message = SyncResult.Synced(
            added = 12,
            updated = 0,
            removed = 0,
            keptActive = 0,
            rejectedDirectives = 0,
        ).toUserMessage()

        message.resId shouldBe R.plurals.subscription_added
        message.quantity shouldBe 12
    }

    @Test
    fun aRefreshThatChangesNothingDoesNotSayAddedZeroServers() {
        // M4's device run. The provider's list was unchanged, six rows were rewritten in place,
        // and the screen read "Added 0 servers" — which a user reads as failure. Reporting
        // `added` alone is only correct for a first sync.
        val message = SyncResult.Synced(
            added = 0,
            updated = 6,
            removed = 0,
            keptActive = 0,
            rejectedDirectives = 0,
        ).toUserMessage()

        message.resId shouldBe R.plurals.subscription_refreshed
        message.quantity shouldBe 6
    }

    @Test
    fun aRefreshThatOnlyRemovesReportsTheRemoval() {
        val message = SyncResult.Synced(
            added = 0,
            updated = 0,
            removed = 2,
            keptActive = 0,
            rejectedDirectives = 0,
        ).toUserMessage()

        message.resId shouldBe R.plurals.subscription_removed
        message.quantity shouldBe 2
    }

    @Test
    fun anEmptyButSuccessfulSyncStillReadsAsSuccess() {
        val message = SyncResult.Synced(
            added = 0,
            updated = 0,
            removed = 0,
            keptActive = 0,
            rejectedDirectives = 0,
        ).toUserMessage()

        message.resId shouldBe R.string.subscription_up_to_date
        message.quantity shouldBe null
    }

    @Test
    fun anAdditionWinsOverAConcurrentRemoval() {
        val message = SyncResult.Synced(
            added = 3,
            updated = 1,
            removed = 2,
            keptActive = 0,
            rejectedDirectives = 0,
        ).toUserMessage()

        message.resId shouldBe R.plurals.subscription_added
        message.quantity shouldBe 3
    }

    @Test
    fun hwidRequiredMapsToTheDeviceIdStringNotAGenericOne() {
        // The milestone's exit criterion, at the layer the user reads. §A.4.1:
        // a bare 404 is the worst outcome and is what most clients give today.
        SyncResult.Failed(SubscriptionSyncFailure.HwidRequired).toUserMessage().resId shouldBe
            R.string.subscription_error_hwid_required
    }

    @Test
    fun deviceLimitReachedMapsToADifferentStringFromHwidRequired() {
        SyncResult.Failed(SubscriptionSyncFailure.DeviceLimitReached).toUserMessage().resId shouldNotBe
            SyncResult.Failed(SubscriptionSyncFailure.HwidRequired).toUserMessage().resId
    }

    @Test
    fun notFoundPointsAtTheUrlNotAtHwid() {
        SyncResult.Failed(SubscriptionSyncFailure.NotFound).toUserMessage().resId shouldBe
            R.string.subscription_error_not_found
    }

    @Test
    fun everySubscriptionSyncFailureMapsToADistinctString() {
        // §10.4: a generic failure is not a diagnosis. If two of these collapse
        // to one resource, the taxonomy exists in the type system and in
        // nothing the user can see.
        val ids = SubscriptionSyncFailure.entries.map { SyncResult.Failed(it).toUserMessage().resId }

        ids.toSet().size shouldBe SubscriptionSyncFailure.entries.size
    }

    @Test
    fun noMessageCarriesAFormatArgumentThatCouldHoldAUrl() {
        // §5.6. The failure branch takes no arguments at all, so there is
        // nowhere for a URL or a body to be interpolated.
        SubscriptionSyncFailure.entries.forEach {
            SyncResult.Failed(it).toUserMessage().quantity shouldBe null
        }
    }
}
