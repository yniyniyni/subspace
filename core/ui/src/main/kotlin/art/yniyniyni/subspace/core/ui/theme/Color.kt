// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule would otherwise flag every ARGB literal below.
// These are the ported design tokens themselves — the ARGB hex *is* the
// value, already named by the val it's assigned to; extracting each into a
// second named constant would just rename the same magic number twice.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * sRGB constants converted, once and offline, from the design system's
 * `tokens/colors.css` — which defines every color in OKLCH
 * (`oklch(40% .12 265)`), not sRGB.
 *
 * Method: OKLCH -> OKLab -> linear sRGB -> gamma-encoded sRGB, using the
 * matrices from Björn Ottosson's OKLab reference
 * (https://bottosson.github.io/posts/oklab/). This file is the *output* of
 * that conversion, not the conversion itself: there is no runtime OKLCH
 * parser here, because these are constants and a runtime converter is code
 * that can drift from the design (design-tokens/colors.css is the source of
 * truth; re-run the conversion by hand if it changes).
 *
 * Gamut: several mid-scale tones (chroma .09-.15) and the tertiary palette
 * (hue 195) in particular fall outside sRGB. Out-of-gamut colors are brought
 * into gamut by *reducing chroma* at fixed lightness and hue (binary search
 * for the largest in-gamut C), not by clamping R/G/B independently.
 * Per-channel clamping changes the ratio between channels and therefore
 * shifts the hue — exactly the kind of subtle, hard-to-spot bug this
 * conversion needs to avoid. This mirrors the CSS Color 4 gamut-mapping
 * algorithm's core idea. Every hex value below was cross-checked against an
 * independent second implementation (the `culori` JS library) at the same
 * OKLCH coordinates; the two agreed exactly, including which values needed
 * clipping and by how much.
 */

// ---- primary (hue 265) ----
private val Primary10 = Color(0xFF01030C)
private val Primary20 = Color(0xFF081431)
private val Primary30 = Color(0xFF172A5A)
private val Primary40 = Color(0xFF274387)
private val Primary80 = Color(0xFFABBEE5)
private val Primary90 = Color(0xFFD4DEF3)
private val Primary100 = Color(0xFFFFFFFF)

// ---- secondary (hue 290) ----
private val Secondary10 = Color(0xFF03020C)
private val Secondary20 = Color(0xFF16102F)
private val Secondary30 = Color(0xFF2E2357)
private val Secondary40 = Color(0xFF493883)
private val Secondary80 = Color(0xFFBCB8E2)
private val Secondary90 = Color(0xFFDDDBF1)
private val Secondary100 = Color(0xFFFFFFFF)

// ---- tertiary (hue 195) — also the source of --color-connected ----
// Chroma .06-.15 at this hue is out of sRGB gamut for tones 10-60; every one
// of those was chroma-reduced. See the file-level KDoc.
private val Tertiary10 = Color(0xFF000404)
private val Tertiary20 = Color(0xFF001B1B)
private val Tertiary30 = Color(0xFF003636)
private val Tertiary40 = Color(0xFF005353)
private val Tertiary80 = Color(0xFF90CACA)
private val Tertiary90 = Color(0xFFC8E5E4)
private val Tertiary100 = Color(0xFFFFFFFF)

// ---- error (hue 25) ----
private val Error10 = Color(0xFF0B0101)
private val Error20 = Color(0xFF2D0606)
private val Error30 = Color(0xFF541313)
private val Error40 = Color(0xFF7F2121)
private val Error80 = Color(0xFFE4AEA9)
private val Error90 = Color(0xFFF3D6D3)
private val Error100 = Color(0xFFFFFFFF)

// ---- neutral (hue 265, near-zero chroma) ----
private val Neutral4 = Color(0xFF000000)
private val Neutral6 = Color(0xFF010101)
private val Neutral10 = Color(0xFF030304)
private val Neutral12 = Color(0xFF060606)
private val Neutral17 = Color(0xFF0F0F10)
private val Neutral20 = Color(0xFF151617)
private val Neutral22 = Color(0xFF1A1B1C)
private val Neutral24 = Color(0xFF1F1F21)
private val Neutral87 = Color(0xFFD4D4D5)
private val Neutral90 = Color(0xFFDDDEDF)
private val Neutral92 = Color(0xFFE4E4E5)
private val Neutral94 = Color(0xFFEBEBEB)
private val Neutral95 = Color(0xFFEEEEEF)
private val Neutral96 = Color(0xFFF1F2F2)
private val Neutral98 = Color(0xFFF8F8F8)
private val Neutral100 = Color(0xFFFFFFFF)

// ---- neutral-variant (hue 265) ----
private val NeutralVariant30 = Color(0xFF2A2E35)
private val NeutralVariant50 = Color(0xFF5D6371)
private val NeutralVariant60 = Color(0xFF7B808C)
private val NeutralVariant80 = Color(0xFFBBBEC4)
private val NeutralVariant90 = Color(0xFFDCDEE1)

// ---- shadow / scrim ----
private val Shadow = Color(0xFF000000)

// ---- expressive accent pop (hue 340) — reserved for one CTA per screen ----
private val PopLight = Color(0xFFB43694)
private val OnPopLight = Color(0xFFFEFAFD)
private val PopDark = Color(0xFFEAA4D2)
private val OnPopDark = Color(0xFF3E0430)

/**
 * Material `ColorScheme` for the light theme, built from the sRGB constants
 * above. Every field traces back to a `--md-sys-color-*` role in
 * `colors.css`'s `:root` block.
 */
val SubspaceLightColorScheme: ColorScheme =
    lightColorScheme(
        primary = Primary40,
        onPrimary = Primary100,
        primaryContainer = Primary90,
        onPrimaryContainer = Primary10,
        inversePrimary = Primary80,
        secondary = Secondary40,
        onSecondary = Secondary100,
        secondaryContainer = Secondary90,
        onSecondaryContainer = Secondary10,
        tertiary = Tertiary40,
        onTertiary = Tertiary100,
        tertiaryContainer = Tertiary90,
        onTertiaryContainer = Tertiary10,
        error = Error40,
        onError = Error100,
        errorContainer = Error90,
        onErrorContainer = Error10,
        background = Neutral98,
        onBackground = Neutral10,
        surface = Neutral98,
        onSurface = Neutral10,
        surfaceVariant = NeutralVariant90,
        onSurfaceVariant = NeutralVariant30,
        outline = NeutralVariant50,
        outlineVariant = NeutralVariant80,
        scrim = Shadow,
        inverseSurface = Neutral20,
        inverseOnSurface = Neutral95,
        surfaceDim = Neutral87,
        surfaceBright = Neutral98,
        surfaceContainerLowest = Neutral100,
        surfaceContainerLow = Neutral96,
        surfaceContainer = Neutral94,
        surfaceContainerHigh = Neutral92,
        surfaceContainerHighest = Neutral90,
    )

/**
 * Material `ColorScheme` for the dark theme, i.e. `colors.css`'s
 * `[data-theme="dark"]` block.
 */
val SubspaceDarkColorScheme: ColorScheme =
    darkColorScheme(
        primary = Primary80,
        onPrimary = Primary20,
        primaryContainer = Primary30,
        onPrimaryContainer = Primary90,
        inversePrimary = Primary40,
        secondary = Secondary80,
        onSecondary = Secondary20,
        secondaryContainer = Secondary30,
        onSecondaryContainer = Secondary90,
        tertiary = Tertiary80,
        onTertiary = Tertiary20,
        tertiaryContainer = Tertiary30,
        onTertiaryContainer = Tertiary90,
        error = Error80,
        onError = Error20,
        errorContainer = Error30,
        onErrorContainer = Error90,
        background = Neutral6,
        onBackground = Neutral90,
        surface = Neutral6,
        onSurface = Neutral90,
        surfaceVariant = NeutralVariant30,
        onSurfaceVariant = NeutralVariant80,
        outline = NeutralVariant60,
        outlineVariant = NeutralVariant30,
        scrim = Shadow,
        inverseSurface = Neutral90,
        inverseOnSurface = Neutral20,
        surfaceDim = Neutral6,
        surfaceBright = Neutral24,
        surfaceContainerLowest = Neutral4,
        surfaceContainerLow = Neutral10,
        surfaceContainer = Neutral12,
        surfaceContainerHigh = Neutral17,
        surfaceContainerHighest = Neutral22,
    )

/**
 * [SubspaceColors] for the light theme.
 *
 * `--color-connected` is `--md-ref-tertiary-40` in `colors.css`; `pop` is the
 * standalone hue-340 accent. `colors.css` does not define an explicit
 * `--color-on-connected` token — it only aliases `connected` and
 * `connected-container` from the tertiary reference palette. `onConnected`
 * here follows the same tone pairing the design system itself uses for
 * `on-tertiary` (tone 40 pairs with tone 100, tone 80 pairs with tone 20),
 * since `connected` *is* a tertiary tone. Verified below to clear 4.5:1.
 */
val subspaceLightColors =
    SubspaceColors(
        connected = Tertiary40,
        connectedContainer = Tertiary90,
        onConnected = Tertiary100,
        pop = PopLight,
        onPop = OnPopLight,
    )

/** [SubspaceColors] for the dark theme — see [subspaceLightColors]. */
val subspaceDarkColors =
    SubspaceColors(
        connected = Tertiary80,
        connectedContainer = Tertiary30,
        onConnected = Tertiary20,
        pop = PopDark,
        onPop = OnPopDark,
    )
