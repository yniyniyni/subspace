// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.add

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.parser.ParseFailure
import art.yniyniyni.subspace.feature.profiles.R

/**
 * One line of [ParseOutcome][art.yniyniyni.subspace.core.parser.ParseOutcome]'s
 * failure list, resolved to display text.
 *
 * `:feature:home` already has this exact mapping (`FailureDetailDisplay.kt`,
 * from the M1 paste flow Task 17 retired) but it cannot be reused here:
 * `:feature:*` modules may not depend on each other (ARCHITECTURE.md §4), and
 * moving it to the one module both could share, `:core:ui`, is not an option
 * either — `checkModuleBoundaries` enforces that `:core:ui` may depend on
 * `:core:model` only, and [FailureDetail]/[DetailField] live in `:core:parser`.
 * There is no module both `:feature:home` and `:feature:profiles` may depend
 * on that is also allowed to depend on `:core:parser`, so the two mappings are
 * genuinely, structurally duplicated rather than merely copy-pasted out of
 * laziness. `:feature:home`'s copy is dead code as of Task 17 (nothing in that
 * module renders it any more) and is removed by this change rather than left
 * beside this one, since keeping an unreferenced duplicate around serves no
 * one.
 *
 * An exhaustive `when` over both [FailureDetail] and [DetailField]: adding a
 * variant to either fails this file to compile until a string exists for it —
 * the payoff §7 and the task brief both call out, and what makes a future
 * localisation (M9) a translation job rather than a refactor.
 *
 * Never carries anything beyond [ParseFailure.index], the closed-vocabulary
 * [FailureDetail], and bounded numeric measurements (§5.6) — there is no
 * channel here for the address, UUID, or key that failed to parse.
 */
@Composable
internal fun failureText(failure: ParseFailure): String {
    val entry = failure.index + 1
    return when (val detail = failure.detail) {
        FailureDetail.None -> stringResource(R.string.import_failure_generic, entry)
        is FailureDetail.Length ->
            stringResource(
                R.string.import_failure_length,
                entry,
                stringResource(detail.field.labelRes()),
                detail.expected,
                detail.actual,
            )
        is FailureDetail.Range ->
            stringResource(
                R.string.import_failure_range,
                entry,
                stringResource(detail.field.labelRes()),
                detail.min,
                detail.max,
                detail.actual,
            )
        is FailureDetail.Missing ->
            stringResource(R.string.import_failure_missing, entry, stringResource(detail.field.labelRes()))
        is FailureDetail.Unsupported ->
            stringResource(R.string.import_failure_unsupported, entry, stringResource(detail.field.labelRes()))
        is FailureDetail.Malformed ->
            stringResource(R.string.import_failure_malformed, entry, stringResource(detail.field.labelRes()))
    }
}

/**
 * Internal (not `private`) since Task 21: the profile editor's own field-level error text
 * ([art.yniyniyni.subspace.feature.profiles.editor.editorErrorText]) resolves the same field
 * name vocabulary, and duplicating this exhaustive `when` a second time in the same module
 * would be exactly the kind of drift [failureText]'s own KDoc warns against.
 */
@Suppress("CyclomaticComplexMethod")
internal fun DetailField.labelRes(): Int =
    when (this) {
        DetailField.Scheme -> R.string.import_detail_field_scheme
        DetailField.Uri -> R.string.import_detail_field_uri
        DetailField.Address -> R.string.import_detail_field_address
        DetailField.Port -> R.string.import_detail_field_port
        DetailField.Uuid -> R.string.import_detail_field_uuid
        DetailField.Password -> R.string.import_detail_field_password
        DetailField.Method -> R.string.import_detail_field_method
        DetailField.PublicKey -> R.string.import_detail_field_public_key
        DetailField.ShortId -> R.string.import_detail_field_short_id
        DetailField.Fingerprint -> R.string.import_detail_field_fingerprint
        DetailField.Network -> R.string.import_detail_field_network
        DetailField.Security -> R.string.import_detail_field_security
        DetailField.Base64Body -> R.string.import_detail_field_base64_body
        DetailField.JsonBody -> R.string.import_detail_field_json_body
        DetailField.YamlBody -> R.string.import_detail_field_yaml_body
        DetailField.Credential -> R.string.import_detail_field_credential
        DetailField.AlterId -> R.string.import_detail_field_alter_id
    }
