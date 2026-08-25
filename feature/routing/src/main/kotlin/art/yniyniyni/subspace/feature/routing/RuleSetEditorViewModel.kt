// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingEntries
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Why the most recent [RuleSetEditorViewModel.save] failed, or (via a `null`
 * [RuleSetEditorState.saveProblem]) that it did not fail. Fix round 1,
 * Finding 7/8: [save] used to let [RoutingSource.upsert]'s
 * [IllegalArgumentException] and any Room write failure propagate out of
 * `viewModelScope.launch` uncaught, crashing the process (§10.4 wants a
 * specific message, not a crash), and did not detect the one write it can
 * silently corrupt data on — see [NameConflict].
 */
internal sealed interface SaveProblem {
    /**
     * [RoutingSource.upsert] rejected an entry. [addEntry] should have
     * stopped this before it ever reached [save] — this exists for a row
     * written before a validation tightening, or restored from a backup,
     * whose entries were never re-validated by [RuleSetEditorViewModel.load].
     */
    data object InvalidEntry : SaveProblem

    /** The write itself failed — Room, storage, or another unexpected exception. */
    data object WriteFailed : SaveProblem

    /**
     * A different rule set is already named [name].
     * `RoutingRuleSetDao.upsertByIdOrName`'s `id == 0` branch resolves a
     * fresh entity **by name** and updates that row's buckets, order and
     * strategy in place — deliberate for M6's "importing a name that already
     * exists is an update" semantics, but interactive create is not import,
     * and this editor is the first UI to reach that branch. [save] checks
     * [RoutingSource.ruleSetNamed] itself and refuses rather than letting the
     * collision happen silently.
     *
     * [name] is exempt from §5.6 the same way [RuleSetRow.name] is: a label
     * the user chose to identify a rule set, not a value it routes.
     */
    data class NameConflict(val name: String) : SaveProblem
}

/**
 * One rule set's working copy — the same "hold a local draft, write through
 * only on explicit save" shape
 * [art.yniyniyni.subspace.feature.profiles.editor.EditorState] uses, so
 * abandoning this screen changes nothing (brief step 4).
 *
 * @param siteCategories the parsed contents of `geosite.json` beside the geo
 *   root's `geosite.dat`, or empty when that sidecar does not exist or does
 *   not parse — see [GeoCategories.read]. Drives the SITES fields' "browse
 *   categories" affordance; empty means that affordance stays disabled.
 * @param ipCategories the same, for `geoip.json` / the IPS fields.
 * @param saving true from the moment [RuleSetEditorViewModel.save] starts its
 *   write until it resolves — [RuleSetEditorViewModel.save]'s own re-entrancy
 *   guard (fix round 1, Minor): without it two fast taps on Save before the
 *   first write resolves issue two upserts.
 * @param saveProblem why the most recent [RuleSetEditorViewModel.save] call
 *   failed, or `null` — see [SaveProblem]'s own KDoc.
 */
internal data class RuleSetEditorState(
    val loading: Boolean = true,
    val id: Long = 0L,
    val name: String = "",
    val buckets: Map<RouteOutcome, RuleBucket> = emptyMap(),
    val order: List<RouteOutcome> = RoutingRuleSet.DEFAULT_ORDER,
    val domainStrategy: DomainStrategy = DomainStrategy.IP_IF_NON_MATCH,
    val globalProxy: Boolean? = null,
    val siteCategories: List<GeoCategory> = emptyList(),
    val ipCategories: List<GeoCategory> = emptyList(),
    val saving: Boolean = false,
    val saveProblem: SaveProblem? = null,
    val saved: Boolean = false,
) {
    /** The stored entries for [outcome]/[field]. §5.6: this is the screen's whole job — display, never log. */
    fun bucket(
        outcome: RouteOutcome,
        field: BucketField,
    ): List<String> {
        val ruleBucket = buckets[outcome] ?: RuleBucket()
        return when (field) {
            BucketField.SITES -> ruleBucket.sites
            BucketField.IPS -> ruleBucket.ips
        }
    }

    /** [field]'s parsed category list — see [siteCategories]/[ipCategories]'s own KDoc. */
    fun categoriesFor(field: BucketField): List<GeoCategory> =
        when (field) {
            BucketField.SITES -> siteCategories
            BucketField.IPS -> ipCategories
        }

    /** The one save gate the brief specifies (step 4): a non-blank name, nothing more. */
    val canSave: Boolean get() = name.isNotBlank()
}

/**
 * Backs the rule set editor: a working copy of one
 * [art.yniyniyni.subspace.core.model.RoutingRuleSet], validated per entry as
 * it is typed and written through [RoutingSource.upsert] only on [save].
 *
 * [RoutingSource.upsert] **throws** [IllegalArgumentException] on an entry
 * [RoutingEntries.problemWith] rejects — [addEntry] is the guard that must
 * keep such an entry from ever reaching it (brief's "Interfaces" note).
 */
@HiltViewModel
internal class RuleSetEditorViewModel
@Inject
constructor(
    private val source: RoutingSource,
) : ViewModel() {
    private val _state = MutableStateFlow(RuleSetEditorState())
    val state: StateFlow<RuleSetEditorState> = _state.asStateFlow()

    /**
     * Loads [id] — an existing row, or [id] left in place as a fresh draft when
     * no row matches (the create-new-rule-set sentinel `NEW_RULE_SET` in `:app`
     * is `0L`, [RoutingRuleSet]'s own default `id`, so this module needs no
     * knowledge of that constant itself; §4 forbids depending on `:app` anyway).
     * Also reads both category sidecars once — see
     * [RuleSetEditorState.siteCategories]'s own KDoc and [RoutingSource.categoriesFor]
     * for why the filesystem read is dispatched inside [source], not here.
     */
    fun load(id: Long) {
        viewModelScope.launch {
            val existing = source.ruleSet(id)
            val siteCategories = source.categoriesFor(BucketField.SITES)
            val ipCategories = source.categoriesFor(BucketField.IPS)
            _state.value =
                (existing?.toEditorState() ?: RuleSetEditorState(loading = false, id = id))
                    .copy(loading = false, siteCategories = siteCategories, ipCategories = ipCategories)
        }
    }

    /**
     * Validates [entry] for [outcome]/[field] and, only if it passes, adds it —
     * never both rejects and adds (brief: "A rejected entry is never added").
     * A duplicate of an entry already in the bucket is silently ignored rather
     * than reported as a problem: it is not malformed, it would just add
     * nothing.
     *
     * Reports nothing about *why* a rejected [entry] was refused — per-field live validation in
     * [RuleSetEditorScreen] calls [RoutingEntries.problemWith] itself for that, reading straight
     * from the field's own draft text rather than this state (branch review: `entryProblem` used to
     * live here too, set and tested but never read by the screen once that live path shipped; dead
     * state removed rather than left as false confidence).
     */
    fun addEntry(
        outcome: RouteOutcome,
        field: BucketField,
        entry: String,
    ) {
        if (RoutingEntries.problemWith(entry, field) != null) return
        val trimmed = entry.trim()
        _state.update { current ->
            val existing = current.bucket(outcome, field)
            if (trimmed in existing) return@update current
            current.copy(
                buckets = current.buckets + (outcome to current.bucketWith(outcome, field, existing + trimmed)),
            )
        }
    }

    /** Removes [entry] from [outcome]/[field], leaving the rest of the bucket in order. A no-op if absent. */
    fun removeEntry(
        outcome: RouteOutcome,
        field: BucketField,
        entry: String,
    ) {
        _state.update { current ->
            val existing = current.bucket(outcome, field)
            current.copy(buckets = current.buckets + (outcome to current.bucketWith(outcome, field, existing - entry)))
        }
    }

    fun setName(value: String) = update { it.copy(name = value) }

    /**
     * Sets the rule evaluation order. Silently ignored when [order] is not a
     * permutation of every [RouteOutcome] — [RoutingRuleSet]'s own `init`
     * requires that, and this is the guard that keeps [save] from ever
     * constructing one that would throw building the draft in the first place.
     */
    fun setOrder(order: List<RouteOutcome>) {
        val isPermutation = order.size == RouteOutcome.entries.size && order.toSet() == RouteOutcome.entries.toSet()
        if (isPermutation) update { it.copy(order = order) }
    }

    fun setDomainStrategy(strategy: DomainStrategy) = update { it.copy(domainStrategy = strategy) }

    /**
     * Writes the current draft through [RoutingSource.upsert]. Refuses when
     * [RuleSetEditorState.canSave] is false — the same "a disabled control is a
     * hint, the gate belongs here too" reasoning
     * [RoutingViewModel.activate][RoutingViewModel]'s own KDoc documents — or
     * while a previous call is still in flight ([RuleSetEditorState.saving]).
     *
     * Both [RoutingSource] calls this makes — [RoutingSource.ruleSetNamed] for
     * the collision check (fix round 1, Finding 8 — see
     * [SaveProblem.NameConflict]'s own KDoc) and [RoutingSource.upsert] for the
     * write itself — run inside the one `try` in [saveDraft]. Fix round 2,
     * Finding 10: an earlier version of this method ran the collision read
     * *outside* that `try`, on every save, unconditionally — reopening exactly
     * the uncaught-crash defect Finding 7 closed for the write, for the read
     * right next to it in the same function. Anything either call throws now
     * becomes a [SaveProblem] (never a crash), and [RuleSetEditorState.saving]
     * is reset on every exit path, including both failure ones —
     * [CancellationException] is the one exception to both: rethrown
     * unmodified, never turned into a [SaveProblem] or used to reset `saving`,
     * since a cancelled `viewModelScope` means this [ViewModel] is being torn
     * down and further state updates are moot.
     */
    fun save() {
        val current = _state.value
        if (!current.canSave || current.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveProblem = null) }
            saveDraft(current, current.name.trim())
        }
    }

    // TooGenericExceptionCaught: either RoutingSource call below can fail in ways this ViewModel
    // cannot enumerate (§10.4).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun saveDraft(
        current: RuleSetEditorState,
        trimmedName: String,
    ) {
        try {
            val collision = source.ruleSetNamed(trimmedName)
            if (collision != null && collision.id != current.id) {
                _state.update { it.copy(saving = false, saveProblem = SaveProblem.NameConflict(trimmedName)) }
                return
            }
            source.upsert(
                RoutingRuleSet(
                    id = current.id,
                    name = trimmedName,
                    buckets = current.buckets,
                    order = current.order,
                    domainStrategy = current.domainStrategy,
                    globalProxy = current.globalProxy,
                ),
            )
            _state.update { it.copy(saving = false, saved = true) }
        } catch (_: IllegalArgumentException) {
            _state.update { it.copy(saving = false, saveProblem = SaveProblem.InvalidEntry) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update { it.copy(saving = false, saveProblem = SaveProblem.WriteFailed) }
        }
    }

    private inline fun update(transform: (RuleSetEditorState) -> RuleSetEditorState) {
        _state.update(transform)
    }
}

private fun RuleSetEditorState.bucketWith(
    outcome: RouteOutcome,
    field: BucketField,
    entries: List<String>,
): RuleBucket {
    val current = buckets[outcome] ?: RuleBucket()
    return when (field) {
        BucketField.SITES -> current.copy(sites = entries)
        BucketField.IPS -> current.copy(ips = entries)
    }
}

private fun RoutingRuleSet.toEditorState(): RuleSetEditorState =
    RuleSetEditorState(
        loading = false,
        id = id,
        name = name,
        buckets = buckets,
        order = order,
        domainStrategy = domainStrategy,
        globalProxy = globalProxy,
    )
