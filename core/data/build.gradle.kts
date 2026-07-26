// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
}

android {
    namespace = "art.yniyniyni.subspace.core.data"
}

dependencies {
    implementation(project(":core:model"))
}
