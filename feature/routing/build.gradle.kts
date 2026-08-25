// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
plugins {
    id("subspace.android.compose")
    id("subspace.android.hilt")
}

android {
    namespace = "art.yniyniyni.subspace.feature.routing"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    // Routing deeplink parse lives here. Architecture §4 allows :feature:* to
    // depend on :core:parser; :feature:home and :feature:profiles already do.
    implementation(project(":core:parser"))
    // Shared camera surfaces, including M6's generic QR scanner, live here;
    // routing never reaches across to :feature:profiles (§4).
    implementation(project(":core:ui"))
    // PerAppSource.reapply(): VpnService.Builder's allow/deny calls apply at
    // establish() time only, so a changed selection needs the tunnel rebuilt
    // (§8). :feature:home already declares this same dependency.
    implementation(project(":service"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // RoutingQrScanRoute takes the routing list's own NavBackStackEntry, so a
    // scanned payload reaches the sheet the list already shows rather than a
    // second one. :feature:profiles' QrScanRoute takes the same dependency for
    // the same reason.
    implementation(libs.androidx.navigation.compose)
    // The active-selector radio's check/delete/create/warning glyphs and the
    // "Routing" settings row's own icon — same -core-only choice as every
    // other module. See THIRD_PARTY.md.
    implementation(libs.compose.material.icons.core)
    // GeoCategories parses the geosite.json/geoip.json sidecar with this rather than
    // org.json.JSONObject: fix round 1 review found this module's first draft added a
    // testImplementation(libs.org.json) purely to work around org.json being a stub on the
    // JVM unit-test classpath — a workaround `:core:xray`'s LibXrayInvokeTest already
    // considered and rejected by name for the identical reason (see that file's own KDoc).
    // kotlinx-serialization-json needs no compiler plugin here (no @Serializable class,
    // just JsonElement navigation) and is already an `implementation` dependency of
    // `:core:parser`, `:core:data` and `:app`, so this is not a new artifact in the app,
    // only a new module declaring one already present.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.assertions)
    // RoutingViewModel drives its state through viewModelScope, which needs a
    // Main dispatcher — same gap :feature:settings' own build.gradle.kts
    // documents.
    testImplementation(libs.kotlinx.coroutines.test)

    // First Compose UI test in this module (RoutingListScreenContentTest): the two activation-gate
    // markers and the empty state are layout/rendering facts a JVM state test cannot see — same
    // reasoning, and same additions, :feature:settings' own build.gradle.kts documents in full for
    // SettingsHwidLayoutTest. No device is reachable in this environment, so this test compiles but
    // does not run here — see its own KDoc.
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotest.assertions)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
