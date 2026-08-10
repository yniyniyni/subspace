// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

import javax.inject.Inject

/**
 * The two optional device-identifying headers from §A.4.1: `x-ver-os` and
 * `x-device-model`. Behind a seam, same reasoning as [HwidProvider]: it keeps
 * [SubscriptionFetcher] itself free of `android.os.Build`, so its request
 * building stays provable on the plain JVM rather than needing Robolectric or
 * `androidTest` — `android.os.Build.VERSION.RELEASE`/`Build.MODEL` are not
 * live values under a JVM unit test.
 */
public interface DeviceInfo {
    /** e.g. `"14"`. Empty string if unavailable. */
    public val osVersion: String

    /** e.g. `"Pixel 8"`. Empty string if unavailable. */
    public val model: String
}

/**
 * A [DeviceInfo] with both fields empty.
 *
 * The default for call sites — tests, and [SubscriptionFetcher]'s own
 * constructor default — that have no reason to touch `android.os.Build`.
 * Production wiring binds the real one in `NetworkModule`.
 */
public object EmptyDeviceInfo : DeviceInfo {
    override val osVersion: String = ""
    override val model: String = ""
}

/** Reads the two fields from `android.os.Build` — the only Android-coupled part. */
public class AndroidDeviceInfo
@Inject
constructor() : DeviceInfo {
    override val osVersion: String get() = android.os.Build.VERSION.RELEASE.orEmpty()
    override val model: String get() = android.os.Build.MODEL.orEmpty()
}
