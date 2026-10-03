// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * ARCHITECTURE.md §5.5. A task-clearing relaunch of `MainActivity` runs the new
 * instance's `onStart` before the old instance's `onStop` (seen on a Pixel 8,
 * 2026-10-03). With one `ServiceConnection`, the second bind was a no-op and the
 * old instance's unbind dropped the only binding, so Home read "Disconnected"
 * for a whole connected session.
 */
class BindRefCountTest {
    @Test
    fun `the first acquire binds and later ones do not`() {
        val count = BindRefCount()
        count.acquire() shouldBe true
        count.acquire() shouldBe false
    }

    @Test
    fun `a new onStart before the old onStop keeps the binding`() {
        val count = BindRefCount()
        count.acquire() shouldBe true // old instance's onStart
        count.acquire() shouldBe false // new instance's onStart
        count.release() shouldBe false // old instance's onStop: someone still holds it
        count.release() shouldBe true // new instance's onStop: really unbind
    }

    @Test
    fun `a release with nothing held does nothing`() {
        val count = BindRefCount()
        count.release() shouldBe false
        count.acquire() shouldBe true
    }
}
