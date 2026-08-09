// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.add

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.sync.SubscriptionSyncFailure
import art.yniyniyni.subspace.core.data.sync.SubscriptionSyncer
import art.yniyniyni.subspace.core.data.sync.SyncResult
import art.yniyniyni.subspace.core.parser.ParseFailure
import art.yniyniyni.subspace.core.parser.SubscriptionParser
import art.yniyniyni.subspace.core.parser.directive.DirectiveKind
import art.yniyniyni.subspace.core.parser.directive.KindResult
import art.yniyniyni.subspace.core.parser.directive.canonicalise
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import art.yniyniyni.subspace.feature.profiles.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * A user-facing message as a resource reference rather than a string.
 *
 * §12 forbids hardcoded UI text, and this shape additionally keeps the
 * [SyncResult] mapping unit-testable without a `Context`. [quantity] is
 * non-null only for the plural success case; the failure branch carries no
 * arguments at all, which is what guarantees no URL or body can be
 * interpolated into a message (§5.6).
 */
internal data class UserMessage(
    @param:StringRes @param:PluralsRes val resId: Int,
    val quantity: Int? = null,
)

/**
 * Maps a subscription sync outcome to what the user reads, without ever
 * touching the subscription's own URL or the fetch response body (§5.6) —
 * [SyncResult]'s four variants carry only counts and a closed
 * [SubscriptionSyncFailure] vocabulary — `:core:data`'s own translation of
 * `:core:network`'s `FetchFailure`, never that type directly (see
 * [SubscriptionSyncFailure]'s own KDoc for why crossing that boundary
 * matters, not just style — a branch review caught this module reaching
 * for `FetchFailure` directly and traced it to a real §4 enforcement gap).
 *
 * All four variants are handled explicitly: [SyncResult.NoServers] and
 * [SyncResult.ReconciliationConflict] are easy to miss reading the plan text,
 * which sometimes names only [SyncResult.Synced] and [SyncResult.Failed].
 * [SyncResult.ReconciliationConflict] has no string of its own in the closed
 * vocabulary Step 3 defines — spec-wise it is the same class of outcome as
 * [SubscriptionSyncFailure.ServerError] (a write that could not be trusted to
 * have landed cleanly, not something the URL or the user did wrong), so it
 * reuses [R.string.subscription_error_server] rather than inventing a tenth
 * string for a backstop [SubscriptionSyncer.sync] itself says should be rare.
 */
internal fun SyncResult.toUserMessage(): UserMessage =
    when (this) {
        is SyncResult.Synced -> UserMessage(R.plurals.subscription_added, added)
        is SyncResult.Failed -> reason.toUserMessage()
        is SyncResult.NoServers -> UserMessage(R.string.subscription_error_no_servers)
        is SyncResult.ReconciliationConflict -> UserMessage(R.string.subscription_error_server)
    }

/** [SubscriptionSyncFailure]'s closed vocabulary, one distinct string per member (§10.4). */
private fun SubscriptionSyncFailure.toUserMessage(): UserMessage =
    when (this) {
        SubscriptionSyncFailure.HwidRequired -> UserMessage(R.string.subscription_error_hwid_required)
        SubscriptionSyncFailure.DeviceLimitReached -> UserMessage(R.string.subscription_error_device_limit)
        SubscriptionSyncFailure.NotFound -> UserMessage(R.string.subscription_error_not_found)
        SubscriptionSyncFailure.Unreachable -> UserMessage(R.string.subscription_error_unreachable)
        SubscriptionSyncFailure.TimedOut -> UserMessage(R.string.subscription_error_timed_out)
        SubscriptionSyncFailure.TlsFailure -> UserMessage(R.string.subscription_error_tls)
        SubscriptionSyncFailure.ClientError -> UserMessage(R.string.subscription_error_client)
        SubscriptionSyncFailure.ServerError -> UserMessage(R.string.subscription_error_server)
    }

/** True for an absolute `http`/`https` URL with a host — [DirectiveKind.Url]'s own rule. */
private fun isSubscriptionUrl(url: String): Boolean = DirectiveKind.Url.canonicalise(url) is KindResult.Canonical

/**
 * What [AddServerSheet] renders.
 *
 * ARCHITECTURE.md §7: a [ParseOutcome][art.yniyniyni.subspace.core.parser.ParseOutcome]
 * always has two halves, and 197 imported with 3 [failures] is a normal,
 * successful outcome — not an error state. [completed] distinguishes "nothing
 * attempted yet" from "attempted and imported nothing, entirely failures" (both
 * have `imported == 0`).
 *
 * [imported] and [parsed] are deliberately two different numbers (§10.1 fix,
 * element-provenance report): [parsed] is what [SubscriptionParser] produced;
 * [imported] is what [ProfileSource.import] actually persisted, which can be
 * lower — a raw Xray document whose elements collide only in fields the typed
 * projection cannot see (§6's lossy projection; `xhttp` path/host/mode used to
 * be an example and no longer is) stores fewer rows than it parsed
 * profiles. "Imported 15 of 15" must mean 15 rows landed, not 15
 * profiles parsed and only 1 actually stored — that was the bug ([total] and
 * the summary string both read off [parsed], not off what reached storage).
 *

 * [input] is the paste field's own text, held here rather than in
 * `rememberSaveable` (fix round 1, finding 3): the M1 predecessor this
 * file's own KDoc already cites, `HomeViewModel.parseInput`, kept it in
 * ViewModel state for the same reason before Task 17 retired it —
 * `rememberSaveable` marshals its value into the hosting Activity's
 * saved-instance-state `Bundle`, and a pasted config can carry server
 * addresses, UUIDs and REALITY material (§5.6). Plain (non-`SavedStateHandle`)
 * ViewModel state lives in process memory only and is gone on process death,
 * which is the right lifetime for this value — there is nothing here worth
 * surviving a process restart, and every byte of it is exactly the kind of
 * material §5.6 says must not sit somewhere it doesn't need to. [import]
 * clears [input] once an import actually lands a profile (see its own KDoc);
 * a failed attempt keeps the text so the user can see and fix what they
 * pasted.
 *
 * [fileReadFailed] is fix round 1, finding 2's surface for "the picked file
 * could not be read" — a stale/revoked URI, a provider I/O error, or a
 * permission revoked mid-flow. It carries nothing beyond the boolean itself:
 * an I/O exception's message can carry a path or provider detail, so it is
 * never read, let alone stored (§5.6).
 *
 * [subscriptionResult] is Task 13's fifth route, "From subscription URL" —
 * the outcome of [ImportViewModel.addSubscription]'s call to
 * [SubscriptionSyncer.sync], mapped through [toUserMessage] so it stays a
 * [UserMessage] (resource id, never raw text) rather than a second copy of
 * the paste path's [imported]/[parsed]/[failures] shape, which describes a
 * parse, not a sync. [busy] is shared with the paste/file/QR routes rather
 * than duplicated per route: only one of this sheet's operations can be in
 * flight at a time, since they share this one [ImportViewModel].
 */
internal data class ImportState(
    val input: String = "",
    val busy: Boolean = false,
    val completed: Boolean = false,
    val imported: Int = 0,
    val parsed: Int = 0,
    val failures: List<ParseFailure> = emptyList(),
    val fileReadFailed: Boolean = false,
    val subscriptionResult: UserMessage? = null,
) {
    /** What was attempted, for the "of N" half of the summary — always [parsed], never [imported]. */
    val total: Int get() = parsed + failures.size
}

@HiltViewModel
internal class ImportViewModel
@Inject
constructor(
    private val profileSource: ProfileSource,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** The paste field's `onValueChange` — see [ImportState.input]'s own KDoc. */
    fun onInputChanged(text: String) {
        _state.update { it.copy(input = text) }
    }

    /**
     * [AddServerSheet]'s "Import from file" flow calls this before reading
     * the picked document, so the busy indicator covers the file I/O too,
     * not only the parse — and so a previous attempt's stale result/failure
     * banner is cleared before the new one starts, the same reset [import]
     * does for the paste path.
     */
    fun beginFileRead() {
        _state.update { ImportState(input = it.input, busy = true) }
    }

    /**
     * Fix round 1, finding 2: the file picker could not produce readable
     * text — `openInputStream` returned `null` (a stale/revoked URI, which
     * some content providers do instead of throwing) or reading it threw.
     * Either way this is a *reported* outcome, never a silent one (§7/§10.4).
     * Takes no cause — see [ImportState.fileReadFailed]'s KDoc for why.
     */
    fun reportFileReadFailure() {
        _state.update { it.copy(busy = false, fileReadFailed = true) }
    }

    /**
     * Parses [raw] and persists whatever [SubscriptionParser] could make of it.
     *
     * §5.6/§10.4: never throws and never logs [raw] — a clipboard paste or an
     * imported file *is* config content. [ImportState.failures] carries only
     * [ParseFailure]'s closed vocabulary, never the input that produced it.
     */
    fun import(raw: String) {
        viewModelScope.launch {
            _state.update { ImportState(input = it.input, busy = true) }

            // §5.3: the whole pass — a SHA-256 plus regex validation per
            // entry, a YAML document parse per Clash entry — must not run on
            // viewModelScope's Main.immediate dispatcher. Default, not IO:
            // this is pure CPU work with no blocking call in it, and IO's
            // pool is sized for threads parked on syscalls (the M1
            // predecessor of this file, `HomeViewModel.parseInput`, made the
            // same call for the same reason before Task 17 retired it).
            val outcome = withContext(Dispatchers.Default) { SubscriptionParser.parse(raw) }

            // Element-provenance fix: `:core:parser` now attaches each profile's own
            // provenance (Profile.rawJson) as it parses, so this call no longer needs to
            // re-derive "was this raw JSON" from raw's own leading character — see
            // ProfileRepository.import's KDoc for what ProfileSource.import does with it,
            // and what the returned count means.
            val storedCount =
                if (outcome.profiles.isNotEmpty()) {
                    val groupId = profileSource.defaultGroupId()
                    profileSource.import(outcome.profiles, groupId)
                } else {
                    0
                }

            _state.update { current ->
                ImportState(
                    // Fix round 1, finding 3: clear the pasted secret material
                    // once it has actually landed a profile — a fully-failed
                    // attempt keeps it so the user can see and fix what they
                    // pasted, rather than losing it on every attempt.
                    input = if (outcome.profiles.isNotEmpty()) "" else current.input,
                    busy = false,
                    completed = true,
                    imported = storedCount,
                    parsed = outcome.profiles.size,
                    failures = outcome.failures,
                )
            }
        }
    }

    /**
     * Adds a subscription from [url] and runs its first sync.
     *
     * [url] is validated with [DirectiveKind.Url]'s own scheme restriction —
     * the same rule §A.1 applies to provider-supplied URL directives like
     * `new-url`/`fallback-url`, reused rather than re-implemented, because a
     * `file:` subscription URL typed by a user is the identical hazard in a
     * different place. A URL that fails this check is a silent no-op: this
     * sheet has no separate field to report the failure against, and §5.6
     * forbids echoing the text itself back in a message.
     *
     * [ProfileSource.addSubscription] (backed by
     * [art.yniyniyni.subspace.core.data.SubscriptionRepository.add]) is
     * idempotent on [url] — adding an already-stored URL again re-syncs the
     * existing row rather than creating a duplicate group, which is the
     * repository's own documented contract, not a special case here.
     *
     * A first sync that does not land as [SyncResult.Synced] — a failure, no
     * servers, or a reconciliation conflict — deletes the subscription again
     * ([ProfileSource.deleteSubscription]): a row whose very first fetch did
     * not succeed is a group the user did not ask for, and leaving it makes
     * "add" look like it half-worked.
     */
    fun addSubscription(url: String) {
        if (!isSubscriptionUrl(url)) return

        viewModelScope.launch {
            _state.update { ImportState(input = it.input, busy = true) }

            val id = profileSource.addSubscription(url = url, name = subscriptionHostName(url))
            val result = profileSource.syncSubscription(id)

            if (result !is SyncResult.Synced) {
                profileSource.deleteSubscription(id)
            }

            _state.update { it.copy(busy = false, subscriptionResult = result.toUserMessage()) }
        }
    }
}

/**
 * The group name shown until the provider's own `profile-title` (if any)
 * lands on the first sync. Always the URL's host, never the full URL — the
 * path or query string is where a subscription token typically lives
 * (§5.6). [ImportViewModel.addSubscription] only calls this after
 * [isSubscriptionUrl] has already accepted the same URL, which guarantees a
 * non-blank host (`DirectiveKind.Url`'s own rule) — `.orEmpty()` keeps this
 * total without a `!!` for the case that can't actually happen.
 */
private fun subscriptionHostName(url: String): String = java.net.URI(url).host.orEmpty()
