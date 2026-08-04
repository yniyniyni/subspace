// SPDX-License-Identifier: AGPL-3.0-or-later
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
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

    // Task 20 (QrScanScreen/QrAnalyzer): ZXing decodes the QR, CameraX feeds
    // it frames. ARCHITECTURE.md §2/§14.7 — ZXing, not ML Kit, because ML
    // Kit is proprietary and depends on Play Services, which would foreclose
    // the IzzyOnDroid distribution path. zxing:core only, never
    // zxing-android-embedded — that artifact ships its own CaptureActivity
    // and theming, and this app is Compose-only. See THIRD_PARTY.md.
    implementation(libs.zxing.core)
    // AndroidX, not a Play-Services-backed camera API — no proprietary
    // dependency, same reasoning as the ZXing choice above.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

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
