// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsSettingsTest {
    @Test
    fun `the breakdown defaults to off`() =
        runTest {
            val state = SettingsState()
            assertEquals(false, state.perTagBreakdown)
        }
}
