// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser.directive

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class UserInfoTest {
    @Test
    fun `parses the documented example`() {
        // ARCHITECTURE.md §A.1's own example, verbatim.
        val info = parseUserInfo("upload=0; download=2153701362; total=0; expire=1790951622")

        info shouldBe
            UserInfo(
                upload = 0,
                download = 2_153_701_362,
                total = 0,
                expiresAtEpochSeconds = 1_790_951_622,
            )
    }

    @Test
    fun `tolerates missing spaces and extra whitespace`() {
        parseUserInfo("upload=1;download=2;total=3;expire=4") shouldBe
            UserInfo(1, 2, 3, 4)
        parseUserInfo("  upload = 1 ;  download = 2  ") shouldBe
            UserInfo(1, 2, null, null)
    }

    @Test
    fun `a missing field is null, not zero`() {
        // "the provider did not say" and "the provider said zero" are different
        // facts, and only the second should draw a bar.
        parseUserInfo("download=5")?.total.shouldBeNull()
    }

    @Test
    fun `an unparseable field is null rather than failing the whole header`() {
        // §7's never-throw philosophy: one bad field must not lose the others.
        val info = parseUserInfo("upload=abc; download=5")

        info?.upload.shouldBeNull()
        info?.download shouldBe 5
    }

    @Test
    fun `garbage returns null`() {
        parseUserInfo("").shouldBeNull()
        parseUserInfo("not a userinfo header").shouldBeNull()
    }

    @Test
    fun `total of zero means unlimited`() {
        // Rendering 100% used on an unlimited plan is worse than rendering
        // nothing at all.
        parseUserInfo("download=5; total=0")?.isUnlimited shouldBe true
        parseUserInfo("download=5; total=100")?.isUnlimited shouldBe false
    }

    @Test
    fun `used bytes are upload plus download`() {
        parseUserInfo("upload=3; download=7; total=100")?.usedBytes shouldBe 10L
    }

    @Test
    fun `a negative or overflowing value is treated as absent`() {
        parseUserInfo("download=-1")?.download.shouldBeNull()
        parseUserInfo("download=99999999999999999999")?.download.shouldBeNull()
    }

    // Fix round, Minor 1: duplicate-key behaviour was previously undocumented
    // and untested — pinned here against Iterable.toMap()'s own documented
    // last-wins contract, which UserInfo.kt's KDoc now cites explicitly.
    @Test
    fun `a duplicate key keeps its last occurrence`() {
        parseUserInfo("total=1;total=2")?.total shouldBe 2
    }

    // Fix round, Minor 2: these two hostile-input shapes were previously only
    // exercised incidentally (as part of larger strings in other tests), not
    // asserted on directly.
    @Test
    fun `a field present with an empty value is treated as absent`() {
        parseUserInfo("total=;download=5")?.total.shouldBeNull()
    }

    @Test
    fun `a key with no equals sign is ignored`() {
        parseUserInfo("upload;download=5")?.upload.shouldBeNull()
    }
}
