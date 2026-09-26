// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ConnectionState
import space.getsub.core.model.Health

/**
 * Whether a detected [Health] becomes a publish (M8.5 spec §4.2, as amended).
 *
 * Generation-checked like every other transition: [detectorGeneration] is the
 * generation whose committed `Connected` installed the detector. A stale
 * sampler tick from a finished session — the one-emit residual
 * `TrafficSamplerLoop` documents — therefore cannot mark a newer one.
 */
@Suppress("ReturnCount") // One early return per distinct refusal: wrong generation, then not Connected.
internal fun nextHealthState(
    current: ConnectionState,
    detected: Health,
    detectorGeneration: Int,
    currentGeneration: Int,
): ConnectionState.Connected? {
    if (detectorGeneration != currentGeneration) return null
    val connected = current as? ConnectionState.Connected ?: return null
    return connected.takeIf { it.health != detected }?.copy(health = detected)
}
