// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.library")
}

android {
    namespace = "art.yniyniyni.subspace.core.xray"
}

dependencies {
    implementation(project(":core:model"))
}
