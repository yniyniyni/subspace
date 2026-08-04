// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.add

import androidx.compose.ui.test.junit4.v2.createComposeRule
import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.parser.ParseFailure
import art.yniyniyni.subspace.core.parser.ParseFailureReason
import art.yniyniyni.subspace.core.parser.parseFailure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Rule
import org.junit.Test

/**
 * Covers [failureText]: an exhaustive `when` over [FailureDetail] and
 * [DetailField] (§7's closed vocabulary), so every variant renders and none
 * of them can carry the input that produced it (§5.6).
 *
 * Instrumented, not a plain JVM test, despite the task brief's own file list
 * grouping it under `src/test` alongside `ImportViewModelTest.kt` — deviation
 * #3 in the task report. [failureText] is `@Composable` and calls
 * `stringResource()`, which needs a real composition and Android resources;
 * this project has no Robolectric dependency (adding one for a single test
 * file is a disproportionate new third-party dependency per §10.7), and the
 * brief's own "Two corrections" section already anticipates exactly this
 * class of test needing the *instrumented* DEX-040 camelCase rule, not the
 * JVM one — which only makes sense if this file is meant to run through D8,
 * i.e. as an `androidTest`. Same `v2 createComposeRule` and camelCase-only
 * naming as [art.yniyniyni.subspace.core.ui.component.GroupCardTest] and
 * [art.yniyniyni.subspace.core.ui.component.ConnectControlTest] — there is no
 * `@Suppress("DEPRECATION")` here or anywhere else in this codebase.
 *
 * `setContent` may run only once per test (the compose test rule throws on a
 * second call), so every case below renders all the [ParseFailure]s it needs
 * in one composition and asserts on the resulting list/string afterward,
 * rather than calling [failureText] once per `setContent`.
 */
class FailureTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    // One ParseFailure per FailureDetail variant. index/reason are arbitrary
    // (this test is about detail -> text, not reason -> text) but distinct
    // enough that a bug conflating two variants would still be visible.
    private fun allFailureDetailVariants(): List<ParseFailure> =
        listOf(
            parseFailure(0, ParseFailureReason.EmptyInput, FailureDetail.None),
            parseFailure(
                1,
                ParseFailureReason.InvalidRealityKey,
                FailureDetail.Length(DetailField.PublicKey, expected = 43, actual = 12),
            ),
            parseFailure(
                2,
                ParseFailureReason.InvalidPort,
                FailureDetail.Range(DetailField.Port, min = 1, max = 65_535, actual = 70_000),
            ),
            parseFailure(3, ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.Password)),
            parseFailure(4, ParseFailureReason.UnsupportedMethod, FailureDetail.Unsupported(DetailField.Method)),
            parseFailure(5, ParseFailureReason.MalformedUri, FailureDetail.Malformed(DetailField.JsonBody)),
        )

    @Test
    fun everyFailureDetailVariantRendersToADistinctString() {
        val variants = allFailureDetailVariants()
        var rendered: List<String> = emptyList()

        composeRule.setContent {
            rendered = variants.map { failureText(it) }
        }
        composeRule.waitForIdle()

        rendered.distinct().size shouldBe rendered.size
    }

    @Test
    fun noRenderedFailureContainsThePastedInput() {
        // §5.6 as a test. FailureDetail has no free-text channel, so this
        // holds by construction — asserted anyway, because the type could
        // grow one. 198.51.100.0/24 is RFC 5737 documentation space: a
        // plausible-looking address that must never appear regardless of
        // which field the failure is about.
        val anyFailure = parseFailure(0, ParseFailureReason.MalformedUri, FailureDetail.Malformed(DetailField.Address))
        var rendered = ""

        composeRule.setContent {
            rendered = failureText(anyFailure)
        }
        composeRule.waitForIdle()

        rendered shouldNotContain "198.51.100"
    }

    @Test
    fun theEntryNumberShownIsOneBasedNotTheRawZeroBasedIndex() {
        // ParseFailure.index is 0-based (its own KDoc); a user reading "Entry
        // 0 failed" for the very first line would be confusing, so the
        // rendered text adds one. Pinned directly rather than inferred from
        // the distinctness test above.
        val firstEntry = parseFailure(0, ParseFailureReason.UnknownScheme, FailureDetail.None)
        var rendered = ""

        composeRule.setContent {
            rendered = failureText(firstEntry)
        }
        composeRule.waitForIdle()

        rendered shouldNotContain "Entry 0"
    }
}
