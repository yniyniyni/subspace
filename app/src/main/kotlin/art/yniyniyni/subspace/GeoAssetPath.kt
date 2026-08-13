// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.File

private const val TAG = "GeoAssetPath"

/**
 * The environment variable xray-core reads to find `geoip.dat` and `geosite.dat`.
 *
 * `common/platform/platform.go` declares `AssetLocation = "xray.location.asset"`,
 * and `EnvFlag.GetValue` checks that literal first and then `NormalizeEnvName`'s
 * upper-cased, dot-to-underscore form. Setting the normalised name is enough.
 */
private const val ASSET_LOCATION_ENV = "XRAY_LOCATION_ASSET"

/** Where installed geo databases live. Internal storage, not cache — same reasoning as the config file (§5.6). */
public fun geoAssetDirectory(context: Context): File = File(context.filesDir, "geo")

/**
 * Points xray-core at [geoAssetDirectory].
 *
 * ## Do not move this call
 *
 * **Without it, no `geoip:` or `geosite:` rule can ever resolve on Android.**
 * `GetAssetLocation` falls back to `filepath.Dir(os.Executable())`, which in an
 * Android app is `/system/bin` (the process executable is `app_process64`), plus
 * `/usr/local/share/xray`, `/usr/share/xray` and `/opt/share/xray` — none of
 * which exists or is writable. libXray v26.7.11 offers no hook of its own:
 * `RunXrayRequest` carries only `configPath` and there is no `initEnv`, unlike
 * AndroidLibXrayLite. Sources: research §1 and §2.
 *
 * **It must run before anything touches a `libXray.*` class.** Go copies the C
 * `environ` when its native library loads, and `os.LookupEnv` reads that copy —
 * so a `setenv` afterwards is silently ignored. Any reference to a `libXray`
 * class triggers `Seq.touch()` and therefore `System.loadLibrary`. This is called
 * from [SubspaceApplication.attachBaseContext], which is the earliest point a
 * `Context` exists and is strictly before Hilt's generated `onCreate` injects
 * anything that could reach `:core:xray`.
 *
 * **This is §10.2's category**: it looks like boilerplate that belongs beside the
 * other Xray setup in `TunnelService`, and moving it there produces a tunnel that
 * starts, connects, carries traffic, and silently ignores every routing rule —
 * with no exception and no log line anywhere. Device checklist item 2 exists to
 * observe the mechanism rather than trust this comment.
 *
 * @return true when the variable was set. A false is logged and not fatal: the
 *   tunnel still works, only geo-referencing rules cannot resolve, and the
 *   activation gate plus `testXray` both surface that specifically.
 */
public fun installGeoAssetPath(context: Context): Boolean {
    val dir = geoAssetDirectory(context)
    dir.mkdirs()
    return try {
        Os.setenv(ASSET_LOCATION_ENV, dir.absolutePath, true)
        true
    } catch (e: ErrnoException) {
        // §5.6: class name only. Nothing here quotes a path to the log.
        Log.e(TAG, "could not set $ASSET_LOCATION_ENV: ${e.javaClass.simpleName}")
        false
    }
}
