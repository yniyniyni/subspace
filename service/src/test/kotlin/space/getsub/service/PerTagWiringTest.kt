// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerTagWiringTest {
    @Test
    fun `no port is allocated while the breakdown is off`() {
        assertNull(metricsPortFor(breakdownEnabled = false, allocate = { 41234 }))
    }

    @Test
    fun `a port is allocated while the breakdown is on`() {
        assertEquals(41234, metricsPortFor(breakdownEnabled = true, allocate = { 41234 }))
    }

    @Test
    fun `an allocation failure disables the breakdown rather than the session`() {
        // A diagnostic that can abort a connect is worse than one that is absent.
        assertNull(metricsPortFor(breakdownEnabled = true, allocate = { null }))
    }
}
