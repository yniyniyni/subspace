// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.sync

import io.kotest.matchers.shouldBe
import org.junit.Test

class SubscriptionKeyTest {
    @Test
    fun `a unique name is the key`() {
        subscriptionKeysFor(listOf("Tokyo", "Osaka", "Kyoto")) shouldBe
            listOf("Tokyo", "Osaka", "Kyoto")
    }

    @Test
    fun `every occurrence of a duplicated name takes an ordinal, not just the later ones`() {
        // The part worth being explicit about. Keying the first duplicate by
        // bare name and the rest by ordinal means a provider inserting a server
        // above them shifts every ordinal and the whole group churns on a
        // refresh that changed one entry.
        subscriptionKeysFor(listOf("Tokyo", "Tokyo", "Osaka")) shouldBe
            listOf("Tokyo#0", "Tokyo#1", "Osaka")
    }

    @Test
    fun `an unnamed server takes its ordinal`() {
        subscriptionKeysFor(listOf("Tokyo", "", "Osaka")) shouldBe
            listOf("Tokyo", "#1", "Osaka")
    }

    @Test
    fun `blank names are treated as absent`() {
        subscriptionKeysFor(listOf("   ", "Osaka")) shouldBe listOf("#0", "Osaka")
    }

    @Test
    fun `a literal name that mimics an ordinal key does not collide with it`() {
        // The reviewer's counterexample against the unescaped `<name>#<ordinal>`
        // scheme: "Tokyo" x2 generates "Tokyo#0" and "Tokyo#1" as ordinal keys,
        // and a third row literally named "Tokyo#0" is unique on its own, so it
        // would take the bare key "Tokyo#0" — identical to the first row's
        // generated key. Escaping closes this: the literal name's `#` is
        // escaped, so its key can never equal an ordinal-generated one.
        val keys = subscriptionKeysFor(listOf("Tokyo", "Tokyo", "Tokyo#0"))

        keys.size shouldBe keys.toSet().size
    }

    @Test
    fun `keys stay unique across an adversarial mix, including names already containing the escape sequence`() {
        val names =
            listOf(
                "Tokyo",
                "Tokyo",
                "Tokyo#0",
                "Tokyo#1",
                "Tokyo\\#0",
                "",
                "",
                "#0",
                "#1",
                "\\",
                "\\\\",
                "a#b",
                "a#b",
            )

        val keys = subscriptionKeysFor(names)

        keys.size shouldBe keys.toSet().size
    }

    @Test
    fun `names differing only in case are distinct names`() {
        // Only trimming is specified; case folding is a deliberate non-goal.
        // Pinned so a later refactor doesn't fold case and silently change
        // refresh identity for a provider whose casing varies between fetches.
        subscriptionKeysFor(listOf("Tokyo", "tokyo")) shouldBe listOf("Tokyo", "tokyo")
    }

    @Test
    fun `a unique name keeps its key when an unrelated server is inserted above it`() {
        // The property that makes this rule worth having: a provider adding a
        // server must not re-key the servers that did not change, or every
        // refresh loses every row's connection history.
        val before = subscriptionKeysFor(listOf("Tokyo", "Osaka"))
        val after = subscriptionKeysFor(listOf("Seoul", "Tokyo", "Osaka"))

        after.contains(before[0]) shouldBe true
        after.contains(before[1]) shouldBe true
    }

    @Test
    fun `names differing only in surrounding whitespace are the same name`() {
        subscriptionKeysFor(listOf("Tokyo", " Tokyo ")) shouldBe
            listOf("Tokyo#0", "Tokyo#1")
    }

    @Test
    fun `an empty list produces an empty list`() {
        subscriptionKeysFor(emptyList()) shouldBe emptyList()
    }
}
