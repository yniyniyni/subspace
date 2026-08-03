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
}
