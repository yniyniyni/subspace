// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.core.ui.format.formatByteCount

class ByteCountTest {
    @Test fun `bytes below a kilobyte`() = assertEquals("512 B", formatByteCount(512))

    @Test fun `kilobytes`() = assertEquals("1.5 KB", formatByteCount(1_536))

    @Test fun `megabytes`() = assertEquals("2.0 MB", formatByteCount(2L * 1024 * 1024))

    @Test fun `gigabytes`() = assertEquals("3.5 GB", formatByteCount((3.5 * 1024 * 1024 * 1024).toLong()))

    @Test fun `zero is zero, never an em-dash`() = assertEquals("0 B", formatByteCount(0))

    @Test
    fun `past the 32-bit boundary`() {
        // Guards the whole delta-accumulation chain: a formatter that narrowed
        // to Int would undo spec §1.3 at the last step.
        assertEquals("8.0 GB", formatByteCount(8L * 1024 * 1024 * 1024))
    }
}
