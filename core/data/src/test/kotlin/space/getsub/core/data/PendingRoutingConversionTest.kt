// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.model.RoutingProfile
import space.getsub.core.parser.routing.ConversionDrop
import space.getsub.core.parser.routing.RoutingConversion

/**
 * Task 15: the in-memory channel the editor's "Use this config's routing rules" action hands a
 * converted [RoutingConversion] to the routing import review sheet through — the conversion
 * counterpart of [PendingRoutingImportTest]'s link.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingRoutingConversionTest {
    private val pending = PendingRoutingConversion()

    private val first = RoutingConversion(profile = RoutingProfile(name = "First"), drops = emptyMap())
    private val second =
        RoutingConversion(
            profile = RoutingProfile(name = "Second"),
            drops = mapOf(ConversionDrop.BalancerRule to 1),
        )

    @Test
    fun nothingIsPendingUntilSomethingIsOffered() =
        runTest {
            pending.conversion.first() shouldBe null
        }

    @Test
    fun anOfferedConversionIsVisibleToTheScreen() =
        runTest {
            pending.offer(first)

            pending.conversion.first() shouldBe first
        }

    // A second conversion could in principle be offered while the first is being handed to the
    // sheet — consume must not discard it unread, same as PendingRoutingImport.consume.
    @Test
    fun consumingClearsOnlyTheConversionThatWasTaken() =
        runTest {
            pending.offer(first)
            pending.offer(second)

            pending.consume(first)

            pending.conversion.first() shouldBe second
        }

    @Test
    fun consumingTheCurrentConversionClearsIt() =
        runTest {
            pending.offer(first)

            pending.consume(first)

            pending.conversion.first() shouldBe null
        }
}
