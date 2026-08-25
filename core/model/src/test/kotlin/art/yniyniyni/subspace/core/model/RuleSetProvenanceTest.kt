// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class RuleSetProvenanceTest {
    @Test
    fun routingSourceKindsRoundTripEveryStableWireValue() {
        val expected =
            listOf(
                RoutingSourceKind.Deeplink to "deeplink",
                RoutingSourceKind.Clipboard to "clipboard",
                RoutingSourceKind.Qr to "qr",
                RoutingSourceKind.Header to "header",
                RoutingSourceKind.Body to "body",
            )

        expected.forEach { (kind, wireValue) ->
            kind.wireValue shouldBe wireValue
            RoutingSourceKind.fromWireValue(wireValue) shouldBe kind
        }
    }

    @Test
    fun routingSourceKindRejectsUnknownAndNullWireValues() {
        RoutingSourceKind.fromWireValue("Deeplink") shouldBe null
        RoutingSourceKind.fromWireValue("unknown") shouldBe null
        RoutingSourceKind.fromWireValue("") shouldBe null
        RoutingSourceKind.fromWireValue(null) shouldBe null
    }
}
