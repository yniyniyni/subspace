// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.component

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Test

// Plain JVM unit test, not androidTest — FLOATING_NAV_CONTENT_BOTTOM_PADDING
// is a pure value, no Compose runtime or Android framework needed to read
// it, so this belongs in src/test like ColorTest.kt, not src/androidTest.
// Backtick test names are fine here (unlike androidTest): this source set
// compiles for the host JVM via plain JUnit, never through D8/AGP's Android
// dexing pipeline, so the DEX-040 space-in-synthetic-class-name constraint
// FloatingNavigationBarTest.kt's own comment describes does not apply.
class FloatingNavigationBarConstantsTest {
    @Test
    fun `the public bottom-padding constant is 104dp, per the design system's floating-chrome note`() {
        // A caller imports this constant to reserve space, not its literal
        // value — so a silent edit here would only ever be caught by a test
        // that pins the actual number, not by any usage site.
        FLOATING_NAV_CONTENT_BOTTOM_PADDING shouldBe 104.dp
    }
}
