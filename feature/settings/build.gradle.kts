// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
plugins {
    id("subspace.android.compose")
    id("subspace.android.hilt")
}

android {
    namespace = "space.getsub.feature.settings"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    // xrayCoreVersion() (Task 22): the About section's Xray-core version row.
    // :feature:* -> :core:* is a permitted edge (§4) — this is not the
    // forbidden :feature:*-to-:feature:* direction checkModuleBoundaries
    // enforces, and LibXrayInvoke itself stays internal to :core:xray;
    // xrayCoreVersion() is the one function :core:xray exports across this
    // boundary for exactly this call.
    implementation(project(":core:xray"))
    // TunnelSessionSource (F2 / ruling R39): Settings needs to know whether a session is
    // currently up, so a diagnostic-only setting like per-tag breakdown can tell the user when a
    // change takes effect immediately vs. waits for reconnect, rather than letting the switch's
    // new position imply the change is already live. Not a :feature:*-to-:feature:* edge
    // (checkModuleBoundaries only forbids that one, and :feature:home already depends on
    // :service the same way, via its own HomeModule/BoundTunnelConnection) — this exposes only
    // TunnelClient.state through a narrow interface, the same shape
    // :feature:home's own TunnelConnection already uses for the exact same underlying binder
    // connection.
    implementation(project(":service"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // Appearance's system/light/dark segmented control needs no icon (Material3
    // SegmentedButton draws its own selection check); About's three rows use
    // SettingRow's icon tile — same -core-only choice as every other module,
    // see THIRD_PARTY.md.
    implementation(libs.compose.material.icons.core)
    // SettingsViewModel drives its state through viewModelScope, which needs a
    // Main dispatcher — same gap :feature:home's and :feature:profiles' own
    // build.gradle.kts document.
    testImplementation(libs.kotlinx.coroutines.test)

    // First Compose UI instrumented tests in this module (M4 device run: the HWID value was
    // rendering as a vertical stack of single characters, which no JVM state test could see).
    // Same additions feature/profiles/build.gradle.kts documents in full.
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotest.assertions)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
