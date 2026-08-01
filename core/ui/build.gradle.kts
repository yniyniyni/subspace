// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.library")
    id("subspace.android.compose")
}

android {
    namespace = "art.yniyniyni.subspace.core.ui"
}

dependencies {
    implementation(project(":core:model"))

    // First Compose UI instrumented tests in the repo (ConnectControlTest).
    // subspace.android.library already wires testInstrumentationRunner and the
    // base androidTest kit (junit, androidx-test-runner/ext-junit,
    // coroutines-test) for every module, and subspace.android.compose already
    // puts the compose-bom on the androidTest classpath — this only adds what
    // those two convention plugins don't: the Compose test rule itself, the
    // kotest assertions the brief's second test uses (androidTestImplementation
    // isn't covered by the base plugin's testImplementation-only kotest wiring,
    // same gap core/data's build.gradle.kts documents), and the debug-only
    // manifest fragment createComposeRule() needs to host content under test.
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotest.assertions)
    debugImplementation(libs.compose.ui.test.manifest)

    // Forces the espresso-core version above the 3.5.0 compose-ui-test-junit4
    // pulls in transitively. 3.5.0's InputManagerEventInjectionStrategy looks
    // up the hidden, no-arg android.hardware.input.InputManager.getInstance()
    // via reflection to inject touch events; that method is gone on this
    // device's OS (Pixel 8, API 37), so every test failed with
    // NoSuchMethodException before a single assertion ran. 3.7.0 (2025-07-30)
    // replaced that reflection with getSystemService and is the fix per its
    // own release notes.
    androidTestImplementation(libs.androidx.test.espresso.core)
}
