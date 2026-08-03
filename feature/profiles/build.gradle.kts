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
    implementation(project(":core:ui"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // The overflow menu's rename/delete icons and the group caret — same
    // artifact :feature:home and :app already carry, same -core-only choice.
    // See THIRD_PARTY.md.
    implementation(libs.compose.material.icons.core)
    // ServersViewModel drives its state through viewModelScope, which needs a
    // Main dispatcher — same gap :feature:home's build.gradle.kts documents.
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
