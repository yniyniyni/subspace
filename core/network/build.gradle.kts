// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
}

android {
    namespace = "art.yniyniyni.subspace.core.network"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.okhttp)

    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
