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
}
