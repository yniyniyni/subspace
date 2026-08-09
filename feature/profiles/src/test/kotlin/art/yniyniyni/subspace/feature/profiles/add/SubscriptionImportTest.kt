// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.add

import art.yniyniyni.subspace.core.data.sync.SyncResult
import art.yniyniyni.subspace.core.network.FetchFailure
import art.yniyniyni.subspace.feature.profiles.R
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

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
    fun hwidRequiredMapsToTheDeviceIdStringNotAGenericOne() {
        // The milestone's exit criterion, at the layer the user reads. §A.4.1:
        // a bare 404 is the worst outcome and is what most clients give today.
        SyncResult.Failed(FetchFailure.HwidRequired).toUserMessage().resId shouldBe
            R.string.subscription_error_hwid_required
    }

    @Test
    fun deviceLimitReachedMapsToADifferentStringFromHwidRequired() {
        SyncResult.Failed(FetchFailure.DeviceLimitReached).toUserMessage().resId shouldNotBe
            SyncResult.Failed(FetchFailure.HwidRequired).toUserMessage().resId
    }

    @Test
    fun notFoundPointsAtTheUrlNotAtHwid() {
        SyncResult.Failed(FetchFailure.NotFound).toUserMessage().resId shouldBe
            R.string.subscription_error_not_found
    }

    @Test
    fun everyFetchFailureMapsToADistinctString() {
        // §10.4: a generic failure is not a diagnosis. If two of these collapse
        // to one resource, the taxonomy exists in the type system and in
        // nothing the user can see.
        val ids = FetchFailure.entries.map { SyncResult.Failed(it).toUserMessage().resId }

        ids.toSet().size shouldBe FetchFailure.entries.size
    }

    @Test
    fun noMessageCarriesAFormatArgumentThatCouldHoldAUrl() {
        // §5.6. The failure branch takes no arguments at all, so there is
        // nowhere for a URL or a body to be interpolated.
        FetchFailure.entries.forEach {
            SyncResult.Failed(it).toUserMessage().quantity shouldBe null
        }
    }
}
