// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Task 14/16: the in-memory channel a deeplink and a provider directive both
 * reach the review sheet through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingRoutingImportTest {
    private val pending = PendingRoutingImport()

    @Test
    fun nothingIsPendingUntilSomethingIsOffered() = runTest {
        pending.link.first() shouldBe null
    }

    @Test
    fun anOfferedLinkIsVisibleToTheScreen() = runTest {
        pending.offer("happ://routing/off")

        pending.link.first() shouldBe "happ://routing/off"
    }

    // onNewIntent can deliver a second link at any point, including while the
    // first is being handed to the sheet. Clearing blindly would lose it.
    @Test
    fun consumingClearsOnlyTheLinkThatWasTaken() = runTest {
        pending.offer("first")
        pending.offer("second")

        pending.consume("first")

        pending.link.first() shouldBe "second"
    }

    @Test
    fun consumingTheCurrentLinkClearsIt() = runTest {
        pending.offer("first")

        pending.consume("first")

        pending.link.first() shouldBe null
    }

    // A provider's routing directive stays in the database after the sheet
    // shows it. Without this record, dismissing that sheet would only postpone
    // it until the user next opened Routing — and a Dangerous confirmation that
    // reappears every visit is one people learn to tap through.
    @Test
    fun aPresentedImportIsRememberedSoItDoesNotNag() = runTest {
        pending.presentedTexts.first().shouldBeEmpty()

        pending.markPresented("happ://routing/add/x")

        pending.presentedTexts.first() shouldContain "happ://routing/add/x"
    }

    @Test
    fun presentingOneImportDoesNotSuppressAnother() = runTest {
        pending.markPresented("happ://routing/add/x")

        pending.presentedTexts.first().contains("happ://routing/add/y") shouldBe false
    }
}
