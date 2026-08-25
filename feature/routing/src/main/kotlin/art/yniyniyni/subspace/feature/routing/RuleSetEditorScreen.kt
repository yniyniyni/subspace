// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's GroupCard.kt and SettingRow.kt for the same
// pattern. TooManyFunctions: this file's function count is the width of the
// form it renders — six entry-list sections, order, domain strategy, name —
// the same reasoning EditorScreen.kt's own suppression gives.
@file:Suppress("MagicNumber", "TooManyFunctions")

package art.yniyniyni.subspace.feature.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.EntryProblem
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingEntries

private val CONTENT_PADDING = 16.dp
private val FIELD_GAP = 12.dp
private val SECTION_GAP = 20.dp
private val ROW_ICON_GAP = 8.dp
private val LOADING_TOP_PADDING = 48.dp

/**
 * Identifies the loading spinner to `RuleSetEditorScreenContentTest` — same pattern as
 * [EditorScreen][art.yniyniyni.subspace.feature.profiles.editor.EditorScreen]'s own
 * `EDITOR_LOADING_TEST_TAG`.
 */
internal const val RULE_SET_EDITOR_LOADING_TEST_TAG = "rule-set-editor-loading"

/**
 * `:app`'s `RuleSetEditor(ruleSetId)` route — one rule set's editor. See
 * [RuleSetEditorViewModel]'s own KDoc for the working-copy/write-through-on-save shape.
 * [EntryProblem] is shown live, per field, from that field's own draft text — see
 * [RuleSetEditorViewModel.addEntry]'s KDoc for why no `RuleSetEditorState` field backs this
 * anymore (branch review: it used to, but nothing here ever read it).
 */
@Composable
fun RuleSetEditorScreen(
    ruleSetId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RuleSetEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(ruleSetId) { viewModel.load(ruleSetId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    RuleSetEditorContent(
        state = state,
        actions =
        RuleSetEditorActions(
            onBack = onDone,
            onNameChanged = viewModel::setName,
            onAddEntry = viewModel::addEntry,
            onRemoveEntry = viewModel::removeEntry,
            onMoveEarlier = { outcome -> viewModel.setOrder(state.order.moved(outcome, -1)) },
            onMoveLater = { outcome -> viewModel.setOrder(state.order.moved(outcome, 1)) },
            onDomainStrategyChanged = viewModel::setDomainStrategy,
            onSave = viewModel::save,
        ),
        modifier = modifier,
    )
}

/** Swaps the element at [outcome] with its neighbour [delta] positions away. A no-op at either end. */
private fun List<RouteOutcome>.moved(
    outcome: RouteOutcome,
    delta: Int,
): List<RouteOutcome> {
    val index = indexOf(outcome)
    val target = index + delta
    if (index < 0 || target !in indices) return this
    return toMutableList().apply {
        this[index] = this[target]
        this[target] = outcome
    }
}

/**
 * This screen's callbacks, grouped for the same reason
 * [EditorActions][art.yniyniyni.subspace.feature.profiles.editor.EditorActions] is.
 */
internal data class RuleSetEditorActions(
    val onBack: () -> Unit,
    val onNameChanged: (String) -> Unit,
    val onAddEntry: (RouteOutcome, BucketField, String) -> Unit,
    val onRemoveEntry: (RouteOutcome, BucketField, String) -> Unit,
    val onMoveEarlier: (RouteOutcome) -> Unit,
    val onMoveLater: (RouteOutcome) -> Unit,
    val onDomainStrategyChanged: (DomainStrategy) -> Unit,
    val onSave: () -> Unit,
)

/**
 * The stateless half — see
 * [art.yniyniyni.subspace.feature.home.HomeScreenContent]'s KDoc for why this
 * split exists.
 */
@Composable
internal fun RuleSetEditorContent(
    state: RuleSetEditorState,
    actions: RuleSetEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(onBack = actions.onBack)
        if (state.loading) {
            LoadingBody()
        } else {
            EditorForm(state = state, actions = actions)
        }
    }
}

@Composable
private fun EditorTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(CONTENT_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.rule_set_editor_back_description),
            )
        }
        Text(text = stringResource(R.string.rule_set_editor_title), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // No `contentDescription = ""`, unlike a first draft of this file (fix round 1, Minor):
        // that was copied from EditorScreen.kt's own LoadingBody, but there it pairs with a
        // testTag a test actually uses to find the node — without one, an empty description is
        // pure a11y harm (TalkBack still stops here and announces nothing) with no offsetting
        // benefit. RULE_SET_EDITOR_LOADING_TEST_TAG below is that missing pairing.
        CircularProgressIndicator(
            modifier =
            Modifier
                .padding(top = LOADING_TOP_PADDING)
                .testTag(RULE_SET_EDITOR_LOADING_TEST_TAG),
        )
    }
}

@Composable
private fun EditorForm(
    state: RuleSetEditorState,
    actions: RuleSetEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CONTENT_PADDING)
            .padding(bottom = CONTENT_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onNameChanged,
            label = { Text(stringResource(R.string.rule_set_editor_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OrderSection(order = state.order, onMoveEarlier = actions.onMoveEarlier, onMoveLater = actions.onMoveLater)

        DomainStrategySection(selected = state.domainStrategy, onSelected = actions.onDomainStrategyChanged)

        state.order.forEach { outcome ->
            BucketField.entries.forEach { field ->
                EntrySection(
                    outcome = outcome,
                    field = field,
                    entries = state.bucket(outcome, field),
                    categories = state.categoriesFor(field),
                    onAdd = { entry -> actions.onAddEntry(outcome, field, entry) },
                    onRemove = { entry -> actions.onRemoveEntry(outcome, field, entry) },
                )
            }
        }

        // Save-time-only problems (fix round 1, Findings 7/8) — a rejected-entry-while-typing
        // problem is shown inline on its own field below, never here (Finding 4).
        if (state.saveProblem != null) {
            Text(
                text = state.saveProblem.toDisplayText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(onClick = actions.onSave, enabled = state.canSave && !state.saving) {
            Text(stringResource(R.string.rule_set_editor_save_button))
        }
    }
}

/** The three [RouteOutcome]s in evaluation order, each movable earlier/later within the list. */
@Composable
private fun OrderSection(
    order: List<RouteOutcome>,
    onMoveEarlier: (RouteOutcome) -> Unit,
    onMoveLater: (RouteOutcome) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP / 2)) {
        Text(text = stringResource(R.string.rule_set_editor_order_label), style = MaterialTheme.typography.titleMedium)
        order.forEachIndexed { index, outcome ->
            val name = outcome.displayName()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                val earlierDescription =
                    stringResource(R.string.rule_set_editor_move_earlier_description, name)
                IconButton(onClick = { onMoveEarlier(outcome) }, enabled = index > 0) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = earlierDescription,
                    )
                }
                val laterDescription =
                    stringResource(R.string.rule_set_editor_move_later_description, name)
                IconButton(onClick = { onMoveLater(outcome) }, enabled = index < order.lastIndex) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = laterDescription,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteOutcome.displayName(): String =
    when (this) {
        RouteOutcome.BLOCK -> stringResource(R.string.rule_set_editor_outcome_block)
        RouteOutcome.PROXY -> stringResource(R.string.rule_set_editor_outcome_proxy)
        RouteOutcome.DIRECT -> stringResource(R.string.rule_set_editor_outcome_direct)
    }

@Composable
private fun BucketField.displayName(): String =
    when (this) {
        BucketField.SITES -> stringResource(R.string.rule_set_editor_field_sites)
        BucketField.IPS -> stringResource(R.string.rule_set_editor_field_ips)
    }

@Composable
private fun DomainStrategySection(
    selected: DomainStrategy,
    onSelected: (DomainStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP / 2)) {
        Text(
            text = stringResource(R.string.rule_set_editor_domain_strategy_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
            DomainStrategy.entries.forEach { strategy ->
                FilterChip(
                    selected = strategy == selected,
                    onClick = { onSelected(strategy) },
                    label = { Text(strategy.displayName()) },
                )
            }
        }
    }
}

@Composable
private fun DomainStrategy.displayName(): String =
    when (this) {
        DomainStrategy.AS_IS -> stringResource(R.string.rule_set_editor_domain_strategy_as_is)
        DomainStrategy.IP_IF_NON_MATCH -> stringResource(R.string.rule_set_editor_domain_strategy_ip_if_non_match)
        DomainStrategy.IP_ON_DEMAND -> stringResource(R.string.rule_set_editor_domain_strategy_ip_on_demand)
    }

/**
 * One of the six buckets: its stored entries, an add field, and — only when
 * [categories] is non-empty — a "browse categories" affordance beside it. §5.6:
 * every entry below is exactly what the user themself typed or picked; nothing
 * here is logged.
 */
// Same reasoning EditorField's own LongParameterList suppression gives: outcome/field/entries/
// categories/onAdd/onRemove are the width of one bucket's row, not a candidate for grouping into
// a carrier class that would just move the same six names one level down.
@Suppress("LongParameterList")
@Composable
private fun EntrySection(
    outcome: RouteOutcome,
    field: BucketField,
    entries: List<String>,
    categories: List<GeoCategory>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(outcome, field) { mutableStateOf("") }
    val sectionTitle =
        stringResource(R.string.rule_set_editor_section_title, outcome.displayName(), field.displayName())
    // Live, per-field validation (fix round 1, Finding 4 — the brief asked for "while typing",
    // not only on the + tap). RoutingEntries.problemWith is the exact pure function
    // RuleSetEditorViewModel.addEntry itself calls, so this can never disagree with what
    // actually gets rejected — a picked category never reaches this check at all, since
    // CategoryPicker calls onAdd (addEntry) directly. Blank is not shown: an empty field is not
    // yet a mistake, only what tapping + would currently report.
    val liveProblem = if (draft.isEmpty()) null else RoutingEntries.problemWith(draft, field)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP / 2)) {
        Text(
            text = sectionTitle,
            style = MaterialTheme.typography.titleMedium,
        )
        entries.forEach { entry -> EntryRow(entry = entry, onRemove = { onRemove(entry) }) }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.rule_set_editor_entry_hint)) },
                singleLine = true,
                isError = liveProblem != null,
                supportingText = liveProblem?.let { problem -> { Text(problem.toDisplayText()) } },
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(ROW_ICON_GAP))
            IconButton(
                onClick = {
                    // Only a draft the pure check already accepts is cleared — a rejected entry
                    // keeps its text and its inline reason above instead of vanishing silently
                    // (fix round 1, Finding 4).
                    if (draft.isNotEmpty() && liveProblem == null) {
                        onAdd(draft)
                        draft = ""
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.rule_set_editor_add_description),
                )
            }
            if (categories.isNotEmpty()) {
                CategoryPicker(
                    prefix = RoutingEntries.builtInPrefix(field),
                    categories = categories,
                    onSelected = onAdd,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val removeDescription = stringResource(R.string.rule_set_editor_remove_entry_description)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FIELD_GAP, vertical = FIELD_GAP / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = entry, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = removeDescription)
            }
        }
    }
}

/**
 * "Browse categories" for one field. [onSelected] receives [prefix] + the
 * picked [GeoCategory.code] directly — the same string [RuleSetEditorViewModel.addEntry]
 * validates for any other entry, so a picked category goes through the exact
 * same guard as one the user typed by hand.
 */
@Composable
private fun CategoryPicker(
    prefix: String,
    categories: List<GeoCategory>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(onClick = { menuOpen = true }) {
            Text(stringResource(R.string.rule_set_editor_browse_categories_description))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            categories.forEach { category ->
                val label = stringResource(R.string.rule_set_editor_category_row, category.code, category.ruleCount)
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menuOpen = false
                        onSelected(prefix + category.code)
                    },
                )
            }
        }
    }
}

/**
 * [EntryProblem] carries no entry text (§5.6) — every branch below maps the
 * closed-vocabulary problem itself to a fixed message, never anything the
 * user typed.
 */
@Composable
private fun EntryProblem.toDisplayText(): String =
    when (this) {
        EntryProblem.Blank -> stringResource(R.string.rule_set_editor_problem_blank)
        EntryProblem.MissingGeoCode -> stringResource(R.string.rule_set_editor_problem_missing_geo_code)
        EntryProblem.MalformedExtReference -> stringResource(R.string.rule_set_editor_problem_malformed_ext_reference)
        EntryProblem.MalformedAddress -> stringResource(R.string.rule_set_editor_problem_malformed_address)
        EntryProblem.MalformedDomain -> stringResource(R.string.rule_set_editor_problem_malformed_domain)
        EntryProblem.IllegalCharacter -> stringResource(R.string.rule_set_editor_problem_illegal_character)
    }

/**
 * [SaveProblem.NameConflict.name] is exempt from §5.6 the same way
 * [SaveProblem.NameConflict]'s own KDoc says — it is the conflicting rule
 * set's name, not an entry.
 */
@Composable
private fun SaveProblem.toDisplayText(): String =
    when (this) {
        SaveProblem.InvalidEntry -> stringResource(R.string.rule_set_editor_save_problem_invalid_entry)
        SaveProblem.WriteFailed -> stringResource(R.string.rule_set_editor_save_problem_write_failed)
        is SaveProblem.NameConflict -> stringResource(R.string.rule_set_editor_save_problem_name_conflict, name)
    }
