// SPDX-License-Identifier: AGPL-3.0-or-later
// The decimal-MB divisor below is BYTES_PER_MEGABYTE, already named by the
// constant it initializes — same convention QuotaBar.kt documents for its
// own (binary) byte-unit divisor.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import art.yniyniyni.subspace.core.ui.component.SettingRow

private const val BYTES_PER_MEGABYTE = 1_000_000.0
private val UPDATE_SPINNER_SIZE = 24.dp

/**
 * One geo database as the UI displays it: a catalogue [GeoSource]'s shape, joined against
 * whatever [InstalledGeoAsset] (if any) that source's `installFileName` currently has on disk.
 *
 * @property approximateBytes the catalogue's measured download size — shown *before* a download,
 *   per this task's brief, since [installedBytes] does not exist yet for a never-installed row.
 * @property installedBytes the real installed size once known, which supersedes the estimate.
 * @property hasError §A.3.1's persistent error marker: true whenever the most recent install
 *   attempt (this session or a prior one, scheduled or manual) recorded a [InstalledGeoAsset.lastFailure].
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
    val isCustom: Boolean = false,
)

/**
 * Builds one display row from a catalogue (or synthetic custom) [source] and the matching
 * [installed] asset, if any. A pure function on purpose — [SettingsGeoTest] exercises it directly,
 * with no ViewModel, no Compose, and no repository in the loop.
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
        isCustom = isCustom,
    )

/**
 * A synthetic [GeoSource] standing in for an installed asset that came from a user-added URL
 * rather than [art.yniyniyni.subspace.core.model.GeoSourceCatalogue]. [GeoSource.licence] is left
 * blank — a custom source's licence is unknown to this app — and [GeoSource.approximateBytes]
 * falls back to the real installed size, since there is no catalogue estimate to show instead.
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
 * One row: display name, size (installed size once known, the catalogue estimate before that —
 * this task's whole reason for [GeoSource.approximateBytes] existing), install status, and an
 * "Update now" action that always runs (§A.5 — see [GeoAssetSource.install]'s KDoc).
 */
@Composable
private fun GeoRowItem(
    row: GeoRow,
    updating: Boolean,
    result: GeoInstallResult?,
    onUpdateNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizeMb = (row.installedBytes ?: row.approximateBytes) / BYTES_PER_MEGABYTE
    val sizeLabel = stringResource(R.string.settings_geo_size_mb, sizeMb)
    val statusLabel =
        stringResource(
            when {
                updating -> R.string.settings_geo_status_updating
                // A fresh outcome from this session takes priority over the persisted marker —
                // §10.4: the four GeoInstallResult members stay distinct all the way to the string.
                result != null -> result.messageRes()
                row.hasError -> R.string.settings_geo_status_error
                row.installedAt != null -> R.string.settings_geo_status_installed
                else -> R.string.settings_geo_status_not_installed
            },
        )
    val displayName = if (row.isCustom) stringResource(R.string.settings_geo_custom_source_label) else row.displayName

    SettingRow(
        icon = Icons.Default.Info,
        label = displayName,
        supportingText = stringResource(R.string.settings_geo_row_summary, sizeLabel, statusLabel),
        trailing = {
            if (updating) {
                CircularProgressIndicator(modifier = Modifier.size(UPDATE_SPINNER_SIZE))
            } else {
                val updateDescription = stringResource(R.string.settings_geo_update_now, displayName)
                IconButton(onClick = onUpdateNow) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = updateDescription)
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * §10.4: every one of [GeoInstallResult]'s four members gets its own string — never collapsed to
 * a pass/fail boolean, so the user can tell a failed download from a rejected file from a failed
 * local install.
 */
private fun GeoInstallResult.messageRes(): Int =
    when (this) {
        GeoInstallResult.Installed -> R.string.settings_geo_result_installed
        GeoInstallResult.DownloadFailed -> R.string.settings_geo_result_download_failed
        GeoInstallResult.Rejected -> R.string.settings_geo_result_rejected
        GeoInstallResult.InstallFailed -> R.string.settings_geo_result_install_failed
    }

/**
 * One segmented row per [GeoDataKind], letting the user swap which catalogue provider backs that
 * kind's install filename. Selecting a source here does not download anything by itself — that is
 * [GeoRowItem]'s "Update now" action; this only changes which source that action would use.
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
            enabled = url.isNotBlank() && fileName.isNotBlank() && kindForAdd != null,
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
