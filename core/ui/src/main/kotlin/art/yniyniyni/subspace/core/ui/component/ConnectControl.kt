// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the halo's breath duration and the
// control's diameter — both are transcribed design-token values (184dp
// diameter, 28dp blur, .5 opacity, 3.2s breath), already named by the val/
// const they initialize. See Motion.kt and Color.kt for the same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.ui.component

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.core.ui.R
import art.yniyniyni.subspace.core.ui.theme.LocalSubspaceColors

/** `ConnectControl`'s default diameter, per `ds-update/components/core/ConnectControl.d.ts`. */
private val CONNECT_CONTROL_DIAMETER = 184.dp

/** `--color-connected` blur radius for the connected-only glow. */
private val CONNECT_HALO_BLUR_RADIUS = 28.dp

/** The halo's peak opacity while breathing. */
private const val CONNECT_HALO_MAX_ALPHA = 0.5f

/**
 * One full breath (opacity rising from 0 to [CONNECT_HALO_MAX_ALPHA]) takes
 * this long, then reverses over the same duration — the literal reading of
 * the brief's "breathing over 3.2s ease-in-out": 3.2s is the [tween]
 * `durationMillis` for each leg of an [infiniteRepeatable] with
 * [RepeatMode.Reverse], not the full up-and-down cycle.
 */
private const val CONNECT_HALO_BREATH_DURATION_MILLIS = 3200

/**
 * Semantics test tag for the connected-only halo. `internal`: only this
 * module's own instrumented test needs it, to assert the halo composable
 * (and the [rememberInfiniteTransition] inside it) exists in the composition
 * tree only for [ConnectVisualState.Connected].
 */
internal const val CONNECT_HALO_TEST_TAG = "connect-halo"

/**
 * The three states [ConnectControl] renders.
 *
 * A visual-only projection of the richer connection state the service
 * publishes ([art.yniyniyni.subspace.core.model.ConnectionState] on the
 * consuming side): every [art.yniyniyni.subspace.core.model.StartupStage]
 * collapses into [Connecting], and states this component does not render
 * distinctly (`Disconnecting`, `Failed`) are the caller's decision, not this
 * component's — `:core:ui` stays free of `:core:model`'s richer state
 * machine so the button's own state-to-color table doesn't grow a case for
 * every stage a screen might be in.
 */
enum class ConnectVisualState {
    Disconnected,
    Connecting,
    Connected,
}

/** Container/content color pair for one [ConnectVisualState]. */
private data class ConnectControlColors(val container: Color, val content: Color)

@Composable
private fun ConnectVisualState.colors(): ConnectControlColors {
    val scheme = MaterialTheme.colorScheme
    val subspaceColors = LocalSubspaceColors.current
    return when (this) {
        // colors.css aliases --color-disconnected to on-surface-variant and
        // --color-connecting to secondary (Task 13's handover notes), but
        // neither token says which role is the fill and which is the
        // content — colors.css only has the single alias, not a
        // container/on-container pair the way --color-connected does. Rather
        // than invent an unverified pairing, each state uses Material's own
        // guaranteed-contrast tonal pair for the corresponding role
        // (surfaceVariant/onSurfaceVariant, secondaryContainer/
        // onSecondaryContainer) as the closest faithful reading.
        ConnectVisualState.Disconnected ->
            ConnectControlColors(container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
        ConnectVisualState.Connecting ->
            ConnectControlColors(container = scheme.secondaryContainer, content = scheme.onSecondaryContainer)
        // connected/onConnected, not connectedContainer/onConnected: Color.kt's
        // KDoc only verifies contrast for the (connected, onConnected) pair —
        // connectedContainer has no onConnectedContainer counterpart to pair
        // it with safely.
        ConnectVisualState.Connected ->
            ConnectControlColors(container = subspaceColors.connected, content = subspaceColors.onConnected)
    }
}

@Composable
private fun ConnectVisualState.actionDescription(): String =
    stringResource(
        when (this) {
            ConnectVisualState.Disconnected -> R.string.connect_control_action_connect
            // No action exists while connecting — this tap is refused (see
            // ConnectControl's guard below) — so there is nothing to
            // announce but the transient status itself.
            ConnectVisualState.Connecting -> R.string.connect_control_action_connecting
            ConnectVisualState.Connected -> R.string.connect_control_action_disconnect
        },
    )

/**
 * The app's single most important control: starts and stops the VPN tunnel.
 *
 * Owns the [ConnectVisualState]-to-color mapping so no screen re-derives it,
 * and reports its *action* to accessibility services rather than its state —
 * "Connected" as a label would announce what already happened and hide what
 * tapping does next; "Disconnect" tells a screen-reader user what will
 * happen. The visible label carries the same text for sighted users, but is
 * excluded from the merged semantics tree ([Modifier.clearAndSetSemantics])
 * so it is not announced a second time alongside the explicit
 * [androidx.compose.ui.semantics.contentDescription].
 *
 * A tap during [ConnectVisualState.Connecting] is silently refused: [onClick]
 * is only invoked outside that state. This guard lives here, not in the
 * caller, so every consumer gets it — a second tap mid-start must not queue a
 * second connect attempt (§5.3: the start sequence is already slow and
 * async; a queued second start is a race, not a retry).
 *
 * The halo — `--color-connected` at [CONNECT_HALO_BLUR_RADIUS] blur,
 * breathing over [CONNECT_HALO_BREATH_DURATION_MILLIS] — exists in
 * composition *only* while [ConnectVisualState.Connected]. It is not merely
 * rendered at zero opacity otherwise: the private `ConnectHalo` composable
 * that owns the [rememberInfiniteTransition] call is invoked from inside an
 * `if (state == Connected)` block, so in every other state that composable —
 * and the frame-clock subscription its infinite transition holds open — does
 * not exist. An always-running infinite animation is a wakelock-shaped
 * battery cost in an app whose architecture doc opens by warning about
 * six-hour screen-off drain; this is the mechanism that keeps that cost from
 * existing in the two states that are not an established tunnel.
 *
 * @param state which of the three visual states to render.
 * @param onClick invoked on tap, except while [ConnectVisualState.Connecting].
 */
@Composable
fun ConnectControl(
    state: ConnectVisualState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = state.colors()
    val actionDescription = state.actionDescription()

    Box(
        modifier = modifier.size(CONNECT_CONTROL_DIAMETER),
        contentAlignment = Alignment.Center,
    ) {
        if (state == ConnectVisualState.Connected) {
            ConnectHalo(modifier = Modifier.size(CONNECT_CONTROL_DIAMETER))
        }

        Box(
            modifier =
            Modifier
                .size(CONNECT_CONTROL_DIAMETER)
                .clip(CircleShape)
                .background(colors.container)
                .clickable(role = Role.Button) {
                    // The guard: a second tap while Connecting is a no-op,
                    // not a queued second connect attempt.
                    if (state != ConnectVisualState.Connecting) onClick()
                }.semantics { contentDescription = actionDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = actionDescription,
                style = MaterialTheme.typography.titleLarge,
                color = colors.content,
                // Decorative relative to the parent's explicit
                // contentDescription above — without this, the merged
                // semantics tree would carry this text a second time.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * The connected-only glow behind [ConnectControl]. A private composable, not
 * inlined into [ConnectControl], specifically so the [rememberInfiniteTransition]
 * it owns lives or dies with this composable's own presence in composition —
 * see [ConnectControl]'s KDoc.
 */
@Composable
private fun ConnectHalo(modifier: Modifier = Modifier) {
    val haloColor = LocalSubspaceColors.current.connected
    val transition = rememberInfiniteTransition(label = "connect-halo")
    val alpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = CONNECT_HALO_MAX_ALPHA,
        animationSpec =
        infiniteRepeatable(
            // EaseInOut (androidx.compose.animation.core), not one of
            // Motion.kt's named curves: motion.css names this breath's curve
            // with the raw CSS keyword "ease-in-out"
            // (cubic-bezier(.42,0,.58,1)), which is exactly what the Compose
            // stdlib's EaseInOut already is — Motion.kt only carries the
            // design system's own *named* tokens (standard, emphasized,
            // spring), not this generic CSS keyword.
            animation = tween(durationMillis = CONNECT_HALO_BREATH_DURATION_MILLIS, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-halo-alpha",
    )

    Box(
        modifier =
        modifier
            .testTag(CONNECT_HALO_TEST_TAG)
            .graphicsLayer { this.alpha = alpha }
            // Unbounded: a halo that clipped to its own layout bounds would
            // have its blurred edge cut off, which is the opposite of the
            // soft glow the design calls for.
            .blur(radius = CONNECT_HALO_BLUR_RADIUS, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .background(color = haloColor, shape = CircleShape),
    )
}
