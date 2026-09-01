// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class RuleSetProvenanceTest {
    @Test
    fun routingSourceKindsRoundTripEveryStableWireValue() {
        // These strings are frozen once shipped (RuleSetProvenance.kt's own KDoc: `wireValue` is
        // the stable value persisted in Room). Kept as an explicit map, not derived from
        // `kind.wireValue` itself, so a typo that silently changes an *existing* member's wire
        // string is caught here too, not just a member missing one entirely.
        val frozenWireValues =
            mapOf(
                RoutingSourceKind.Deeplink to "deeplink",
                RoutingSourceKind.Clipboard to "clipboard",
                RoutingSourceKind.Qr to "qr",
                RoutingSourceKind.Header to "header",
                RoutingSourceKind.Body to "body",
                RoutingSourceKind.Conversion to "conversion",
            )

        // Exhaustive over RoutingSourceKind.entries rather than trusting the map above to have
        // kept up: a member added without a row here must fail this test now, not silently ship
        // unguarded — fromWireValue returns null for a wire value nobody registered, and that
        // value is persisted to the database.
        frozenWireValues.keys shouldBe RoutingSourceKind.entries.toSet()

        RoutingSourceKind.entries.forEach { kind ->
            val wireValue = frozenWireValues.getValue(kind)
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
