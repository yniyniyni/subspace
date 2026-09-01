// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser.directive

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

/**
 * Which piece of work reads a key. [None] means stored, not yet acted on (spec D2).
 *
 * **Named, not numbered, and that is the point.** These were `M4`…`M9` until the
 * 2026-08-20 roadmap renumber inserted raw-JSON passthrough as M7 and pushed three
 * milestones up one. Forty references in `DirectiveRegistry` silently began naming
 * the wrong milestone — silently, because the members still existed. Semantic names
 * cannot rot that way: a milestone can be renumbered without touching this file.
 */
public enum class Consumer {
    /** Subscriptions as a directive channel (was M4). */
    Subscriptions,

    /** Latency testing and sorting (was M4.5). */
    LatencySorting,

    /** Rule-based routing and geo assets (was M5). No rows today; the vocabulary is complete on purpose. */
    Routing,

    /** Routing profiles as deeplinks (was M6). */
    RoutingProfiles,

    /** The DNS half of a routing profile (M6.5). */
    ProfileDns,

    /** Raw Xray JSON passthrough execution (M7). */
    Passthrough,

    /** Always-on, boot, counters, log viewer (was M7). */
    PlatformHardening,

    /** Fragmentation, noises, fronting, migration (was M8). */
    CensorshipResistance,

    /** Localisation, metadata surface, distribution (was M9). */
    Release,

    /** Stored and validated, acted on by nobody. */
    None,
}

/** One registry row. */
public data class DirectiveSpec(
    val key: String,
    val kind: DirectiveKind,
    val disposition: Disposition,
    val danger: Danger,
    val consumer: Consumer,
)
