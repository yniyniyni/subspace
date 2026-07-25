// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
}

android {
    namespace = "art.yniyniyni.subspace.service"
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:xray"))
    implementation(project(":core:data"))
}
