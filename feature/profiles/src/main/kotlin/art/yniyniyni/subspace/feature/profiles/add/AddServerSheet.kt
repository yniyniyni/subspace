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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import java.io.IOException

private val CONTENT_HORIZONTAL_PADDING = 16.dp
private val CONTENT_BOTTOM_PADDING = 16.dp
private val FIELD_GAP = 8.dp
private const val PASTE_FIELD_MIN_LINES = 4

/**
 * Identifies the busy [CircularProgressIndicator] to instrumented tests —
 * same pattern as `core/ui`'s `CONNECT_HALO_TEST_TAG`. Not part of
 * [AddServerSheet]'s own API surface, only a seam [AddServerSheetContent]'s
 * own tests use to assert the indicator's presence without matching on text.
 */
internal const val IMPORT_BUSY_TEST_TAG = "import-busy"

/**
 * Identifies the paste [OutlinedTextField] to instrumented tests. Its label
 * text is not a reliable target for `performTextInput` (the label is its own
 * semantics text node, not the editable one), so a tag is used the same way
 * [GroupCardTest][art.yniyniyni.subspace.core.ui.component.GroupCardTest]'s
 * own `contentTag` is.
 */
internal const val IMPORT_PASTE_FIELD_TEST_TAG = "import-paste-field"

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
 * @param onScanQr the "Scan QR code" button (Task 20 fix round 1). The
 *   caller (ultimately `SubspaceNavHost`'s `QrScan` composable, via
 *   [art.yniyniyni.subspace.feature.profiles.qr.QrScanRoute]) owns
 *   navigation — this module has no `NavController` of its own — and is
 *   responsible for feeding a scanned payload back into the *same*
 *   [ImportViewModel] instance this sheet is backed by, not a fresh one, so
 *   the scan result renders in this same sheet on return. See
 *   [art.yniyniyni.subspace.feature.profiles.qr.QrScanRoute]'s own KDoc for
 *   why that hand-off does not go through `NavBackStackEntry.savedStateHandle`.
 */
@Composable
internal fun AddServerSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubspaceBottomSheet(
        open = open,
        titleRes = R.string.import_sheet_title,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        AddServerSheetBody(onScanQr = onScanQr)
    }
}

@Composable
private fun AddServerSheetBody(
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ImportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ACTION_OPEN_DOCUMENT: reads the picked file's bytes directly and feeds
    // them to the same ImportViewModel.import(raw) the paste field uses (task
    // brief §"Clipboard and file import ... both feed the same import(raw)")
    // — a file is config content exactly like a paste, so the read runs off
    // the main thread (§5.3) and nothing about its content is ever logged
    // (§5.6).
    //
    // Fix round 1, finding 2: some content providers return a null
    // InputStream instead of throwing for a stale/revoked URI, and a real
    // I/O error (permission revoked mid-flow, provider crash) throws
    // IOException/SecurityException from anywhere in this chain. Both are
    // caught here rather than left to crash the coroutine, and both resolve
    // to the same `text == null` outcome ImportViewModel.reportFileReadFailure
    // renders — neither branch reads the exception's own message (§5.6: an
    // I/O exception can carry a path or provider detail).
    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                viewModel.beginFileRead()
                val text =
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }
                    } catch (ignored: IOException) {
                        null
                    } catch (ignored: SecurityException) {
                        null
                    }
                if (text != null) {
                    viewModel.import(text)
                } else {
                    viewModel.reportFileReadFailure()
                }
            }
        }

    AddServerSheetContent(
        state = state,
        actions =
        ImportActions(
            onInputChanged = viewModel::onInputChanged,
            onImportClick = { viewModel.import(state.input) },
            // "*/*": a subscription can be a share-link list, base64 blob,
            // Clash YAML or raw Xray JSON (§7) — SubscriptionParser detects
            // the shape itself, so this does not narrow by extension or
            // MIME type the way a single-format picker would.
            onImportFromFileClick = { openDocument.launch(arrayOf("*/*")) },
            // Fix round 1: just forwards to the caller — this composable
            // has no NavController and does not know or care how scanning
            // is presented, only that tapping it should start.
            onScanClick = onScanQr,
        ),
        modifier = modifier,
    )
}

/**
 * [AddServerSheetContent]'s four callbacks, grouped for the same reason
 * [art.yniyniyni.subspace.feature.profiles.list.ServersActions] is (both
 * pre-date and post-date detekt's `LongParameterList` threshold — this one
 * crossed it when Task 20 fix round 1 added [onScanClick]).
 */
internal data class ImportActions(
    val onInputChanged: (String) -> Unit,
    val onImportClick: () -> Unit,
    val onImportFromFileClick: () -> Unit,
    val onScanClick: () -> Unit,
)

/**
 * The stateless half — see [art.yniyniyni.subspace.feature.home.HomeScreenContent]'s
 * KDoc for why this split exists. Everything [AddServerSheetBody] would
 * otherwise own directly (paste text, the Import/Import-from-file/Scan
 * buttons' enabled gating, the busy indicator, the failure list's
 * expand/collapse) is driven from [state] and [actions] here, so
 * instrumented tests can exercise it with a plain [ImportState] and no-op
 * lambdas instead of a real [ImportViewModel] behind Hilt.
 */
@Composable
internal fun AddServerSheetContent(
    state: ImportState,
    actions: ImportActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = CONTENT_HORIZONTAL_PADDING, vertical = CONTENT_BOTTOM_PADDING),
        verticalArrangement = Arrangement.spacedBy(FIELD_GAP),
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = actions.onInputChanged,
            label = { Text(stringResource(R.string.import_paste_label)) },
            minLines = PASTE_FIELD_MIN_LINES,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag(IMPORT_PASTE_FIELD_TEST_TAG),
        )

        Button(
            onClick = actions.onImportClick,
            enabled = !state.busy && state.input.isNotBlank(),
        ) {
            Text(stringResource(R.string.import_button))
        }

        TextButton(
            onClick = actions.onImportFromFileClick,
            enabled = !state.busy,
        ) {
            Text(stringResource(R.string.import_from_file_button))
        }

        TextButton(
            onClick = actions.onScanClick,
            enabled = !state.busy,
        ) {
            Text(stringResource(R.string.import_scan_qr_button))
        }

        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.testTag(IMPORT_BUSY_TEST_TAG))
        }

        if (state.fileReadFailed) {
            Text(
                text = stringResource(R.string.import_file_read_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
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
