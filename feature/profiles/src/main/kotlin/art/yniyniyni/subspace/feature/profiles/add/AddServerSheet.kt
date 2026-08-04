// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the content paddings below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's ConnectControl.kt for the same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.profiles.add

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.parser.ParseFailure
import art.yniyniyni.subspace.core.ui.component.SubspaceBottomSheet
import art.yniyniyni.subspace.feature.profiles.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CONTENT_HORIZONTAL_PADDING = 16.dp
private val CONTENT_BOTTOM_PADDING = 16.dp
private val FIELD_GAP = 8.dp
private const val PASTE_FIELD_MIN_LINES = 4

/**
 * The only place in the app that turns pasted or imported text into a stored
 * [art.yniyniyni.subspace.core.data.StoredProfile] — before this task, Home
 * could connect to a stored profile and Servers could pick one, but nothing
 * could put one there.
 *
 * Every entry [art.yniyniyni.subspace.core.parser.SubscriptionParser] cannot
 * read is a [ParseFailure], never a lost row (§7) — this sheet reports both
 * halves of the outcome, listed by [ParseFailure.index] so a user can be told
 * which entry failed without that entry ever being rendered (§5.6).
 *
 * @param open whether the sheet is shown — see [SubspaceBottomSheet]'s own
 *   `open` for why closed means "not composed", not "composed but invisible".
 * @param onDismiss scrim tap, swipe down, or the drag handle's dismiss action.
 */
@Composable
internal fun AddServerSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubspaceBottomSheet(
        open = open,
        titleRes = R.string.import_sheet_title,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        AddServerSheetBody()
    }
}

@Composable
private fun AddServerSheetBody(modifier: Modifier = Modifier) {
    val viewModel: ImportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ACTION_OPEN_DOCUMENT: reads the picked file's bytes directly and feeds
    // them to the same ImportViewModel.import(raw) the paste field uses (task
    // brief §"Clipboard and file import ... both feed the same import(raw)")
    // — a file is config content exactly like a paste, so the read runs off
    // the main thread (§5.3) and nothing about its content is ever logged
    // (§5.6).
    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val text =
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }
                if (text != null) viewModel.import(text)
            }
        }

    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = CONTENT_HORIZONTAL_PADDING, vertical = CONTENT_BOTTOM_PADDING),
        verticalArrangement = Arrangement.spacedBy(FIELD_GAP),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.import_paste_label)) },
            minLines = PASTE_FIELD_MIN_LINES,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { viewModel.import(input) },
            enabled = !state.busy && input.isNotBlank(),
        ) {
            Text(stringResource(R.string.import_button))
        }

        TextButton(
            // "*/*": a subscription can be a share-link list, base64 blob,
            // Clash YAML or raw Xray JSON (§7) — SubscriptionParser detects
            // the shape itself, so this does not narrow by extension or MIME
            // type the way a single-format picker would.
            onClick = { openDocument.launch(arrayOf("*/*")) },
            enabled = !state.busy,
        ) {
            Text(stringResource(R.string.import_from_file_button))
        }

        if (state.busy) {
            CircularProgressIndicator()
        }

        if (state.completed) {
            ImportResult(state = state)
        }
    }
}

@Composable
private fun ImportResult(
    state: ImportState,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        // §7/§10.4: this is a normal, successful outcome even when failures
        // is non-empty — 197 imported with 3 listed failures, never silently
        // rounded down to "197 imported" with the other three dropped.
        Text(
            text = stringResource(R.string.import_result_summary, state.imported, state.total),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.failures.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) {
                        stringResource(R.string.import_result_failures_hide)
                    } else {
                        pluralStringResource(
                            R.plurals.import_result_failures_show,
                            state.failures.size,
                            state.failures.size,
                        )
                    },
                )
            }

            if (expanded) {
                FailureList(failures = state.failures)
            }
        }
    }
}

@Composable
private fun FailureList(
    failures: List<ParseFailure>,
    modifier: Modifier = Modifier,
) {
    // A plain Column, not a LazyColumn: this list lives inside a
    // ModalBottomSheet, which already scrolls its own content, and a scrolling
    // list inside a scrolling list fights the outer one for gesture priority.
    // §7 caps this at a handful of entries per import in practice (one bad
    // line per subscription, not hundreds), so eager composition is cheap.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        failures.forEach { failure ->
            Text(text = failureText(failure), style = MaterialTheme.typography.bodySmall)
        }
    }
}
