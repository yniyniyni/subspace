// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the code tile's size and the row's inner
// paddings/gaps below — all are tokens/spacing.css's --space-* scale, already
// named by the val each initializes. See core/ui's ConnectControl.kt for the
// same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.profiles.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.feature.profiles.R
import java.text.BreakIterator
import java.util.Locale

private val CODE_TILE_SIZE = 40.dp
private val NOTICE_ICON_SIZE = 14.dp
private val ROW_VERTICAL_PADDING = 12.dp
private val ROW_GAP = 12.dp
private val BADGE_GAP = 4.dp
private val BADGE_PADDING_VERTICAL = 2.dp

/**
 * One stored server. Renders a code tile (initials from [ServerRow.name]),
 * the name, protocol badge, transport, an active check, and — as of Task 21
 * — an edit button. Deliberately no ping value (M4) and no address, since an
 * address is a secret (§5.6, see [ServerRow]'s own KDoc for why this
 * projection carries no address field to begin with).
 *
 * @param onSelect the row body itself — sets this profile active (unchanged
 *   since Task 18).
 * @param onEdit the edit icon specifically — opens the profile editor
 *   (`Editor(profileId)`, Task 21). A separate control from [onSelect] rather
 *   than overloading the row tap: "make this the active server" and "change
 *   its fields" are different actions a user reaches for independently, and
 *   conflating them would make the editor reachable only by first switching
 *   the active server, which is not what a user editing a REALITY key on a
 *   server they are not currently using wants.
 */
@Composable
internal fun ServerRowItem(
    row: ServerRow,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = row.contentDescription()
    val editDescription = stringResource(R.string.servers_row_edit_description, row.name)

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onSelect)
            .semantics { contentDescription = description }
            .padding(vertical = ROW_VERTICAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CodeTile(name = row.name)

        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.name, style = MaterialTheme.typography.bodyLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(BADGE_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProtocolBadge(label = row.protocolDisplay)
                Text(
                    text = row.transport,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.compatibilityMode || !row.connectable) {
                RowNotices(row = row)
            }
        }

        if (row.isActive) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }

        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = editDescription,
            )
        }
    }
}

@Composable
private fun RowNotices(
    row: ServerRow,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (row.compatibilityMode) {
            Text(
                text = stringResource(R.string.servers_compatibility_mode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!row.connectable) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(BADGE_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(NOTICE_ICON_SIZE),
                )
                Text(
                    text = stringResource(R.string.servers_not_connectable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CodeTile(
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(CODE_TILE_SIZE).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.codeTileInitials(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ProtocolBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = BADGE_GAP, vertical = BADGE_PADDING_VERTICAL),
        )
    }
}

@Composable
private fun ServerRow.contentDescription(): String {
    val base =
        if (isActive) {
            stringResource(R.string.servers_row_description_active, name, protocolDisplay, transport)
        } else {
            stringResource(R.string.servers_row_description_select, name, protocolDisplay, transport)
        }
    val notices =
        buildList {
            if (compatibilityMode) add(stringResource(R.string.servers_compatibility_mode))
            if (!connectable) add(stringResource(R.string.servers_not_connectable))
        }
    return if (notices.isEmpty()) base else "$base, ${notices.joinToString(", ")}"
}

/**
 * The code tile's label: the first grapheme cluster of up to two words in
 * [this] that actually start with a letter or digit — e.g. "Frankfurt" ->
 * "F", "US East" -> "UE".
 *
 * Device-fixes finding: real profile names lead with an emoji (`"🚀 Авто |
 * Лучший сервер 🇪🇺"`) or carry a flag mid-string (`"Хельсинки 🇫🇮 XHTTP"`).
 * A `String` is UTF-16, so the rocket is a surrogate pair and each half of a
 * flag is a regional-indicator code point that is *itself* a surrogate pair
 * — taking `Char`s (the previous `take(1)`/`take(2)`) or even whole code
 * points off the front could still land mid-pair or mid-flag, producing an
 * unpaired surrogate that renders as `<27>`.
 *
 * The fix is two-layered:
 *  - Skip whole words that carry no letter/digit at all (an emoji, a flag,
 *    a bare `|` separator) rather than truncating into them. A letter is
 *    what makes a tile useful for scanning a list; an emoji is not, and a
 *    name starting with one should not make its tile any less legible than
 *    a name that doesn't.
 *  - Read a qualifying word's *first grapheme cluster* via [BreakIterator],
 *    not its first `Char` or even its first code point, since a combining
 *    mark can still trail a single-code-point base letter.
 *
 * A name with no letter/digit anywhere — blank, or entirely emoji/symbols —
 * falls back to `"?"`, the same sentinel the empty-name case already used.
 * Pure function of [this]: the same name always produces the same tile.
 */
internal fun String.codeTileInitials(): String {
    val letteredWords =
        trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && Character.isLetterOrDigit(it.codePointAt(0)) }
    if (letteredWords.isEmpty()) return "?"
    return letteredWords.take(2).joinToString("") { it.firstGraphemeCluster().uppercase(Locale.ROOT) }
}

/** The substring up to [this]'s first grapheme-cluster boundary, per [BreakIterator]. */
private fun String.firstGraphemeCluster(): String {
    val boundary = BreakIterator.getCharacterInstance(Locale.ROOT)
    boundary.setText(this)
    val end = boundary.next()
    return if (end == BreakIterator.DONE) this else substring(0, end)
}
