// SPDX-License-Identifier: AGPL-3.0-or-later
// The section's composables (row, picker, custom-source form) plus the pure row-assembly
// functions SettingsGeoTest exercises directly (geoRowFor, geoRowsFor, its private helpers, the
// two string-resource mappers) push this past the file-level function-count threshold. Splitting
// the pure functions into a second file would separate them from the composables that are their
// only real caller, for no readability gain — same call [SettingsViewModel]'s own
// TooManyFunctions suppression makes for the identical reason.
@file:Suppress("TooManyFunctions")

package art.yniyniyni.subspace.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.core.data.GeoInstallResult
import art.yniyniyni.subspace.core.data.InstalledGeoAsset
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoSource
import art.yniyniyni.subspace.core.model.GeoSourceCatalogue
import art.yniyniyni.subspace.core.model.isGeoFileName
import art.yniyniyni.subspace.core.ui.component.SettingRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Suppress("MagicNumber") // The one non-token literal in this file — decimal-MB, not binary (QuotaBar.kt's own).
private const val BYTES_PER_MEGABYTE = 1_000_000.0
private val UPDATE_SPINNER_SIZE = 24.dp

private val INSTALLED_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

/**
 * One geo database as the UI displays it: a catalogue [GeoSource]'s shape, joined against
 * whatever [InstalledGeoAsset] (if any) that source's `installFileName` currently has on disk.
 *
 * @property approximateBytes the catalogue's measured download size — shown *before* a download,
 *   per this task's brief, since [installedBytes] does not exist yet for a never-installed row.
 * @property installedBytes the real installed size once known, which supersedes the estimate.
 * @property hasError §A.3.1's persistent error marker: true whenever the most recent install
 *   attempt (this session or a prior one, scheduled or manual) recorded a [InstalledGeoAsset.lastFailure].
 * @property lastFailure the raw closed-vocabulary name behind [hasError] — `null` unless [hasError]
 *   is true. §10.4 (branch review, Finding 3): kept distinct from the four in-session
 *   [GeoInstallResult] outcomes so a permanent `NotGeoData` (retrying will never help) does not read
 *   identically to a transient `DownloadFailed` or a `RecoveryFailed` whose backups are stranded in
 *   staging awaiting recovery.
 * @property isCustom true when this row was built from an installed asset that does not match any
 *   currently selected catalogue source — a user-added source (§6, §4.1).
 */
internal data class GeoRow(
    val sourceId: String,
    val displayName: String,
    val installFileName: String,
    val downloadUrl: String,
    val geoType: GeoDataKind,
    val licence: String,
    val approximateBytes: Long,
    val installedBytes: Long?,
    val installedAt: Long?,
    val hasError: Boolean,
    val lastFailure: String? = null,
    val isCustom: Boolean = false,
)

/**
 * Builds one display row from a catalogue (or synthetic custom) [source] and the matching
 * [installed] asset, if any. A pure function on purpose — [SettingsGeoTest] exercises it directly,
 * with no ViewModel, no Compose, and no repository in the loop.
 *
 * This function trusts whatever [source]/[installed] pair its caller hands it — it does not decide
 * *which* source belongs with which asset. [geoRowsFor] is that decision; see its own KDoc for why
 * the two are kept separate.
 */
internal fun geoRowFor(
    source: GeoSource,
    installed: InstalledGeoAsset?,
    isCustom: Boolean = false,
): GeoRow =
    GeoRow(
        sourceId = source.id,
        displayName = source.displayName,
        installFileName = source.installFileName,
        downloadUrl = source.downloadUrl,
        geoType = source.geoType,
        licence = source.licence,
        approximateBytes = source.approximateBytes,
        installedBytes = installed?.sizeBytes,
        installedAt = installed?.installedAt,
        hasError = installed?.lastFailure != null,
        lastFailure = installed?.lastFailure,
        isCustom = isCustom,
    )

/**
 * A synthetic [GeoSource] standing in for an installed asset that came from a user-added URL
 * rather than [GeoSourceCatalogue]. [GeoSource.licence] is left blank — a custom source's licence
 * is unknown to this app — and [GeoSource.approximateBytes] falls back to the real installed size,
 * since there is no catalogue estimate to show instead.
 */
internal fun InstalledGeoAsset.asCustomSource(): GeoSource =
    GeoSource(
        id = "custom:$fileName",
        displayName = fileName,
        downloadUrl = sourceUrl,
        installFileName = fileName,
        geoType = geoType,
        licence = "",
        approximateBytes = sizeBytes ?: 0L,
    )

/**
 * Builds the full "Geo databases" row list, one row per install filename.
 *
 * A branch review found the previous version of this assembly: it always built a filename's row
 * from *whichever catalogue source the picker currently named* for that filename, joined against
 * whatever asset happened to share the filename. Two bugs fell out of that — both fixed here by
 * changing what "ground truth" for a row means:
 *
 * 1. **A custom source could be silently destroyed.** `GeoSourceCatalogue.defaults()` always
 *    claims `geoip.dat`/`geosite.dat` by default, so a custom source installed under one of those
 *    names (the add-custom-source form's own filename hint invites exactly `geoip.dat`) was
 *    rendered as the *catalogue* row, and "Update now" on it downloaded the catalogue source over
 *    the user's data with no warning.
 * 2. **The estimate shown before a download could be stale.** Picking a new, larger source for an
 *    already-installed filename kept showing the *old* installed size until the download finished,
 *    understating the cost §A.5 requires the user see beforehand.
 *
 * The fix: an installed asset is ground truth for its filename **unless** [selectedIds] explicitly
 * names a different source for that filename right now — in which case that selection is a
 * *pending* switch, previewed with its own estimate (no installed-only *size*, per the §A.5 fix
 * above) but **not** stripped of the rest of the installed asset's state. Branch review, Finding 2:
 * an earlier version of this also erased [InstalledGeoAsset.installedAt] and
 * [InstalledGeoAsset.lastFailure] the instant a selection was armed, so the row read "Not installed
 * yet" for a file that was still genuinely installed and still active for routing — the Routing
 * screen, which reads [InstalledGeoAsset] directly rather than through a pending selection, kept
 * disagreeing with this one the whole time — and, worse, a persisted failure on that same file
 * became invisible for as long as the selection stayed armed, the exact masking class Task 17's own
 * Finding 3 closed through a different door. Only once "Update now" actually downloads the pending
 * selection does the row become the new ground truth outright, because at that point the two agree.
 * A filename with neither an installed asset nor a matching selection falls back to
 * [GeoSourceCatalogue.defaults] — the two starter rows a user who never opens the picker still
 * sees.
 */
internal fun geoRowsFor(
    sources: List<GeoSource>,
    selectedIds: Set<String>,
    installed: List<InstalledGeoAsset>,
): List<GeoRow> {
    val selectedSources = selectedIds.mapNotNull { id -> sources.firstOrNull { it.id == id } }
    val defaultSources = GeoSourceCatalogue.defaults()
    val installedFileNames = installed.map { it.fileName }
    val selectedFileNames = selectedSources.map { it.installFileName }
    val defaultFileNames = defaultSources.map { it.installFileName }
    val fileNames = (installedFileNames + selectedFileNames + defaultFileNames).distinct()

    return fileNames.mapNotNull { fileName ->
        rowForFileName(fileName, sources, selectedSources, defaultSources, installed)
    }
}

@Suppress("ReturnCount") // Three independent, mutually-exclusive precedence rules — see the KDoc above.
private fun rowForFileName(
    fileName: String,
    sources: List<GeoSource>,
    selectedSources: List<GeoSource>,
    defaultSources: List<GeoSource>,
    installed: List<InstalledGeoAsset>,
): GeoRow? {
    val installedAsset = installed.firstOrNull { it.fileName == fileName }
    val pendingSource = selectedSources.firstOrNull { it.installFileName == fileName }
    val agreesWithInstalled = pendingSource == null || pendingSource.downloadUrl == installedAsset?.sourceUrl

    if (installedAsset != null && agreesWithInstalled) {
        val matched =
            sources.firstOrNull { it.installFileName == fileName && it.downloadUrl == installedAsset.sourceUrl }
        return geoRowFor(matched ?: installedAsset.asCustomSource(), installedAsset, isCustom = matched == null)
    }
    if (pendingSource != null) {
        // The estimate must be the pending source's (§A.5), but everything else about an existing
        // installed asset — install date, error marker — must survive an armed-but-not-yet-updated
        // selection (branch review, Finding 2). sizeBytes = null is what keeps GeoRowItem's
        // `installedBytes ?: approximateBytes` from showing the stale on-disk size instead.
        return geoRowFor(pendingSource, installed = installedAsset?.copy(sizeBytes = null))
    }
    val defaultSource = defaultSources.firstOrNull { it.installFileName == fileName } ?: return null
    return geoRowFor(defaultSource, installed = null)
}

/**
 * "Geo databases": one row per install filename, a source picker per [GeoDataKind], an
 * add-custom-source form, and the scheduled-refresh metered toggle (Task 14's
 * [SettingsSource.geoRefreshOnMetered]).
 *
 * Deliberately **not** a `SectionHeader` plus this content in one composable — [SettingsScreen]
 * places the header itself, the same split every other section in that file uses.
 */
@Composable
internal fun SettingsGeoSection(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        state.geoRows.forEach { row ->
            GeoRowItem(
                row = row,
                updating = row.installFileName in state.geoUpdateInFlight,
                result = state.geoUpdateResults[row.installFileName],
                onUpdateNow = { actions.onGeoUpdateNow(row) },
                onRemove = { actions.onRemoveCustomGeoSource(row.installFileName) },
            )
        }

        GeoSourcePicker(
            sources = state.geoSources,
            selectedIds = state.selectedGeoSourceIds,
            onSourceSelected = actions.onGeoSourceSelected,
        )

        GeoCustomSourceForm(onAdd = actions.onAddCustomGeoSource)

        val meteredTitle = stringResource(R.string.settings_geo_metered_title)
        SettingRow(
            icon = Icons.Default.Refresh,
            label = meteredTitle,
            supportingText = stringResource(R.string.settings_geo_metered_summary),
            trailing = {
                Switch(
                    checked = state.geoRefreshOnMetered,
                    onCheckedChange = actions.onGeoRefreshOnMeteredChanged,
                    modifier = Modifier.semantics { contentDescription = meteredTitle },
                )
            },
        )
    }
}

/**
 * One row: display name (the real filename for a custom source, §5.6 — filenames are shape, not
 * a secret, and a generic "Custom source" label for every custom row left two of them
 * indistinguishable), size, licence, install date, status, an "Update now" action that always runs
 * (§A.5 — see [GeoAssetSource.install]'s KDoc), and — for a custom row only — a "Remove" action
 * (branch review, Finding 3): a catalogue row always has somewhere to reappear from ([GeoSourcePicker]
 * or [GeoSourceCatalogue.defaults]), so only a custom row can be a typo'd URL with no other way to
 * stop it being retried by a scheduled refresh forever.
 */
// row/updating/result/onUpdateNow/onRemove/modifier is the width of one row's own data and its two
// actions, not a candidate for grouping into a carrier that would just move the same five names one
// level down — same reasoning EntrySection's own LongParameterList suppression gives in
// RuleSetEditorScreen.kt.
@Suppress("LongParameterList")
@Composable
private fun GeoRowItem(
    row: GeoRow,
    updating: Boolean,
    result: GeoInstallResult?,
    onUpdateNow: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizeMb = (row.installedBytes ?: row.approximateBytes) / BYTES_PER_MEGABYTE
    val sizeLabel = stringResource(R.string.settings_geo_size_mb, sizeMb)
    val statusLabel = row.statusLabel(updating, result)
    val displayName =
        if (row.isCustom) {
            stringResource(R.string.settings_geo_custom_source_label, row.installFileName)
        } else {
            row.displayName
        }
    val installedAtLabel =
        row.installedAt?.let { millis ->
            stringResource(R.string.settings_geo_installed_at, formatInstalledAt(millis))
        }

    Column {
        SettingRow(
            icon = Icons.Default.Info,
            label = displayName,
            supportingText = stringResource(R.string.settings_geo_row_summary, sizeLabel, statusLabel),
            trailing = {
                if (updating) {
                    CircularProgressIndicator(modifier = Modifier.size(UPDATE_SPINNER_SIZE))
                } else {
                    Row {
                        if (row.isCustom) {
                            val removeDescription =
                                stringResource(R.string.settings_geo_remove_custom_source, displayName)
                            IconButton(onClick = onRemove) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = removeDescription)
                            }
                        }
                        val updateDescription = stringResource(R.string.settings_geo_update_now, displayName)
                        IconButton(onClick = onUpdateNow) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = updateDescription)
                        }
                    }
                }
            },
            modifier = modifier,
        )
        // Spec §6 asks for the catalogue with sizes and licences; the brief's Step 3 asks for the
        // install date. Neither is a control, so both go on their own supporting line rather than
        // fighting SettingRow's single supportingText slot for space (same reasoning HwidControl's
        // KDoc gives for the HWID value's own line).
        if (installedAtLabel != null || row.licence.isNotBlank()) {
            Text(
                text = listOfNotNull(installedAtLabel, row.licence.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * §10.4 (branch review, Finding 3): a fresh in-session [GeoInstallResult] wins over the persisted
 * marker *unless* it is a stale "Installed" masking a failure some other actor (a scheduled
 * refresh, another process) has since recorded — a live failure must never be hidden behind an
 * earlier success this session happened to produce.
 */
@Composable
private fun GeoRow.statusLabel(updating: Boolean, result: GeoInstallResult?): String =
    when {
        updating -> stringResource(R.string.settings_geo_status_updating)
        hasError && result == GeoInstallResult.Installed -> stringResource(lastFailureMessageRes(lastFailure))
        result != null -> stringResource(result.messageRes())
        hasError -> stringResource(lastFailureMessageRes(lastFailure))
        installedAt != null -> stringResource(R.string.settings_geo_status_installed)
        else -> stringResource(R.string.settings_geo_status_not_installed)
    }

private fun formatInstalledAt(millis: Long): String =
    INSTALLED_AT_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/**
 * §10.4: every one of [GeoInstallResult]'s four members gets its own string — never collapsed to
 * a pass/fail boolean, so the user can tell a failed download from a rejected file from a failed
 * local install. `internal` (not `private`) so [SettingsGeoTest] can pin that all four resolve to
 * distinct resource ids — a copy-paste pointing two members at the same string would otherwise
 * compile and pass silently.
 */
internal fun GeoInstallResult.messageRes(): Int =
    when (this) {
        GeoInstallResult.Installed -> R.string.settings_geo_result_installed
        GeoInstallResult.DownloadFailed -> R.string.settings_geo_result_download_failed
        GeoInstallResult.Rejected -> R.string.settings_geo_result_rejected
        GeoInstallResult.InstallFailed -> R.string.settings_geo_result_install_failed
    }

/**
 * [InstalledGeoAsset.lastFailure] is a closed-vocabulary name from one of two `:core:data`/`:core:model`
 * enums its own KDoc names but does not expose together as one importable type:
 * [art.yniyniyni.subspace.core.data.GeoAssetRepository]'s `GeoAssetFailure` (`InvalidFileName`,
 * `DownloadFailed`, `InstallFailed`, `RecoveryFailed` — `internal` to `:core:data`, so it cannot be
 * imported here at all) and [art.yniyniyni.subspace.core.model.GeoValidation] (`Unreadable`,
 * `NotGeoData`, both public). Matched as plain strings for both, one flat `when` rather than a
 * partial import for the two that happen to have a public type.
 *
 * §10.4 (branch review, Finding 3): distinct from [GeoInstallResult.messageRes] on purpose — this
 * is the *persisted* marker a scheduled refresh's own failure leaves behind, which
 * [SettingsViewModel] never learns about as a fresh [GeoInstallResult] at all, so it is the only
 * failure channel a user normally sees for that case. A permanent `NotGeoData` and a transient
 * `DownloadFailed` must not read the same, and `RecoveryFailed` specifically means backups are
 * stranded in staging awaiting recovery — worth a different message than "try again".
 */
internal fun lastFailureMessageRes(lastFailure: String?): Int =
    when (lastFailure) {
        "InvalidFileName" -> R.string.settings_geo_persisted_invalid_filename
        "DownloadFailed" -> R.string.settings_geo_persisted_download_failed
        "InstallFailed" -> R.string.settings_geo_persisted_install_failed
        "RecoveryFailed" -> R.string.settings_geo_persisted_recovery_failed
        "Unreadable" -> R.string.settings_geo_persisted_unreadable
        "NotGeoData" -> R.string.settings_geo_persisted_not_geo_data
        // Defensive fallback for a value this app never wrote itself (§10.4's own "fail loudly and
        // specifically" is about code paths, not about a persisted string a future app version, or
        // a hand-edited row, might carry) — never crashes the section over an unrecognised marker.
        else -> R.string.settings_geo_status_error
    }

/**
 * One segmented row per [GeoDataKind], letting the user swap which catalogue provider backs that
 * kind's install filename. Selecting a source here does not download anything by itself — that is
 * [GeoRowItem]'s "Update now" action; this only arms which source that action would use next
 * ([geoRowsFor]'s "pending switch" case).
 */
@Composable
private fun GeoSourcePicker(
    sources: List<GeoSource>,
    selectedIds: Set<String>,
    onSourceSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        GeoDataKind.entries.forEach { kind ->
            val kindSources = sources.filter { it.geoType == kind }
            if (kindSources.isEmpty()) return@forEach

            Text(text = stringResource(kind.labelRes()), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                kindSources.forEachIndexed { index, source ->
                    SegmentedButton(
                        selected = source.id in selectedIds,
                        onClick = { onSourceSelected(source.id) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = kindSources.size),
                        label = { Text(source.displayName) },
                    )
                }
            }
        }
    }
}

/**
 * URL, install filename, and a required [GeoDataKind] choice — this task's brief is explicit that
 * the kind cannot default or be inferred from the bytes (Part 1 Task 6): [selectedKind] starts at
 * `null` and the Add button stays disabled until the user taps one of the two segments themselves.
 *
 * The filename field is also checked against [isGeoFileName] before the Add button enables
 * (branch review minor): typing `geoip` with no extension used to reach the repository, which
 * rejects it as `InvalidFileName` → `GeoInstallResult.Rejected` → "Downloaded file was not a valid
 * geo database" — a message about bytes that were never fetched at all. Catching it here means the
 * button simply never enables for a filename shaped like that in the first place.
 */
@Composable
private fun GeoCustomSourceForm(
    onAdd: (url: String, fileName: String, geoType: GeoDataKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf<GeoDataKind?>(null) }

    Column(modifier = modifier) {
        Text(text = stringResource(R.string.settings_geo_add_custom_title), style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.settings_geo_add_custom_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it },
            label = { Text(stringResource(R.string.settings_geo_add_custom_filename)) },
            isError = fileName.isNotBlank() && !isGeoFileName(fileName),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            GeoDataKind.entries.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = selectedKind == kind,
                    onClick = { selectedKind = kind },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = GeoDataKind.entries.size),
                    label = { Text(stringResource(kind.labelRes())) },
                )
            }
        }

        val kindForAdd = selectedKind
        Button(
            onClick = {
                if (kindForAdd == null) return@Button
                onAdd(url, fileName, kindForAdd)
                url = ""
                fileName = ""
                selectedKind = null
            },
            enabled = url.isNotBlank() && isGeoFileName(fileName) && kindForAdd != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_geo_add_custom_action))
        }
    }
}

private fun GeoDataKind.labelRes(): Int =
    when (this) {
        GeoDataKind.DOMAIN -> R.string.settings_geo_kind_domain
        GeoDataKind.IP -> R.string.settings_geo_kind_ip
    }
