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
    // For ShareLinkConverter: accepting a raw Xray config means asking the core
    // to interpret it rather than writing a second interpretation here.
    implementation(project(":core:xray"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
