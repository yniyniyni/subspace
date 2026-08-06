// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.compose")
    id("subspace.android.hilt")
}

android {
    namespace = "art.yniyniyni.subspace.feature.home"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:parser"))
    implementation(project(":core:ui"))
    implementation(project(":service"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // The "Add server" chip's leading icon. -core only, same choice and
    // reasoning as :app's NavItem icons — see THIRD_PARTY.md.
    implementation(libs.compose.material.icons.core)
    // subspace.android.library already wires junit + kotest-assertions for unit
    // tests, but not this: HomeViewModel drives its state through
    // viewModelScope, which needs a Main dispatcher. Without it, exercising
    // onConsentGranted in a JVM unit test throws before it does anything.
    testImplementation(libs.kotlinx.coroutines.test)
}
