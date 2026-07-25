// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.plugin.android.gradle)
    compileOnly(libs.plugin.kotlin.gradle)
    compileOnly(libs.plugin.kotlin.compose)
    compileOnly(libs.plugin.ksp.gradle)
    compileOnly(libs.plugin.hilt.gradle)
}
