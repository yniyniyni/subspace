// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// File groups TerminalStateMemory's persistence seam with its codec functions,
// not just the interface — see TerminalStateMemory.kt, which is what actually
// consumes all three.
@file:Suppress("MatchingDeclarationName", "Filename")

package space.getsub.service

import space.getsub.core.data.SettingsRepository
import space.getsub.core.model.FailureReason

/**
 * The minimal shape [TerminalStateMemory] persists for a terminal
 * `ConnectionState.Failed` (ARCHITECTURE.md §11 row 7).
 *
 * Not `ConnectionState.Failed` itself: that type's constructor is private, reachable
 * only through `failure()`, which re-redacts — exactly what reconstructing a
 * persisted row should do (`redact()` is idempotent, ARCHITECTURE.md §5.6 /
 * `core/model/Redaction.kt`'s own KDoc). Keeping the persisted shape as a plain
 * pair of primitives, rather than the domain type, is also what keeps
 * [encodeFailureReason]/[decodeFailureReason] free of any dependency on how
 * `ConnectionState.Failed` is built.
 *
 * @property detail already redacted. Guaranteed by `ConnectionState.Failed`'s
 *   constructor for every value that can reach [TerminalStateMemory.record].
 */
internal data class PersistedFailure(
    val reason: FailureReason,
    val detail: String,
)

/** The wire form [decodeFailureReason] reads back: the enum's own name. */
internal fun encodeFailureReason(reason: FailureReason): String = reason.name

/**
 * Decodes a stored `FailureReason.name`, or null for "no persisted failure".
 *
 * Defensive by design, not merely defensive in style: a garbage, blank, or
 * future-version name (an enum value added after this row was written, then the
 * app downgraded) must read as "none" rather than throw. `FailureReason.valueOf`
 * throws on exactly that input, so this never calls it directly.
 */
internal fun decodeFailureReason(name: String?): FailureReason? =
    name?.let { stored -> FailureReason.entries.firstOrNull { it.name == stored } }

/**
 * The persistence seam [TerminalStateMemory] writes and reads through.
 *
 * A plain interface rather than [SettingsRepository] directly, for the same
 * reason [BoundPassthroughValidator] and [SessionIntentGate] take lambdas/seams
 * instead of their real collaborators: it is what lets
 * `TerminalStateMemoryTest` drive the write-on-change and ordering rules with a
 * fake, with no Room instance and no `:core:data` dependency in the test.
 */
internal interface TerminalFailurePersistence {
    /** The persisted failure, or null if none is outstanding, or the stored row was garbage. */
    suspend fun load(): PersistedFailure?

    /** Persists [failure], or clears the row when [failure] is null. */
    suspend fun save(failure: PersistedFailure?)
}

/**
 * The production [TerminalFailurePersistence], over [SettingsRepository]'s
 * key/value pair — ARCHITECTURE.md §3: Room, never `androidx.datastore`.
 */
internal class RoomTerminalFailurePersistence(
    private val settingsRepository: SettingsRepository,
) : TerminalFailurePersistence {
    override suspend fun load(): PersistedFailure? {
        val (reasonName, detail) = settingsRepository.lastTerminalFailure()
        val reason = decodeFailureReason(reasonName) ?: return null
        return PersistedFailure(reason, detail.orEmpty())
    }

    override suspend fun save(failure: PersistedFailure?) {
        settingsRepository.setLastTerminalFailure(
            reasonName = failure?.reason?.let(::encodeFailureReason),
            detail = failure?.detail,
        )
    }
}
