// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
}

android {
    namespace = "space.getsub.core.network"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.okhttp)

    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
