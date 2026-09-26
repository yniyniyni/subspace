// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.feature.settings.log.shouldFollow

class LogFollowTest {
    @Test
    fun `the first load follows to the end`() {
        shouldFollow(lastVisibleIndex = -1, previousSize = 0) shouldBe true
    }

    @Test
    fun `a reader at the bottom follows new lines`() {
        shouldFollow(lastVisibleIndex = 99, previousSize = 100) shouldBe true
    }

    @Test
    fun `a reader who scrolled up is not pulled down`() {
        shouldFollow(lastVisibleIndex = 40, previousSize = 100) shouldBe false
    }
}
