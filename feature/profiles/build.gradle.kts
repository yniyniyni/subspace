// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
plugins {
    id("subspace.android.compose")
    id("subspace.android.hilt")
}

android {
    namespace = "art.yniyniyni.subspace.feature.profiles"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    // AddServerSheet (Task 19) is the first thing in this module to call
    // SubscriptionParser — the same pure-JVM module :feature:home already
    // depends on for the identical reason (§7).
    implementation(project(":core:parser"))
    implementation(project(":core:ui"))
    // TunnelClient: measurement runs in :bg, where §5.1's protector lives.
    // §4 permits a :feature: module depending on :service — it forbids the
    // reverse, and :feature:home already declares this same edge.
    implementation(project(":service"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // Fix round 1 (Task 20 wiring): QrScanRoute takes a NavBackStackEntry
    // directly (the Servers destination's own entry, resolved by :app —
    // this module cannot reference :app's route types, §4) so the scanned
    // result reaches the SAME ImportViewModel instance backing
    // AddServerSheet rather than a fresh one. That type was already on this
    // module's compile classpath transitively (via hilt-navigation-compose
    // -> navigation-compose), but it is now imported directly in source, so
    // it is declared explicitly rather than relied on implicitly. Same
    // artifact/version :app already carries — no new THIRD_PARTY.md entry,
    // matching how compose-ui/compose-material3/etc. are treated as
    // baseline stack, not a new capability.
    implementation(libs.androidx.navigation.compose)
    // AddServerSheet's "Import from file" button (Task 19):
    // rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()).
    // Not recorded as a new THIRD_PARTY.md entry — :app already carries this
    // artifact (MainActivity's VPN-consent launcher) and it is baseline
    // Activity/Compose plumbing, not a new capability the way e.g. Compose UI
    // Testing was when it first unlocked instrumented Compose tests.
    implementation(libs.androidx.activity.compose)
    // The overflow menu's rename/delete icons and the group caret — same
    // artifact :feature:home and :app already carry, same -core-only choice.
    // See THIRD_PARTY.md.
    implementation(libs.compose.material.icons.core)
    // ServersViewModel and ImportViewModel drive their state through
    // viewModelScope, which needs a Main dispatcher — same gap
    // :feature:home's build.gradle.kts documents.
    testImplementation(libs.kotlinx.coroutines.test)

    // First Compose UI instrumented tests in this module (fix round 1,
    // finding 3 — ServersDialogsTest). Same additions core/ui/build.gradle.kts
    // documents in full: subspace.android.library/subspace.android.compose
    // already wire the base androidTest kit and the compose-bom, this only
    // adds the test rule itself, kotest assertions on the androidTest
    // classpath, and the debug-only manifest fragment createComposeRule()
    // needs to host content under test.
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotest.assertions)
    debugImplementation(libs.compose.ui.test.manifest)
    // Same espresso-core pin core/ui/build.gradle.kts explains in full —
    // 3.5.0's reflective InputManager lookup is gone on API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
}
