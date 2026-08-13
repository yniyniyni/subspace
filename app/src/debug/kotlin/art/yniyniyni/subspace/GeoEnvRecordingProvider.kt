// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.system.Os

/**
 * Observes `XRAY_LOCATION_ASSET` at the one point in app startup that actually
 * exercises [installGeoAssetPath]'s "do not move this call" ordering claim.
 *
 * `GeoAssetPathTest`'s other tests all run as instrumented test *methods*,
 * which the platform only starts after `Application.onCreate` has already
 * returned — so they would stay green even if `installGeoAssetPath` moved from
 * `attachBaseContext` into `onCreate`, and catch only deletion or a move to a
 * different component entirely (`TunnelService`).
 *
 * A `<provider>`, in contrast, is instantiated by `ActivityThread` while it is
 * still binding the application — strictly *after* `Application.attachBaseContext`
 * returns and strictly *before* `Application.onCreate` runs (this is also how
 * `androidx.startup`'s `InitializationProvider`, already declared in the main
 * manifest, gets to run ahead of `onCreate`).
 *
 * ## Why this lives in `src/debug`, not `src/androidTest`
 *
 * A provider declared in `app/src/androidTest/AndroidManifest.xml` was tried
 * first and does not work: AGP packages `androidTest` into a **separate**
 * package (`art.yniyniyni.subspace.test`), and even though instrumentation
 * runs that package's test code inside the target app's process, the OS still
 * tracks the provider under the test package's own UID. Confirmed on-device
 * (Pixel 8, API 36): `ActivityThread.installContentProviders` — the eager,
 * pre-`onCreate` install this depends on — is scoped to providers owned by the
 * package whose `Application` is being created, so the test-package provider's
 * `onCreate` was never called at all; forcing a lazy load via
 * `ContentResolver.acquireContentProviderClient` then failed outright with
 * `SecurityException: ... that is not exported from UID <test-package-uid>`,
 * since the requesting process's UID is the *app's*, not the test package's.
 * `src/debug` merges this provider into the app's own manifest — the same
 * package, the same UID, the same eager-install path `InitializationProvider`
 * already uses — so `connectedDebugAndroidTest` (which builds the `debug`
 * variant) sees it in the one shared process. It is invisible to `release`
 * builds, since AGP never compiles `src/debug` into that variant.
 */
public class GeoEnvRecordingProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        recordedXrayLocationAsset = Os.getenv("XRAY_LOCATION_ASSET")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    public companion object {
        /**
         * `XRAY_LOCATION_ASSET` as seen from [onCreate], or null if nothing had set it yet.
         *
         * `@Volatile` because the provider is instantiated on the main thread during
         * process bind-up while the test method later reads this from the
         * instrumentation thread — there is no other synchronisation between the two.
         */
        @Volatile
        public var recordedXrayLocationAsset: String? = null
    }
}
