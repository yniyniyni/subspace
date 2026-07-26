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
}
