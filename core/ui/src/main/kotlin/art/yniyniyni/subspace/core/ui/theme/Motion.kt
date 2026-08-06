// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires even on named `const val`/curve-control-point
// declarations (this project's detekt.yml doesn't set ignorePropertyDeclaration).
// Every literal below is a token value transcribed from motion.css, already
// named by the val/const it initializes.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

// `tokens/motion.css` durations, in milliseconds. M3 Expressive: springier,
// more emphasized than baseline M3 — FAB morph, connect-button state
// changes, list item entrances.
const val MOTION_DURATION_SHORT_MILLIS: Int = 100
const val MOTION_DURATION_MEDIUM_MILLIS: Int = 250
const val MOTION_DURATION_LONG_MILLIS: Int = 400
const val MOTION_DURATION_EXTRA_LONG_MILLIS: Int = 600

/** `--motion-easing-standard`: `cubic-bezier(.2,0,0,1)`. */
val MotionEasingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** `--motion-easing-emphasized`: `cubic-bezier(.3,0,.8,.15)`. */
val MotionEasingEmphasized: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** `--motion-easing-emphasized-decelerate`: `cubic-bezier(.05,.7,.1,1)`. */
val MotionEasingEmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * `--motion-easing-emphasized-accelerate`: `cubic-bezier(.3,0,.8,.15)`.
 * Identical curve to [MotionEasingEmphasized] in the source token file —
 * kept as a separate name because the tokens are semantically distinct
 * (entering vs. exiting), even though today they share one curve.
 */
val MotionEasingEmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * `--motion-easing-spring`: `cubic-bezier(.34,1.56,.64,1)`. That curve's
 * second control point (y = 1.56) is past 1, i.e. it overshoots the target
 * before settling — a Compose [Easing] is a `Float -> Float` function
 * clamped to the 0..1 fraction domain and cannot represent that, so this is
 * expressed as a physical [spring] instead of a [CubicBezierEasing].
 * [Spring.DampingRatioHighBouncy] (0.2 — lower damping ratio means more
 * overshoot before settling) is the closest physical analogue to the
 * curve's bounce.
 */
fun <T> motionSpringSpec(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
    spring(
        dampingRatio = Spring.DampingRatioHighBouncy,
        stiffness = Spring.StiffnessMedium,
        visibilityThreshold = visibilityThreshold,
    )
