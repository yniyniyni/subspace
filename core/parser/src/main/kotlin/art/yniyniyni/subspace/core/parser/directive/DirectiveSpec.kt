// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.directive

/**
 * How much damage acting on a directive could do.
 *
 * ARCHITECTURE.md §A.1 requires directives that are dangerous by nature to get
 * explicit user confirmation in this project, even though Happ applies them
 * silently. Carrying the classification on the key means M5 and M8 inherit that
 * requirement rather than re-deriving it, and means no consumer has to guess.
 */
public enum class Danger {
    /** Display and cosmetic values. */
    Benign,

    /** Changes app behaviour but cannot redirect traffic or reach a new host. */
    Sensitive,

    /**
     * Can redirect traffic, cause a network call to an attacker-chosen host, or
     * change what is proxied. **No `Dangerous` key is acted on in M4** — see
     * `DirectiveRegistryTest`, which enforces it.
     */
    Dangerous,
}

/**
 * Whether a key is stored at all.
 *
 * Deliberately separate from [Danger]: they answer different questions. A key on
 * Appendix D's cut list is rejected because honouring it costs zero
 * compatibility (§A.5), not because it is dangerous. Collapsing the two would
 * make `Rejected` read as a fourth danger level, which it is not.
 */
public sealed interface Disposition {
    public data object Accept : Disposition

    /** @property note why it was cut, recorded at the point of rejection. */
    public data class Reject(
        val note: String,
    ) : Disposition
}

/** The milestone that reads a key. [None] means stored, not yet acted on (spec D2). */
public enum class Consumer { M4, M4_5, M5, M6, M7, M8, M9, None }

/** One registry row. */
public data class DirectiveSpec(
    val key: String,
    val kind: DirectiveKind,
    val disposition: Disposition,
    val danger: Danger,
    val consumer: Consumer,
)
