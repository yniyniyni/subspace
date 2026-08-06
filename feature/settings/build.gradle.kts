// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.compose")
    id("subspace.android.hilt")
}

android {
    namespace = "art.yniyniyni.subspace.feature.settings"
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
}
