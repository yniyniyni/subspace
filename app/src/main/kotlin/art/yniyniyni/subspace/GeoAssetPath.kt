// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace

import android.content.Context
import java.io.File

/**
 * Where installed geo databases (`geoip.dat`, `geosite.dat`, and any custom
 * `.dat` a user adds) live. Internal storage, not cache — same reasoning as
 * the config file (§5.6).
 *
 * This lives in `:app` rather than `:core:data` because `GeoModule` — the one
 * module with both `GeoAssetRepository` and this `Context`-scoped path in
 * scope (§4) — needs it to supply `GeoAssetRoot`, and `:core:xray`'s
 * `XrayController` needs the identical path, so the two must read it from one
 * function rather than risk two copies drifting apart.
 *
 * ## This directory is *not* how xray-core finds it
 *
 * A prior version of this file also set `XRAY_LOCATION_ASSET` via
 * `android.system.Os.setenv`, called from
 * [SubspaceApplication.attachBaseContext] before anything could touch a
 * `libXray.*` class. That mechanism does not work, was never proven on
 * hardware until it was, and is the reason this file's own history is worth
 * knowing before "fixing" it back.
 *
 * `Os.setenv` writes libc's `environ`. Go's Android shared-library entry point
 * (`runtime/rt0_android_arm64.s`) starts the Go runtime with a synthetic argv
 * and an **empty envv**, so a gomobile-built library — which is what libXray
 * is — begins with no environment at all, regardless of what the process's C
 * `environ` holds at any point, in any process. `os.LookupEnv` inside Go
 * therefore never sees anything `Os.setenv` writes. Full derivation, verified
 * on a Pixel 8 by `AssetLocationProbeTest`:
 * `docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md` §2b.
 *
 * The only route that works is a Go-side `os.Setenv`, which libXray performs
 * when the invoke request carries an `env` object — upstream's own PR #133,
 * restored by `third_party/libxray-patches/0001-restore-invoke-env.patch`.
 * `XrayController` sends it on every `testXray`/`runXray` call, built from the
 * directory this function returns. See `XrayController`'s KDoc for why a
 * constructor parameter and not a call here.
 */
public fun geoAssetDirectory(context: Context): File = File(context.filesDir, "geo")
